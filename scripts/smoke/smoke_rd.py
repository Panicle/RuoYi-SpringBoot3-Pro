#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 6 — 阶段8 研发加计扣除 端到端冒烟（任务卡 §六 10 组用例 + 回归 honor）
- 覆盖：预算维护 / 工资维护 / 工时闭环 / 复制上月 / 算法闭合 / 状态机 / 边界 / 数据权限 / 三张导出 / 回归
- 复用 smoke_honor.py 的登录/DB 直查工具 + smoke_contract.py 的 RSA 公钥
- 结果写 scripts/smoke/result_rd.jsonl（独立文件，不与 result*.jsonl 撞车）
- 只测不改业务代码；发现的 Bug 记入报告，返回给控制方裁决
- 测试账号：test_rd_res / test_rd_sci / test_rd_dl / test_rd_lhr
  （用户名 ≤15 字符，遵循上阶段教训）
- surcharge_rate 表只读不改（任务卡纪律）
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

import requests
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5

try:
    import dmPython  # 达梦
except Exception:  # pragma: no cover
    dmPython = None

try:
    import openpyxl  # allocation 表用 openpyxl 解析断言
except Exception:  # pragma: no cover
    openpyxl = None

BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8087")
ADMIN_USER = "admin"
ADMIN_PASS = "admin123"

DM_PASSWORD = os.environ.get("DM_PASSWORD", "Ruoyi12345")
DM_CONN_KW = dict(user="SYSDBA", password=DM_PASSWORD, server="localhost", port=5236)

PUBLIC_KEY_PEM = (
    "-----BEGIN PUBLIC KEY-----\n"
    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvVwyAyLabhi5SeUdgcVd\n"
    "GfDuaFmCLOS0MCgKpvlLNPh7lp/lXoj4XERSSQgzh7wBBIInlqytYZgOZAq+hdSn\n"
    "oKM+hhE7ytowK77kdbZw418XxK3Lx+Jft2+5l6SxsZAE916BJ46U6EK8QpABq9tL\n"
    "P1rUKTowTygFy6SWlBl+RbulRVPbt4m0gFObXM/eh5ZIm97hLsQcAuadQ0Kqb+02\n"
    "nsF2bmViBtMMK2Yj6IRwX9YV+N8cjsJXeSdsVGwF/fGtJ2xKq/FXE4rrtLK99BKw\n"
    "1L7ru2Po6tZKIVOGZgZnrRX6Iw/vMqSa+FnTxgfsNZDcWOqZ8K+aqQ7YdveGzvZk\n"
    "cwIDAQAB\n"
    "-----END PUBLIC KEY-----\n"
)

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RESULT_PATH = os.path.join(SCRIPT_DIR, "result_rd.jsonl")

# ============== 测试用户（独占 ID 段 90061+，与既有 smoke 脚本不冲突；用户名 ≤15 字符） ==============
# researcher：本课题参与 + leader 角色（105，data_scope=5）；用作本人填工时 + 工资越权样本
# labor_hr：本工资可见 + 仅看分摊管理（103，data_scope=1）— 工资越权拒访问的反向样本
# dept_leader：本室可见（104，data_scope=3）— 数据权限本室样本
# science_admin：全所（101，data_scope=1）— 全所样本 + 预算维护
RES_USER_ID    = 90061
LHR_USER_ID    = 90062
DL_USER_ID     = 90063
SCI_USER_ID    = 90064
RES2_USER_ID   = 90065   # 第二个 researcher（"他人" 跨课题同日合计样本）
RES_USERNAME   = "test_rd_res"
LHR_USERNAME   = "test_rd_lhr"
DL_USERNAME    = "test_rd_dl"
SCI_USERNAME   = "test_rd_sci"
RES2_USERNAME  = "test_rd_res2"

DEPT_D1 = 101             # 本室
DEPT_D2 = 100             # 他室

# surcharge_rate 10 项 rate_code（V1.0.1 dict_data.sql:203-213，按 rate_id 升序固定）
SURCHARGE_RATES = [
    ("edu",          0.0150),
    ("union",        0.0200),
    ("med",          0.0800),
    ("med_sup",      0.0200),
    ("pension",      0.1600),
    ("annuity",      0.0700),
    ("unemploy",     0.0070),
    ("injury",       0.0036),
    ("housing_fund", 0.1200),
    ("relief",       0.0030),
]

TEST_MARK = "smoke-task8-rd"

RESULTS: List[Dict[str, Any]] = []
STATE: Dict[str, Any] = {}


# ============================================================
#  通用工具
# ============================================================

def rsa_encrypt_password(plain: str) -> str:
    key = RSA.import_key(PUBLIC_KEY_PEM)
    cipher = PKCS1_v1_5.new(key)
    ct = cipher.encrypt(plain.encode("utf-8"))
    return base64.b64encode(ct).decode("ascii")


def wait_ready(timeout: int = 90) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = requests.get(BASE_URL + "/captchaImage", timeout=3)
            if r.status_code in (200, 401, 403, 404):
                return True
        except Exception:
            pass
        time.sleep(1.0)
    return False


def login(session: requests.Session, username: str, password: str) -> Tuple[Optional[str], Dict[str, Any]]:
    pwd_enc = rsa_encrypt_password(password)
    payload = {"username": username, "password": pwd_enc}
    try:
        r = session.post(BASE_URL + "/login", json=payload, timeout=10)
    except Exception as e:
        return None, {"_err": repr(e)}
    body = safe_json(r)
    out = {"status_code": r.status_code, "body": body, "raw": r.text[:2000]}
    token = None
    if isinstance(body, dict):
        if body.get("code") == 200:
            token = body.get("token")
        if token is None and isinstance(body.get("data"), dict):
            token = body["data"].get("token")
        if token is None and isinstance(body.get("data"), str):
            token = body["data"]
    return token, out


def safe_json(r: requests.Response) -> Any:
    try:
        return r.json()
    except Exception:
        return None


def record(case: str, ok: bool, req: Dict[str, Any], resp: Dict[str, Any], note: str = "") -> None:
    RESULTS.append({
        "case": case, "ok": ok, "note": note,
        "request": req, "response": resp,
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
    })
    flag = "PASS" if ok else "FAIL"
    print(f"[{flag}] {case} :: {note}")


def http(session: requests.Session, method: str, url: str, *, json_body: Any = None,
         params: Any = None, binary: bool = False) -> requests.Response:
    full = url if url.startswith("http://") or url.startswith("https://") else BASE_URL + url
    return session.request(method, full, json=json_body, params=params, timeout=30)


def db_query(sql: str, params: Optional[List[Any]] = None) -> Tuple[bool, Any]:
    if dmPython is None:
        return False, "dmPython not installed"
    try:
        conn = dmPython.connect(**DM_CONN_KW)
        try:
            cur = conn.cursor()
            if params is None:
                cur.execute(sql)
            else:
                cur.execute(sql, params)
            cols = [c[0] for c in cur.description] if cur.description else []
            rows = cur.fetchall()
            return True, {"columns": cols, "rows": rows}
        finally:
            conn.close()
    except Exception as e:
        return False, repr(e)


def db_execute(sql: str, params: Optional[List[Any]] = None) -> Tuple[bool, Any]:
    if dmPython is None:
        return False, "dmPython not installed"
    try:
        conn = dmPython.connect(**DM_CONN_KW)
        try:
            cur = conn.cursor()
            if params is None:
                cur.execute(sql)
            else:
                cur.execute(sql, params)
            conn.commit()
            return True, cur.rowcount
        finally:
            conn.close()
    except Exception as e:
        return False, repr(e)


def get_data(body: Any) -> Any:
    """RuoYi 两种响应封装：
    - AjaxResult: { code, msg, data: <payload> }
    - TableDataInfo: { code, msg, total, rows: [...] }（无 data 包裹；list/paged 端点）
    本函数只认 AjaxResult 形态。TableDataInfo 端点直接 b.get("rows") / b.get("total")。"""
    if isinstance(body, dict) and body.get("code") == 200:
        return body.get("data")
    return None


def tdi_rows(body: Any) -> Optional[List[Dict[str, Any]]]:
    """TableDataInfo 端点统一取 rows。"""
    if isinstance(body, dict) and body.get("code") == 200:
        return body.get("rows")
    return None


def is_business_reject(body: Any) -> bool:
    """业务拒绝统一判定：code != 200（包含 500 ServiceException / 403 权限 / 400 参数）。"""
    if not isinstance(body, dict):
        return False
    return body.get("code") not in (200, None)


def sleep_anti_repeat(sec: float = 2.5) -> None:
    """R&D 端点带 @RepeatSubmit(interval=2000)，串行调用需停顿。"""
    time.sleep(sec)


# ============================================================
#  测试用户 / 测试数据准备
# ============================================================

def setup_test_users() -> Tuple[bool, str]:
    """建 test_rd_res(105) / test_rd_lhr(103) / test_rd_dl(104) / test_rd_sci(101) / test_rd_res2(105)。"""
    ok, res = db_query("SELECT PASSWORD FROM RUOYI.SYS_USER WHERE USER_NAME='admin'")
    if not ok or not res["rows"]:
        return False, "admin hash 读取失败: " + str(res)
    admin_hash = res["rows"][0][0]
    users = [
        (RES_USER_ID,   RES_USERNAME,  "冒烟RD科研员",   DEPT_D1, 105),
        (RES2_USER_ID,  RES2_USERNAME, "冒烟RD他人",     DEPT_D1, 105),
        (LHR_USER_ID,   LHR_USERNAME,  "冒烟RD工资员",   DEPT_D1, 103),
        (DL_USER_ID,    DL_USERNAME,   "冒烟RD室主任",   DEPT_D1, 104),
        (SCI_USER_ID,   SCI_USERNAME,  "冒烟RD科管",     DEPT_D1, 101),
    ]
    for uid, uname, nick, dept, role_id in users:
        db_execute("DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID = ?", [uid])
        db_execute("DELETE FROM RUOYI.SYS_USER WHERE USER_ID = ?", [uid])
        ok, r = db_execute(
            "INSERT INTO RUOYI.SYS_USER (USER_ID, DEPT_ID, USER_NAME, NICK_NAME, USER_TYPE, STATUS, "
            "DEL_FLAG, PASSWORD, CREATE_BY, CREATE_TIME, REMARK, TENANT_ID, TRY_COUNT) "
            "VALUES (?, ?, ?, ?, '00', '0', '0', ?, 'admin', SYSDATE, ?, '000000', 0)",
            [uid, dept, uname, nick, admin_hash, TEST_MARK])
        if not ok:
            return False, f"建用户 {uname} 失败: {r}"
        if role_id is not None:
            ok2, r2 = db_execute(
                "INSERT INTO RUOYI.SYS_USER_ROLE (USER_ID, ROLE_ID) VALUES (?, ?)", [uid, role_id])
            if not ok2:
                return False, f"绑角色 {uname} 失败: {r2}"
    return True, "ok"


def cleanup_test_data() -> Tuple[bool, str]:
    """按 TEST_MARK 物理清理：rd_labor_* / rd_worktime_* / project_member / project / 测试用户。
    surcharge_rate 只读不改（任务卡纪律）。"""
    # 1. rd_labor_allocation（按 remark — 但分配行 remark 默认空，所以走 project_id in 子查）
    ok, r = db_execute(
        "DELETE FROM RUOYI.RD_LABOR_ALLOCATION WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 rd_labor_allocation 失败: " + str(r)
    # 2. rd_worktime_monthly + rd_worktime_daily
    ok, r = db_execute(
        "DELETE FROM RUOYI.RD_WORKTIME_MONTHLY WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 rd_worktime_monthly 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.RD_WORKTIME_DAILY WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 rd_worktime_daily 失败: " + str(r)
    # 3. rd_labor_budget
    ok, r = db_execute(
        "DELETE FROM RUOYI.RD_LABOR_BUDGET WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 rd_labor_budget 失败: " + str(r)
    # 4. rd_researcher_salary（按 researcher_id 走本批用户）
    ok, r = db_execute(
        "DELETE FROM RUOYI.RD_RESEARCHER_SALARY WHERE RESEARCHER_ID IN (?, ?, ?, ?, ?)",
        [RES_USER_ID, LHR_USER_ID, DL_USER_ID, SCI_USER_ID, RES2_USER_ID])
    if not ok:
        return False, "清 rd_researcher_salary 失败: " + str(r)
    # 5. project_member + project
    ok, r = db_execute(
        "DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 project_member 失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清 project 失败: " + str(r)
    # 6. 测试用户
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID IN (?, ?, ?, ?, ?)",
        [RES_USER_ID, LHR_USER_ID, DL_USER_ID, SCI_USER_ID, RES2_USER_ID])
    if not ok:
        return False, "清 user_role 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?, ?, ?)",
        [RES_USER_ID, LHR_USER_ID, DL_USER_ID, SCI_USER_ID, RES2_USER_ID])
    if not ok:
        return False, "清 user 失败: " + str(r)
    return True, "ok"


# ============================================================
#  辅助：建课题 / 工资 / 工时 / 预算 / 分摊 / 导出
# ============================================================

def add_project(sess, name: str, leader_id: int, dept_id: int) -> Tuple[Optional[int], Dict[str, Any]]:
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {
        "projectName": name,
        "projectType": "NATIONAL",
        "leaderId": leader_id,
        "projectNo": f"KY-RD-{(STATE['no_seq'] % 900) + 100:03d}",
        "projectCategory": "A",
        "specialty": "Y",
        "deptId": dept_id,
        "remark": TEST_MARK,
    }
    r = http(sess, "POST", "/biz/project", json_body=body)
    b = safe_json(r)
    data = get_data(b)
    pid = data.get("projectId") if isinstance(data, dict) else None
    return pid, {"body": body, "resp": b, "projectId": pid}


def add_project_member(sess, project_id: int, user_id: int) -> Tuple[bool, Dict[str, Any]]:
    body = {"projectId": project_id, "members": [{"userId": user_id, "role": "PARTICIPANT"}]}
    r = http(sess, "POST", "/biz/project/member", json_body=body)
    b = safe_json(r)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    return ok, {"body": body, "resp": b}


# ============================================================
#  R&D 端点封装
# ============================================================

def rd_budget_list(sess, project_id: int, year: int) -> Tuple[int, Any]:
    r = http(sess, "GET", "/biz/rd/budget/list", params={"projectId": project_id, "year": year})
    return r.status_code, safe_json(r)


def rd_budget_save(sess, body: Dict[str, Any]) -> Tuple[int, Any]:
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/rd/budget/save", json_body=body)
    return r.status_code, safe_json(r)


def rd_salary_list(sess, params: Optional[Dict[str, Any]] = None) -> Tuple[int, Any]:
    p = dict(params or {})
    r = http(sess, "GET", "/biz/rd/salary/list", params=p)
    return r.status_code, safe_json(r)


def rd_salary_save(sess, body: Dict[str, Any]) -> Tuple[int, Any]:
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/rd/salary/save", json_body=body)
    return r.status_code, safe_json(r)


def rd_worktime_calendar(sess, project_id: int, researcher_id: int, month: str) -> Tuple[int, Any]:
    r = http(sess, "GET", "/biz/rd/worktime/calendar",
             params={"projectId": project_id, "researcherId": researcher_id, "month": month})
    return r.status_code, safe_json(r)


def rd_worktime_save(sess, body: Dict[str, Any]) -> Tuple[int, Any]:
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/rd/worktime/save", json_body=body)
    return r.status_code, safe_json(r)


def rd_worktime_copy(sess, body: Dict[str, Any]) -> Tuple[int, Any]:
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/rd/worktime/copyLastMonth", json_body=body)
    return r.status_code, safe_json(r)


def rd_worktime_monthly(sess, params: Optional[Dict[str, Any]] = None) -> Tuple[int, Any]:
    p = dict(params or {})
    p.setdefault("pageNum", 1)
    p.setdefault("pageSize", 200)
    r = http(sess, "GET", "/biz/rd/worktime/monthly/list", params=p)
    return r.status_code, safe_json(r)


def rd_alloc_calc(sess, body: Dict[str, Any]) -> Tuple[int, Any]:
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/rd/alloc/calc", json_body=body)
    return r.status_code, safe_json(r)


def rd_alloc_list(sess, project_id: int, month: str) -> Tuple[int, Any]:
    r = http(sess, "GET", "/biz/rd/alloc/list",
             params={"projectId": project_id, "month": month, "pageNum": 1, "pageSize": 50})
    return r.status_code, safe_json(r)


def rd_alloc_dashboard(sess, project_id: int, month: str) -> Tuple[int, Any]:
    r = http(sess, "GET", "/biz/rd/alloc/dashboard",
             params={"projectId": project_id, "month": month})
    return r.status_code, safe_json(r)


def rd_alloc_confirm(sess, body: Dict[str, Any]) -> Tuple[int, Any]:
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/rd/alloc/confirm", json_body=body)
    return r.status_code, safe_json(r)


def rd_alloc_revoke(sess, body: Dict[str, Any]) -> Tuple[int, Any]:
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/rd/alloc/revoke", json_body=body)
    return r.status_code, safe_json(r)


def rd_export_worktime(sess, body: Dict[str, Any]) -> Tuple[int, bytes, Dict[str, str]]:
    r = http(sess, "POST", "/biz/rd/export/worktime", json_body=body)
    return r.status_code, r.content, {
        "content_type": r.headers.get("Content-Type", ""),
        "content_disposition": r.headers.get("Content-disposition", ""),
    }


def rd_export_allocation(sess, body: Dict[str, Any]) -> Tuple[int, bytes, Dict[str, str]]:
    r = http(sess, "POST", "/biz/rd/export/allocation", json_body=body)
    return r.status_code, r.content, {
        "content_type": r.headers.get("Content-Type", ""),
        "content_disposition": r.headers.get("Content-disposition", ""),
    }


def rd_export_summary(sess, body: Dict[str, Any]) -> Tuple[int, bytes, Dict[str, str]]:
    r = http(sess, "POST", "/biz/rd/export/summary", json_body=body)
    return r.status_code, r.content, {
        "content_type": r.headers.get("Content-Type", ""),
        "content_disposition": r.headers.get("Content-disposition", ""),
    }


# ============================================================
#  DB 辅助
# ============================================================

def db_allocations(project_id: int, month: str) -> Tuple[bool, Any]:
    ok, res = db_query(
        "SELECT ALLOC_ID, RESEARCHER_ID, ALLOCATED_AMOUNT, SURCHARGE_TOTAL, GRAND_TOTAL, "
        "MONTHLY_HOURS, HOURLY_RATE, SURCHARGE_DETAIL, STATUS, CONFIRM_BY, DEL_FLAG "
        "FROM RUOYI.RD_LABOR_ALLOCATION WHERE PROJECT_ID = ? AND MONTH = ? ORDER BY RESEARCHER_ID ASC",
        [project_id, month])
    return ok, res["rows"] if ok else None


def db_worktime_daily(project_id: int, researcher_id: int, month: str) -> Tuple[bool, Any]:
    ok, res = db_query(
        "SELECT ID, RESEARCHER_ID, WORK_DATE, RD_HOURS, DEL_FLAG "
        "FROM RUOYI.RD_WORKTIME_DAILY WHERE PROJECT_ID = ? AND RESEARCHER_ID = ? "
        "AND TO_CHAR(WORK_DATE, 'YYYY-MM') = ? ORDER BY WORK_DATE ASC",
        [project_id, researcher_id, month])
    return ok, res["rows"] if ok else None


def db_worktime_monthly(project_id: int, researcher_id: int, month: str) -> Tuple[bool, Any]:
    ok, res = db_query(
        "SELECT TOTAL_RD_HOURS, CUMULATIVE_HOURS FROM RUOYI.RD_WORKTIME_MONTHLY "
        "WHERE PROJECT_ID = ? AND RESEARCHER_ID = ? AND MONTH = ?",
        [project_id, researcher_id, month])
    return ok, res["rows"][0] if ok and res["rows"] else (None, None)


def db_salary(researcher_id: int, month: str) -> Tuple[bool, Any]:
    ok, res = db_query(
        "SELECT MONTHLY_SALARY, DEL_FLAG FROM RUOYI.RD_RESEARCHER_SALARY "
        "WHERE RESEARCHER_ID = ? AND SALARY_MONTH = ?",
        [researcher_id, month])
    return ok, res["rows"][0] if ok and res["rows"] else (None, None)


def db_budget(project_id: int, year: int, month: int) -> Tuple[bool, Any]:
    ok, res = db_query(
        "SELECT TOTAL_AMOUNT, STATUS FROM RUOYI.RD_LABOR_BUDGET "
        "WHERE PROJECT_ID = ? AND BUDGET_YEAR = ? AND MONTH = ?",
        [project_id, year, month])
    return ok, res["rows"][0] if ok and res["rows"] else (None, None)


# ============================================================
#  Case 00：造测试数据
# ============================================================

def case_00_prepare(sess_admin, sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # P_OWN：res 主持本室课题（本人主持）
    pid_own, info = add_project(sess_admin, f"{TEST_MARK}-本人主持", leader_id=RES_USER_ID, dept_id=DEPT_D1)
    if pid_own is None:
        return {"own": info}, False, "建 P_OWN 失败"
    STATE["p_own_id"] = pid_own
    out["own"] = info
    # P_OTHER：res2 主持他室课题（researcher 不可见）
    pid_other, info_o = add_project(sess_admin, f"{TEST_MARK}-他室他人", leader_id=RES2_USER_ID, dept_id=DEPT_D2)
    if pid_other is None:
        return {"other": info_o}, False, "建 P_OTHER 失败"
    STATE["p_other_id"] = pid_other
    out["other"] = info_o
    # P_DEPT_DL：DL 主持本室（DL 可见）
    pid_dl, info_d = add_project(sess_admin, f"{TEST_MARK}-室主任本室", leader_id=DL_USER_ID, dept_id=DEPT_D1)
    if pid_dl is None:
        return {"dept": info_d}, False, "建 P_DEPT_DL 失败"
    STATE["p_dept_id"] = pid_dl
    out["dept"] = info_d
    return out, True, ""


# ============================================================
#  Case 01：预算维护（science_admin 建测试课题 → save 3 个月 → list 12 行缺月零值；researcher save 403）
# ============================================================

def case_01_budget(sess_sci, sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    project_id = STATE["p_own_id"]
    year = 2026
    # 1.1 save 3 个月（3/5/7）— 10000/20000/30000
    save_body = {
        "projectId": project_id,
        "year": year,
        "months": [
            {"month": 3, "totalAmount": 10000},
            {"month": 5, "totalAmount": 20000},
            {"month": 7, "totalAmount": 30000},
        ],
    }
    code, b = rd_budget_save(sess_sci, save_body)
    out["save"] = {"code": code, "body": b}
    save_ok = code == 200 and isinstance(b, dict) and b.get("code") == 200
    # DB 落库直查
    db_ok_all = True
    db_rows = []
    for m, expected in [(3, 10000), (5, 20000), (7, 30000)]:
        ok, row = db_budget(project_id, year, m)
        db_rows.append({"month": m, "row": row, "expected": expected,
                        "ok": ok and row is not None and float(row[0]) == expected})
        if not (ok and row is not None and float(row[0]) == expected):
            db_ok_all = False
    out["db_rows"] = db_rows
    # 1.2 list 12 行 — 应返 12 行；3/5/7 = 上述值；其余 9 行 total_amount=0 budgetId=null
    code_l, bl = rd_budget_list(sess_sci, project_id, year)
    out["list"] = {"code": code_l, "body": bl}
    list_data = get_data(bl) if isinstance(bl, dict) else None
    list_count_ok = isinstance(list_data, list) and len(list_data) == 12
    # 校验缺月补零值（totalAmount=0 / budgetId=None）
    zero_rows_ok = True
    if isinstance(list_data, list):
        for row in list_data:
            m = row.get("month")
            if m in (3, 5, 7):
                if float(row.get("totalAmount") or 0) == 0:
                    zero_rows_ok = False
            else:
                # 缺月行 totalAmount 应为 0 且 budgetId 应为 None
                if float(row.get("totalAmount") or 0) != 0:
                    zero_rows_ok = False
                if row.get("budgetId") is not None:
                    zero_rows_ok = False
    # 1.3 researcher save 403（Service 层兜底：scoped 闸门抛"无权访问"）
    code2, b2 = rd_budget_save(sess_res, save_body)
    res_msg = str(b2.get("msg") or "") if isinstance(b2, dict) else ""
    res_reject = is_business_reject(b2) and (
        "无权" in res_msg or "权限" in res_msg or "403" in str(b2.get("code", "")))
    out["res_reject"] = {"code": code2, "body": b2, "msg": res_msg, "rejected": res_reject}
    ok_all = save_ok and db_ok_all and list_count_ok and zero_rows_ok and res_reject
    return out, ok_all, "" if ok_all else (
        f"save={save_ok} db={db_ok_all} list12={list_count_ok} zero={zero_rows_ok} res_reject={res_reject}")


# ============================================================
#  Case 02：工资维护（save upsert 同键不增行；researcher /salary/list 拒；labor_hr 可见）
# ============================================================

def case_02_salary(sess_admin, sess_res, sess_lhr) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    target_month = "2026-03"
    target_user = RES_USER_ID
    # 2.1 首次 save
    body = {"researcherId": target_user, "salaryMonth": target_month, "monthlySalary": 10000.50}
    code1, b1 = rd_salary_save(sess_admin, body)
    out["save1"] = {"code": code1, "body": b1}
    save1_ok = code1 == 200 and isinstance(b1, dict) and b1.get("code") == 200
    # DB 落库
    ok_db, row_db = db_salary(target_user, target_month)
    out["db_after_save1"] = {"row": row_db, "ok": ok_db and row_db is not None}
    # 2.2 同键二次 save（monthlySalary=12000）— 应 UPDATE 不新增行（应用层查重）
    body2 = dict(body)
    body2["monthlySalary"] = 12000
    code2, b2 = rd_salary_save(sess_admin, body2)
    out["save2"] = {"code": code2, "body": b2}
    save2_ok = code2 == 200 and isinstance(b2, dict) and b2.get("code") == 200
    # DB 二次 save 后应仍 1 行（UNIQUE 索引兜底）
    ok_count, cnt_res = db_query(
        "SELECT COUNT(*) FROM RUOYI.RD_RESEARCHER_SALARY WHERE RESEARCHER_ID = ? AND SALARY_MONTH = ?",
        [target_user, target_month])
    db_row_count = cnt_res["rows"][0][0] if ok_count else None
    out["db_count_after_save2"] = db_row_count
    upsert_ok = (db_row_count == 1)
    # DB 值是否更新到 12000
    ok_db2, row_db2 = db_salary(target_user, target_month)
    val_updated = (ok_db2 and row_db2 is not None and float(row_db2[0]) == 12000)
    out["db_value_updated"] = val_updated
    # 2.3 researcher 调 /salary/list → @PreAuthorize 拒（业务 403 语义）
    code3, b3 = rd_salary_list(sess_res, {"researcherId": target_user, "salaryMonth": target_month})
    res_msg = str(b3.get("msg") or "") if isinstance(b3, dict) else ""
    res_reject = is_business_reject(b3) and (
        "无权查看工资数据" in res_msg or "权限" in res_msg or b3.get("code") == 403)
    out["res_list_reject"] = {"code": code3, "body": b3, "msg": res_msg, "rejected": res_reject}
    # 2.4 labor_hr list → 可见（/salary/list 是 TableDataInfo 端点，rows 在顶层）
    code4, b4 = rd_salary_list(sess_lhr, {"researcherId": target_user, "salaryMonth": target_month})
    rows_arr = tdi_rows(b4) or []
    lhr_sees = (code4 == 200 and isinstance(b4, dict) and b4.get("code") == 200 and len(rows_arr) >= 1)
    out["lhr_list"] = {"code": code4, "body": b4, "rows_count": len(rows_arr), "sees": lhr_sees}
    ok_all = save1_ok and save2_ok and upsert_ok and val_updated and res_reject and lhr_sees
    return out, ok_all, "" if ok_all else (
        f"save1={save1_ok} save2={save2_ok} upsert={upsert_ok} val={val_updated} "
        f"res_reject={res_reject} lhr_sees={lhr_sees}")


# ============================================================
#  Case 03：工时闭环（researcher 填本人+本人参与课题 5 天 → calendar 回读 + monthTotal；改 1 天为 0 软删；单日 25h 拒；两课题同日 13+13 拒；researcher 给他人填拒；monthly/list 汇总）
# ============================================================

def case_03_worktime(sess_res, sess_admin, sess_res2) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own_id"]
    p_other = STATE["p_other_id"]  # res2 主持他室 — researcher 给他人填拒走该路径
    month = "2026-03"
    # 3.1 researcher 给本人 + p_own 填 5 天（5/6/7/8/9 → 各 8h）
    days = [
        {"workDate": f"{month}-05", "rdHours": 8},
        {"workDate": f"{month}-06", "rdHours": 8},
        {"workDate": f"{month}-07", "rdHours": 8},
        {"workDate": f"{month}-08", "rdHours": 8},
        {"workDate": f"{month}-09", "rdHours": 8},
    ]
    body = {"projectId": p_own, "researcherId": RES_USER_ID, "month": month, "days": days}
    code, b = rd_worktime_save(sess_res, body)
    out["save5d"] = {"code": code, "body": b}
    save5d_ok = code == 200 and isinstance(b, dict) and b.get("code") == 200
    # 3.2 calendar 回读 — 逐日相等 + monthTotal=40
    code_c, b_c = rd_worktime_calendar(sess_res, p_own, RES_USER_ID, month)
    cal_data = get_data(b_c) if isinstance(b_c, dict) else None
    out["calendar"] = {"code": code_c, "body": b_c}
    # 校验 days
    cal_days_ok = False
    cal_month_total_ok = False
    if isinstance(cal_data, dict):
        days_arr = cal_data.get("days") or []
        # 逐日相等（按 workDate 字符串）
        expected = {f"{month}-0{i}": 8 for i in range(5, 10)}
        actual = {d.get("workDate"): float(d.get("rdHours") or 0) for d in days_arr}
        cal_days_ok = actual == expected
        cal_month_total_ok = float(cal_data.get("monthTotal") or 0) == 40.0
    out["cal_days_ok"] = cal_days_ok
    out["cal_month_total_ok"] = cal_month_total_ok
    # DB 5 行有效
    ok_db, rows_db = db_worktime_daily(p_own, RES_USER_ID, month)
    db_daily_ok = (ok_db and len(rows_db) == 5 and all(r[4] == "0" for r in rows_db))
    out["db_daily_ok"] = db_daily_ok
    # 月汇总行 total_rd_hours=40 / cumulative_hours=40
    ok_m, row_m = db_worktime_monthly(p_own, RES_USER_ID, month)
    monthly_ok = (ok_m and row_m is not None and float(row_m[0]) == 40.0)
    out["monthly_ok"] = monthly_ok
    # 3.3 改 1 天为 0 → 软删（DB del_flag=2）
    zero_body = {"projectId": p_own, "researcherId": RES_USER_ID, "month": month,
                 "days": [{"workDate": f"{month}-05", "rdHours": 0}]}
    code_z, b_z = rd_worktime_save(sess_res, zero_body)
    # rdHours=0 视为软删，toAjax 返回 int 受影响行数 → AjaxResult.data=0；
    # 但 RuoYi toAjax 0 行会返回 code=500（业务"失败"语义）— 已知；只看 HTTP 200 + DB del_flag=2
    softdel_api_ok = (code_z == 200 and isinstance(b_z, dict)
                      and (b_z.get("code") in (200, 500)))
    out["zero_body_resp"] = {"code": code_z, "body": b_z}
    # DB：当日行 del_flag='2'
    ok_d, val_d = db_query(
        "SELECT DEL_FLAG FROM RUOYI.RD_WORKTIME_DAILY WHERE PROJECT_ID = ? AND RESEARCHER_ID = ? "
        "AND TO_CHAR(WORK_DATE, 'YYYY-MM-DD') = ?",
        [p_own, RES_USER_ID, f"{month}-05"])
    db_zero_del = (ok_d and val_d["rows"] and val_d["rows"][0][0] == "2")
    out["softdel_api_ok"] = softdel_api_ok
    out["softdel_db"] = db_zero_del
    # 3.4 单日 25h 拒
    over_body = {"projectId": p_own, "researcherId": RES_USER_ID, "month": month,
                 "days": [{"workDate": f"{month}-10", "rdHours": 25}]}
    code_o, b_o = rd_worktime_save(sess_res, over_body)
    over_msg = str(b_o.get("msg") or "") if isinstance(b_o, dict) else ""
    over_reject = (code_o == 200 and isinstance(b_o, dict) and b_o.get("code") != 200
                   and ("24" in over_msg or "超过" in over_msg))
    out["over_reject"] = {"code": code_o, "body": b_o, "msg": over_msg, "rejected": over_reject}
    # 3.5 两课题同日 13+13 拒（跨课题 24h）
    # 先在 p_other 填 res2 本人 13h（admin 代填，因 researcher 不能跨本人相关；这里用 res2 本人）
    cross_body_1 = {"projectId": p_other, "researcherId": RES2_USER_ID, "month": month,
                    "days": [{"workDate": f"{month}-11", "rdHours": 13}]}
    code_x1, b_x1 = rd_worktime_save(sess_res2, cross_body_1)
    out["cross_save1"] = {"code": code_x1, "body": b_x1}
    # 再在 p_own 填 res2（本人） 13h（admin 代填跨人）
    cross_body_2 = {"projectId": p_own, "researcherId": RES2_USER_ID, "month": month,
                    "days": [{"workDate": f"{month}-11", "rdHours": 13}]}
    code_x2, b_x2 = rd_worktime_save(sess_admin, cross_body_2)
    out["cross_save2"] = {"code": code_x2, "body": b_x2}
    cross_msg = str(b_x2.get("msg") or "") if isinstance(b_x2, dict) else ""
    cross_reject = (code_x2 == 200 and isinstance(b_x2, dict) and b_x2.get("code") != 200
                    and ("跨课题" in cross_msg or "24" in cross_msg or "超过" in cross_msg))
    out["cross_reject"] = {"code": code_x2, "body": b_x2, "msg": cross_msg, "rejected": cross_reject}
    # 3.6 researcher 给他人填拒
    other_body = {"projectId": p_own, "researcherId": RES2_USER_ID, "month": month,
                  "days": [{"workDate": f"{month}-12", "rdHours": 4}]}
    code_ot, b_ot = rd_worktime_save(sess_res, other_body)
    other_msg = str(b_ot.get("msg") or "") if isinstance(b_ot, dict) else ""
    other_reject = (code_ot == 200 and isinstance(b_ot, dict) and b_ot.get("code") != 200
                    and ("无权" in other_msg or "代他人" in other_msg or "他人" in other_msg))
    out["other_reject"] = {"code": code_ot, "body": b_ot, "msg": other_msg, "rejected": other_reject}
    # 3.7 monthly/list 汇总行 total 与日和一致（researcher 自身视角应见本人行；
    #     /monthly/list 是 TableDataInfo 端点，rows 在顶层）
    code_m, b_m = rd_worktime_monthly(sess_res, {"projectId": p_own})
    rows_m = tdi_rows(b_m) or []
    # 找到 (res 本人, p_own, month) 行
    target_row = None
    for r in rows_m:
        if int(r.get("researcherId") or -1) == RES_USER_ID and int(r.get("projectId") or -1) == p_own and r.get("month") == month:
            target_row = r
            break
    list_total_ok = (target_row is not None
                     and float(target_row.get("totalRdHours") or 0) == 32.0)  # 40 - 8(softdel)
    out["monthly_list_ok"] = list_total_ok
    out["monthly_list_row"] = target_row
    ok_all = (save5d_ok and cal_days_ok and cal_month_total_ok and db_daily_ok and monthly_ok
              and softdel_api_ok and db_zero_del and over_reject
              and cross_reject and other_reject and list_total_ok)
    return out, ok_all, "" if ok_all else (
        f"save5d={save5d_ok} days={cal_days_ok} monthTotal={cal_month_total_ok} "
        f"db_daily={db_daily_ok} monthly={monthly_ok} softdel_api={softdel_api_ok} "
        f"softdel_db={db_zero_del} over={over_reject} cross={cross_reject} "
        f"other={other_reject} list_total={list_total_ok}")


# ============================================================
#  Case 04：复制上月（res 本人 + p_own 填 3 天 → copyLastMonth copiedCount=3 → 再复制 skippedCount=3）
# ============================================================

def case_04_copy_last_month(sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own_id"]
    prev_month = "2026-04"  # 当月 03 → 上月 04（按 previousMonth 算法）
    curr_month = "2026-05"
    # 4.1 fill 上月（04）3 天 — 5/6/7 各 6h
    fill_body = {"projectId": p_own, "researcherId": RES_USER_ID, "month": prev_month,
                 "days": [
                     {"workDate": f"{prev_month}-05", "rdHours": 6},
                     {"workDate": f"{prev_month}-06", "rdHours": 6},
                     {"workDate": f"{prev_month}-07", "rdHours": 6},
                 ]}
    code_f, b_f = rd_worktime_save(sess_res, fill_body)
    out["fill_prev"] = {"code": code_f, "body": b_f}
    fill_ok = code_f == 200 and isinstance(b_f, dict) and b_f.get("code") == 200
    # 4.2 copyLastMonth → copiedCount=3
    copy_body = {"projectId": p_own, "researcherId": RES_USER_ID, "month": curr_month}
    code_c1, b_c1 = rd_worktime_copy(sess_res, copy_body)
    out["copy1"] = {"code": code_c1, "body": b_c1}
    data1 = get_data(b_c1) if isinstance(b_c1, dict) else None
    copied1 = data1.get("copiedCount") if isinstance(data1, dict) else None
    skipped1 = data1.get("skippedCount") if isinstance(data1, dict) else None
    copy1_ok = (code_c1 == 200 and isinstance(b_c1, dict) and b_c1.get("code") == 200
                and copied1 == 3)
    # 4.3 再复制 → skippedCount=3（目标日已存在）
    code_c2, b_c2 = rd_worktime_copy(sess_res, copy_body)
    out["copy2"] = {"code": code_c2, "body": b_c2}
    data2 = get_data(b_c2) if isinstance(b_c2, dict) else None
    copied2 = data2.get("copiedCount") if isinstance(data2, dict) else None
    skipped2 = data2.get("skippedCount") if isinstance(data2, dict) else None
    copy2_ok = (code_c2 == 200 and isinstance(b_c2, dict) and b_c2.get("code") == 200
                and copied2 == 0 and skipped2 == 3)
    ok_all = fill_ok and copy1_ok and copy2_ok
    return out, ok_all, "" if ok_all else (
        f"fill={fill_ok} copy1={copy1_ok}(copied={copied1},skipped={skipped1}) "
        f"copy2={copy2_ok}(copied={copied2},skipped={skipped2})")


# ============================================================
#  Case 05：★算法闭合（核心）
# ============================================================

def case_05_algorithm_closure(sess_admin, sess_res) -> Tuple[Dict[str, Any], bool]:
    """3 人月薪 10000/8000/6000、工时 100/80/60，B=30000 → calc → DB 直查断言。"""
    out: Dict[str, Any] = {}
    p_own = STATE["p_own_id"]
    calc_month = "2026-03"
    budget_B = 30000
    salaries = {RES_USER_ID: 10000, DL_USER_ID: 8000, LHR_USER_ID: 6000}
    hours = {RES_USER_ID: 100, DL_USER_ID: 80, LHR_USER_ID: 60}
    # 5.0 准备：预算 → 30000（简报经典场景；case_01 曾设 2026-03=10000，此 PUT 增量改到 30000，
    #            与 budget_B=30000 断言自洽；calc 前改预算合法 — 该月无 CONFIRMED 批次）
    code_bud, b_bud = rd_budget_save(sess_admin, {
        "projectId": p_own, "year": 2026, "months": [{"month": 3, "totalAmount": budget_B}]})
    if not (code_bud == 200 and isinstance(b_bud, dict) and b_bud.get("code") == 200):
        return {"save_budget": {"code": code_bud, "body": b_bud}}, False, "case05 预算改 30000 失败"
    # 5.1 准备：工资（3 人）+ 工时（3 人） — admin 代填 3 人工时到 p_own
    # 先清掉 case_03 残留的 res 工时（5d 中 1 软删；4 行剩 03-06..03-09；与 case_05 要填 03-01..03-10 冲突）
    # UNIQUE INDEX 在 (project_id, researcher_id, work_date) 上，软删(del_flag='2')也算冲突，
    # 故物理 DELETE 全行（仅本脚本本任务专用；不影响业务代码）。
    db_execute("DELETE FROM RUOYI.RD_WORKTIME_DAILY WHERE PROJECT_ID = ? AND RESEARCHER_ID = ?",
               [p_own, RES_USER_ID])
    db_execute("DELETE FROM RUOYI.RD_WORKTIME_MONTHLY WHERE PROJECT_ID = ? AND RESEARCHER_ID = ?",
               [p_own, RES_USER_ID])
    for uid, sal in salaries.items():
        code_s, b_s = rd_salary_save(sess_admin, {
            "researcherId": uid, "salaryMonth": calc_month, "monthlySalary": sal})
        if not (code_s == 200 and isinstance(b_s, dict) and b_s.get("code") == 200):
            return {"save_salary": {"uid": uid, "code": code_s, "body": b_s}}, False, f"save 工资 uid={uid} 失败"
    # 工时：admin 代填 DL / LHR；res 走本人 save
    # DL: 03-15..03-22 共 8 天 × 10h = 80
    dl_days = [{"workDate": f"{calc_month}-{d:02d}", "rdHours": 10} for d in range(15, 23)]
    code_dl, b_dl = rd_worktime_save(sess_admin, {
        "projectId": p_own, "researcherId": DL_USER_ID, "month": calc_month, "days": dl_days})
    if not (code_dl == 200 and isinstance(b_dl, dict) and b_dl.get("code") == 200):
        return {"save_dl": {"code": code_dl, "body": b_dl}}, False, "DL 工时 save 失败"
    # LHR: 03-23..03-28 共 6 天 × 10h = 60
    lhr_days = [{"workDate": f"{calc_month}-{d:02d}", "rdHours": 10} for d in range(23, 29)]
    code_lhr, b_lhr = rd_worktime_save(sess_admin, {
        "projectId": p_own, "researcherId": LHR_USER_ID, "month": calc_month, "days": lhr_days})
    if not (code_lhr == 200 and isinstance(b_lhr, dict) and b_lhr.get("code") == 200):
        return {"save_lhr": {"code": code_lhr, "body": b_lhr}}, False, "LHR 工时 save 失败"
    # res: 03-01..03-10 共 10 天 × 10h = 100
    res_days = [{"workDate": f"{calc_month}-{d:02d}", "rdHours": 10} for d in range(1, 11)]
    code_res, b_res = rd_worktime_save(sess_res, {
        "projectId": p_own, "researcherId": RES_USER_ID, "month": calc_month, "days": res_days})
    if not (code_res == 200 and isinstance(b_res, dict) and b_res.get("code") == 200):
        return {"save_res": {"code": code_res, "body": b_res}}, False, "RES 工时 save 失败"
    # 5.1 calc
    calc_body = {"projectId": p_own, "month": calc_month}
    code_c, b_c = rd_alloc_calc(sess_admin, calc_body)
    out["calc"] = {"code": code_c, "body": b_c}
    calc_ok = code_c == 200 and isinstance(b_c, dict) and b_c.get("code") == 200
    if not calc_ok:
        return out, False, "calc 失败"
    # 5.2 DB 直查断言
    ok_db, rows_db = db_allocations(p_own, calc_month)
    if not ok_db or not rows_db:
        return {"db": rows_db}, False, "DB 无分配行"
    out["db_rows"] = [
        {"alloc_id": r[0], "researcher_id": r[1], "alloc": r[2], "surcharge": r[3],
         "grand": r[4], "hours": r[5], "hourly_rate": r[6], "status": r[8], "confirm_by": r[9]}
        for r in rows_db
    ]
    # (a) 行数 3
    row_cnt_ok = len(rows_db) == 3
    # (b) Σallocated_amount == 30000 精确
    sum_alloc = sum(float(r[2] or 0) for r in rows_db)
    sum_alloc_ok = abs(sum_alloc - budget_B) < 0.005
    # (c) surcharge_detail JSON 逐 rate_code 求和 == round2(B × rate_k) — 全部 10 项
    json_roundtrip_ok = True
    surcharge_json_close_ok = True
    try:
        detail_jsons = [r[7] for r in rows_db]
        # 逐 rate_code 求和（DB → Python）
        sums_per_code: Dict[str, float] = {}
        for dj in detail_jsons:
            if not dj:
                continue
            obj = json.loads(dj)
            for code, val in obj.items():
                sums_per_code[code] = sums_per_code.get(code, 0.0) + float(val)
        # 期望：round2(B × rate_k) — 10 项
        for code, rate in SURCHARGE_RATES:
            expected = round(budget_B * rate, 2)
            actual = round(sums_per_code.get(code, 0.0), 2)
            if abs(actual - expected) > 0.005:
                surcharge_json_close_ok = False
        out["surcharge_sums"] = sums_per_code
        out["surcharge_expected"] = {c: round(budget_B * r, 2) for c, r in SURCHARGE_RATES}
    except Exception as e:
        json_roundtrip_ok = False
        out["surcharge_err"] = repr(e)
    # (d) Σgrand == Σalloc + Σsurcharge
    sum_grand = sum(float(r[4] or 0) for r in rows_db)
    sum_surcharge = sum(float(r[3] or 0) for r in rows_db)
    sum_grand_close = abs(sum_grand - (sum_alloc + sum_surcharge)) < 0.005
    # (e) hourly_rate == round2(月薪 / 174)
    hourly_rate_ok = True
    for r in rows_db:
        uid = int(r[1])
        expected_hr = round(salaries[uid] / 174.0, 2)
        actual_hr = round(float(r[6] or 0), 2)
        if abs(actual_hr - expected_hr) > 0.005:
            hourly_rate_ok = False
    out["sum_alloc"] = sum_alloc
    out["sum_surcharge"] = sum_surcharge
    out["sum_grand"] = sum_grand
    out["hourly_rate_ok"] = hourly_rate_ok
    ok_all = (calc_ok and row_cnt_ok and sum_alloc_ok
              and json_roundtrip_ok and surcharge_json_close_ok
              and sum_grand_close and hourly_rate_ok)
    return out, ok_all, "" if ok_all else (
        f"calc={calc_ok} row_cnt={row_cnt_ok} sum_alloc={sum_alloc_ok} "
        f"json_rt={json_roundtrip_ok} json_close={surcharge_json_close_ok} "
        f"grand_close={sum_grand_close} hourly_rate={hourly_rate_ok}")


# ============================================================
#  Case 06：状态机
# ============================================================

def case_06_state_machine(sess_admin, sess_res, sess_sci) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own_id"]
    sm_month = "2026-03"
    # 6.1 再 calc（DRAFT 重建 — 旧行 del_flag='2'）
    # 注意：calc 时会软删旧 DRAFT 行；这里 case05 已经写了 3 行 DRAFT；case06 再 calc 一次
    # 旧 DRAFT 应被软删（del_flag='2'），新 DRAFT 插入（del_flag='0'）
    code_c, b_c = rd_alloc_calc(sess_admin, {"projectId": p_own, "month": sm_month})
    out["calc_rebuild"] = {"code": code_c, "body": b_c}
    calc_ok = code_c == 200 and isinstance(b_c, dict) and b_c.get("code") == 200
    # DB：原 DRAFT 行应 del_flag='2'（按 case05 的旧 alloc_id 软删）— 这里重新按 alloc_id 全表查
    ok_db_all, rows_all = db_query(
        "SELECT ALLOC_ID, RESEARCHER_ID, STATUS, DEL_FLAG FROM RUOYI.RD_LABOR_ALLOCATION "
        "WHERE PROJECT_ID = ? AND MONTH = ? ORDER BY ALLOC_ID ASC", [p_own, sm_month])
    out["db_after_calc"] = rows_all
    # 旧 DRAFT 应有 del_flag='2'
    old_drafts = [r for r in (rows_all["rows"] if ok_db_all else []) if r[2] == "DRAFT" and r[3] == "2"]
    new_drafts = [r for r in (rows_all["rows"] if ok_db_all else []) if r[2] == "DRAFT" and r[3] == "0"]
    # 6.2 confirm → 全部 DRAFT → CONFIRMED + confirm_by/time 非空
    code_cf, b_cf = rd_alloc_confirm(sess_admin, {"projectId": p_own, "month": sm_month})
    out["confirm"] = {"code": code_cf, "body": b_cf}
    confirm_ok = code_cf == 200 and isinstance(b_cf, dict) and b_cf.get("code") == 200
    # DB：所有 del_flag='0' 的行 status='CONFIRMED' 且 confirm_by 非空、confirm_time 非空
    ok_db_c, rows_c = db_query(
        "SELECT STATUS, CONFIRM_BY, CONFIRM_TIME, DEL_FLAG FROM RUOYI.RD_LABOR_ALLOCATION "
        "WHERE PROJECT_ID = ? AND MONTH = ? AND DEL_FLAG = '0'", [p_own, sm_month])
    all_confirmed = False
    confirm_by_ok = False
    confirm_time_ok = False
    if ok_db_c and rows_c["rows"]:
        all_confirmed = all(r[0] == "CONFIRMED" for r in rows_c["rows"])
        confirm_by_ok = all((r[1] is not None and str(r[1]).strip() != "") for r in rows_c["rows"])
        confirm_time_ok = all(r[2] is not None for r in rows_c["rows"])
    out["confirm_db"] = {"rows": rows_c["rows"] if ok_db_c else None,
                         "all_confirmed": all_confirmed,
                         "confirm_by_ok": confirm_by_ok,
                         "confirm_time_ok": confirm_time_ok}
    # 6.3 再 calc 拒（已 CONFIRMED）
    code_rec, b_rec = rd_alloc_calc(sess_admin, {"projectId": p_own, "month": sm_month})
    rec_msg = str(b_rec.get("msg") or "") if isinstance(b_rec, dict) else ""
    rec_reject = (code_rec == 200 and isinstance(b_rec, dict) and b_rec.get("code") != 200
                  and ("撤销" in rec_msg or "CONFIRMED" in rec_msg or "已确认" in rec_msg))
    out["calc_after_confirm_reject"] = {"code": code_rec, "body": b_rec, "msg": rec_msg, "rejected": rec_reject}
    # 6.4 预算改该月拒（sm_month=03 已有 CONFIRMED 分配行）
    code_b, b_b = rd_budget_save(sess_sci, {"projectId": p_own, "year": 2026,
                                            "months": [{"month": 3, "totalAmount": 99999}]})
    bm_msg = str(b_b.get("msg") or "") if isinstance(b_b, dict) else ""
    bm_reject = (code_b == 200 and isinstance(b_b, dict) and b_b.get("code") != 200
                 and ("已确认" in bm_msg or "不可修改" in bm_msg or "CONFIRMED" in bm_msg))
    out["budget_after_confirm_reject"] = {"code": code_b, "body": b_b, "msg": bm_msg, "rejected": bm_reject}
    # 6.5 revoke 无 reason 拒
    code_rn, b_rn = rd_alloc_revoke(sess_admin, {"projectId": p_own, "month": sm_month, "reason": ""})
    rn_msg = str(b_rn.get("msg") or "") if isinstance(b_rn, dict) else ""
    rn_reject = (code_rn == 200 and isinstance(b_rn, dict) and b_rn.get("code") != 200
                 and ("reason" in rn_msg or "必填" in rn_msg or "撤销" in rn_msg))
    out["revoke_no_reason_reject"] = {"code": code_rn, "body": b_rn, "msg": rn_msg, "rejected": rn_reject}
    # 6.6 revoke 带 reason → 回 DRAFT + confirm_by 清空 + remark 含 "[撤销确认"
    rev_body = {"projectId": p_own, "month": sm_month, "reason": "smoke 撤销确认"}
    code_rv, b_rv = rd_alloc_revoke(sess_admin, rev_body)
    revoke_ok = code_rv == 200 and isinstance(b_rv, dict) and b_rv.get("code") == 200
    out["revoke_ok"] = revoke_ok
    # DB 验证
    ok_db_r, rows_r = db_query(
        "SELECT STATUS, CONFIRM_BY, REMARK FROM RUOYI.RD_LABOR_ALLOCATION "
        "WHERE PROJECT_ID = ? AND MONTH = ? AND DEL_FLAG = '0'", [p_own, sm_month])
    draft_back = False
    confirm_cleared = False
    remark_prefix_ok = False
    if ok_db_r and rows_r["rows"]:
        draft_back = all(r[0] == "DRAFT" for r in rows_r["rows"])
        confirm_cleared = all(r[1] is None for r in rows_r["rows"])
        remark_prefix_ok = any(r[2] is not None and "[撤销确认" in str(r[2]) for r in rows_r["rows"])
    out["revoke_db"] = {"draft_back": draft_back, "confirm_cleared": confirm_cleared,
                        "remark_prefix_ok": remark_prefix_ok,
                        "sample_remark": rows_r["rows"][0][2] if ok_db_r and rows_r["rows"] else None}
    # 6.7 再 calc 成功
    code_again, b_again = rd_alloc_calc(sess_admin, {"projectId": p_own, "month": sm_month})
    calc_again_ok = code_again == 200 and isinstance(b_again, dict) and b_again.get("code") == 200
    out["calc_after_revoke_ok"] = calc_again_ok
    ok_all = (calc_ok and confirm_ok and all_confirmed and confirm_by_ok and confirm_time_ok
              and rec_reject and bm_reject and rn_reject and revoke_ok
              and draft_back and confirm_cleared and remark_prefix_ok and calc_again_ok)
    return out, ok_all, "" if ok_all else (
        f"calc={calc_ok} confirm={confirm_ok} all_conf={all_confirmed} "
        f"by={confirm_by_ok} time={confirm_time_ok} rec_rej={rec_reject} bm_rej={bm_reject} "
        f"rn_rej={rn_reject} revoke={revoke_ok} draft_back={draft_back} "
        f"confirm_cleared={confirm_cleared} remark_pfx={remark_prefix_ok} calc_again={calc_again_ok}")


# ============================================================
#  Case 07：边界（无预算月 calc 拒；无工时月 calc 返回 msg="无有效工时" 且 rows 空；缺薪资 calc 拒且报文列人员）
# ============================================================

def case_07_boundary(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own_id"]
    # 7.1 无预算月 calc 拒（2026-02 无预算）
    code1, b1 = rd_alloc_calc(sess_admin, {"projectId": p_own, "month": "2026-02"})
    no_budget_msg = str(b1.get("msg") or "") if isinstance(b1, dict) else ""
    no_budget_reject = (code1 == 200 and isinstance(b1, dict) and b1.get("code") != 200
                        and ("未编制" in no_budget_msg or "预算" in no_budget_msg))
    out["no_budget_reject"] = {"code": code1, "body": b1, "msg": no_budget_msg, "rejected": no_budget_reject}
    # 7.2 无工时月 calc → 返回 msg="无有效工时" 且 rows 空
    # 选 2026-06（无工时）— 需有预算
    code_b, _ = rd_budget_save(sess_admin, {"projectId": p_own, "year": 2026,
                                            "months": [{"month": 6, "totalAmount": 5000}]})
    out["budget_for_06"] = {"code": code_b}
    code2, b2 = rd_alloc_calc(sess_admin, {"projectId": p_own, "month": "2026-06"})
    data2 = get_data(b2) if isinstance(b2, dict) else None
    rows2 = data2.get("rows") if isinstance(data2, dict) else None
    msg2 = (data2.get("msg") if isinstance(data2, dict) else "") or (b2.get("msg") if isinstance(b2, dict) else "")
    no_worktime_ok = (code2 == 200 and isinstance(b2, dict) and b2.get("code") == 200
                      and rows2 == [] and "无有效工时" in str(msg2))
    out["no_worktime_ok"] = {"code": code2, "body": b2, "rows_empty": rows2 == [], "msg": msg2, "ok": no_worktime_ok}
    # 7.3 缺薪资 calc 拒且报文列人员
    # 选 2026-08（不在 03/05/07 已编预算月；先编预算）— 仅有工时但无工资
    code_b2, _ = rd_budget_save(sess_admin, {"projectId": p_own, "year": 2026,
                                             "months": [{"month": 8, "totalAmount": 8000}]})
    out["budget_for_08"] = {"code": code_b2}
    # 工时：admin 代填 1 人（DL） → 03-month 填 DL（但 03 已经 calc 过；08 重新填）
    code_w, _ = rd_worktime_save(sess_admin, {
        "projectId": p_own, "researcherId": DL_USER_ID, "month": "2026-08",
        "days": [{"workDate": "2026-08-15", "rdHours": 8}]})
    out["worktime_for_08"] = {"code": code_w}
    # 清掉 DL 08 工资（先删）
    db_execute("DELETE FROM RUOYI.RD_RESEARCHER_SALARY WHERE RESEARCHER_ID = ? AND SALARY_MONTH = '2026-08'",
               [DL_USER_ID])
    code3, b3 = rd_alloc_calc(sess_admin, {"projectId": p_own, "month": "2026-08"})
    miss_msg = str(b3.get("msg") or "") if isinstance(b3, dict) else ""
    miss_reject = (code3 == 200 and isinstance(b3, dict) and b3.get("code") != 200
                   and ("未维护工资标准" in miss_msg or "工资" in miss_msg)
                   and str(DL_USER_ID) in miss_msg)
    out["miss_salary_reject"] = {"code": code3, "body": b3, "msg": miss_msg, "rejected": miss_reject}
    ok_all = no_budget_reject and no_worktime_ok and miss_reject
    return out, ok_all, "" if ok_all else (
        f"no_budget={no_budget_reject} no_worktime={no_worktime_ok} miss_salary={miss_reject}")


# ============================================================
#  Case 08：数据权限（researcher / dept_leader / office 三通道）
# ============================================================

def case_08_data_permission(sess_res, sess_dl, sess_sci) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own_id"]
    p_other = STATE["p_other_id"]
    p_dept = STATE["p_dept_id"]
    month = "2026-03"
    # 8.1 researcher list / dashboard 本人参与课题（P_OWN）可看；他室课题（P_OTHER）不可看
    code_l, b_l = rd_alloc_list(sess_res, p_own, month)
    res_rows = tdi_rows(b_l) or []
    res_sees_own = (code_l == 200 and isinstance(b_l, dict) and b_l.get("code") == 200 and len(res_rows) >= 1)
    # 同行 hourlyRate 自己非空，他人 null
    res_self_row = next((r for r in res_rows if int(r.get("researcherId") or -1) == RES_USER_ID), None)
    res_other_row = next((r for r in res_rows if int(r.get("researcherId") or -1) != RES_USER_ID), None)
    res_self_hourly_non_null = (res_self_row is not None and res_self_row.get("hourlyRate") is not None)
    res_other_hourly_null = (res_other_row is None or res_other_row.get("hourlyRate") is None)
    out["res_list_own"] = {"sees": res_sees_own, "self_hourly_ok": res_self_hourly_non_null,
                           "other_hourly_null": res_other_hourly_null, "rows_count": len(res_rows)}
    # 他室 dashboard 拒
    code_d, b_d = rd_alloc_dashboard(sess_res, p_other, month)
    other_msg = str(b_d.get("msg") or "") if isinstance(b_d, dict) else ""
    other_reject = is_business_reject(b_d) and (
        "无权" in other_msg or "权限" in other_msg or "其他" in other_msg)
    out["res_dashboard_other_reject"] = {"code": code_d, "body": b_d, "msg": other_msg, "rejected": other_reject}
    # 8.2 dept_leader 本室（P_DEPT_DL）可看；跨室（P_OTHER 100）拒
    code_dl, b_dl = rd_alloc_list(sess_dl, p_dept, month)
    dl_rows = tdi_rows(b_dl) or []
    # 注：P_DEPT_DL 还未做 calc，这里 list 应为空（rows=[]）
    dl_sees_dept = (code_dl == 200 and isinstance(b_dl, dict) and b_dl.get("code") == 200)
    # 跨室 list（无 calc 行 + 数据权限过不去 — Service scoped 闸门拒"无权访问"）
    code_dl2, b_dl2 = rd_alloc_list(sess_dl, p_other, month)
    cross_msg = str(b_dl2.get("msg") or "") if isinstance(b_dl2, dict) else ""
    cross_reject = is_business_reject(b_dl2) and (
        "无权" in cross_msg or "权限" in cross_msg)
    out["dl_list_dept"] = {"sees": dl_sees_dept, "rows_count": len(dl_rows)}
    out["dl_list_other_reject"] = {"code": code_dl2, "body": b_dl2, "msg": cross_msg, "rejected": cross_reject}
    # 8.3 office — task brief 未建 office 角色；用 sci_admin 验全所（office data_scope=1 全所语义相同）
    code_sci, b_sci = rd_alloc_list(sess_sci, p_own, month)
    sci_rows = tdi_rows(b_sci) or []
    sci_sees = (code_sci == 200 and isinstance(b_sci, dict) and b_sci.get("code") == 200 and len(sci_rows) >= 1)
    out["sci_list_own"] = {"sees": sci_sees, "rows_count": len(sci_rows)}
    ok_all = (res_sees_own and res_self_hourly_non_null and res_other_hourly_null
              and other_reject and dl_sees_dept and cross_reject and sci_sees)
    return out, ok_all, "" if ok_all else (
        f"res_sees={res_sees_own} res_self_hr={res_self_hourly_non_null} "
        f"res_other_hr_null={res_other_hourly_null} res_other_rej={other_reject} "
        f"dl_sees={dl_sees_dept} dl_cross_rej={cross_reject} sci_sees={sci_sees}")


# ============================================================
#  Case 09：三张导出
# ============================================================

def case_09_exports(sess_admin, sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own_id"]
    month = "2026-03"
    # 9.1 /export/worktime → 200 + Content-Type 为 Excel
    code1, content1, h1 = rd_export_worktime(sess_admin, {"projectId": p_own, "month": month})
    out["worktime"] = {"status": code1, "headers": h1, "content_len": len(content1)}
    worktime_ok = code1 == 200 and "spreadsheetml" in h1.get("content_type", "")
    # 9.2 /export/allocation → 200 + Excel
    code2, content2, h2 = rd_export_allocation(sess_admin, {"projectId": p_own, "month": month})
    out["allocation"] = {"status": code2, "headers": h2, "content_len": len(content2)}
    allocation_ok = code2 == 200 and "spreadsheetml" in h2.get("content_type", "")
    # 9.3 openpyxl 解析 allocation Excel — 10 项附加费列值与 DB surcharge_detail 一致、合计行 == Σ列
    alloc_parse_ok = False
    alloc_total_ok = False
    if allocation_ok and openpyxl is not None:
        try:
            import io as _io
            wb = openpyxl.load_workbook(_io.BytesIO(content2), data_only=True)
            ws = wb.active
            # 表头：研发人员 / 月工时 / 时薪 / 分摊人工费 / 10 项附加费 / 附加费合计 / 总计 = 16 列
            headers = [c.value for c in ws[1]]
            # 找到 10 项附加费列的索引（headers 第 5..14，0-indexed 4..13）
            rate_col_indices = []
            for i, h in enumerate(headers):
                if h is None:
                    continue
                for code, _ in SURCHARGE_RATES:
                    # 表头是 rate_name（中文）；按 rate_code → rate_name 映射
                    pass
            # 用 DB 验证：拉所有 allocation 行的实际活跃数（del_flag != '2'）
            ok_db, db_rows = db_allocations(p_own, month)
            # SELECT 索引：0 alloc_id, 1 researcher_id, 2 alloc, 3 surcharge, 4 grand,
            #               5 monthly_hours, 6 hourly_rate, 7 surcharge_detail,
            #               8 status, 9 confirm_by, 10 del_flag
            db_rows_visible = [r for r in (db_rows if ok_db else []) if r[10] != "2"]
            db_rows_visible.sort(key=lambda r: r[1])
            # 解析每行 JSON
            db_per_researcher: Dict[int, Dict[str, float]] = {}
            for r in db_rows_visible:
                rid = int(r[1])
                if not r[7]:
                    continue
                obj = json.loads(r[7])
                db_per_researcher[rid] = {k: float(v) for k, v in obj.items()}
            # 校验 Excel 数据行
            n_data_rows = len(db_rows_visible)
            excel_data_match = True
            # rate_name → rate_code 映射（V1.0.1 dict_data.sql:172-180）
            name_to_code = {
                "职工教育经费": "edu", "工会经费": "union", "基本医疗保险费": "med",
                "补充医疗保险费": "med_sup", "基本养老保险费": "pension", "企业年金": "annuity",
                "失业保险费": "unemploy", "工伤保险费": "injury", "住房公积金": "housing_fund",
                "帮扶救助": "relief",
            }
            for i, db_row in enumerate(db_rows_visible):
                excel_row = ws[i + 2]  # 行 1 是表头
                rid = int(db_row[1])
                db_codes = db_per_researcher.get(rid, {})
                for col_off, (code, _) in enumerate(SURCHARGE_RATES):
                    excel_val = excel_row[4 + col_off].value  # 4 = 起始附加费列
                    db_val = db_codes.get(code, 0.0)
                    if excel_val is None or abs(round(float(excel_val), 2) - round(db_val, 2)) > 0.005:
                        excel_data_match = False
            alloc_parse_ok = excel_data_match
            # 合计行：最后一行（行 = n_data_rows + 2）— 列 0 文本 "合计"
            total_row_idx = n_data_rows + 2
            total_row = ws[total_row_idx]
            total_label_ok = total_row[0].value == "合计"
            # 10 项附加费列合计应 == DB 求和
            col_sum_match = True
            for col_off, (code, _) in enumerate(SURCHARGE_RATES):
                expected = sum(db_per_researcher.get(int(r[1]), {}).get(code, 0.0)
                               for r in db_rows_visible)
                actual = total_row[4 + col_off].value
                if actual is None or abs(round(float(actual), 2) - round(expected, 2)) > 0.005:
                    col_sum_match = False
            alloc_total_ok = total_label_ok and col_sum_match
            out["alloc_parse"] = {
                "headers_count": len(headers), "headers": headers,
                "data_rows": n_data_rows, "data_match": excel_data_match,
                "total_row_label_ok": total_label_ok, "col_sum_match": col_sum_match,
            }
        except Exception as e:
            out["alloc_parse_err"] = repr(e)
            alloc_parse_ok = False
    elif allocation_ok and openpyxl is None:
        out["alloc_parse_note"] = "openpyxl not installed; skip parse"
        alloc_parse_ok = False  # 任务卡要求 openpyxl 解析
    # 9.4 /export/summary → 200 + Excel
    code3, content3, h3 = rd_export_summary(sess_admin, {"year": 2026, "projectIds": [p_own]})
    out["summary"] = {"status": code3, "headers": h3, "content_len": len(content3)}
    summary_ok = code3 == 200 and "spreadsheetml" in h3.get("content_type", "")
    # 9.5 researcher 调 summary 403（perms biz:rd:alloc:summary 不在 researcher 角色）
    code4, content4, h4 = rd_export_summary(sess_res, {"year": 2026, "projectIds": [p_own]})
    res_sum_reject = (code4 == 200 and not ("spreadsheetml" in h4.get("content_type", "")))
    out["summary_res_reject"] = {"status": code4, "headers": h4,
                                 "content_len": len(content4),
                                 "rejected": res_sum_reject}
    ok_all = worktime_ok and allocation_ok and alloc_parse_ok and alloc_total_ok and summary_ok and res_sum_reject
    return out, ok_all, "" if ok_all else (
        f"worktime={worktime_ok} allocation={allocation_ok} parse={alloc_parse_ok} "
        f"total={alloc_total_ok} summary={summary_ok} res_sum_rej={res_sum_reject}")


# ============================================================
#  Case 10：回归 — smoke_honor.main()
# ============================================================

def case_10_regression() -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # smoke_honor 内部 case 09 委托 smoke_project.main()；smoke_project 在模块级 fatal-exit
    # 当 DM_PASSWORD 未设置。本任务简报 §六回归要求 17/17，因此强制注入口令（仅本子进程）。
    os.environ.setdefault("DM_PASSWORD", "Ruoyi12345")
    try:
        import smoke_honor as SH  # noqa: E402
        ret = SH.main()
        out["smoke_honor_rc"] = ret
        ok = ret == 0
    except SystemExit as e:
        out["smoke_honor_rc"] = e.code
        ok = e.code == 0
    except Exception as e:  # noqa: BLE001
        out["smoke_honor_rc"] = repr(e)
        ok = False
    return out, ok, "" if ok else f"smoke_honor rc={out.get('smoke_honor_rc')}"


# ============================================================
#  main
# ============================================================

def dump_results() -> None:
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        for r in RESULTS:
            f.write(json.dumps(r, ensure_ascii=False, default=str) + "\n")


def make_session(ua: str) -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": ua})
    return s


def main() -> int:
    print(f"[boot] base url = {BASE_URL}")
    if not wait_ready(90):
        record("00_backend_ready", False, {}, {"elapsed": 90}, "后端 90 秒内未就绪")
        dump_results()
        return 1
    print("[boot] backend ready")

    # 清残留（幂等）
    cleanup_test_data()

    # 主会话：admin
    sess = make_session("task8-rd-smoke/1.0")
    token, login_resp = login(sess, ADMIN_USER, ADMIN_PASS)
    if not token:
        record("10_login_admin", False, {"username": ADMIN_USER}, login_resp, "admin 登录失败")
        dump_results()
        return 1
    sess.headers.update({"Authorization": "Bearer " + token})
    record("10_login_admin", True, {"username": ADMIN_USER},
           {"status_code": login_resp.get("status_code")})

    # 测试用户
    ok, msg = setup_test_users()
    record("05_setup_test_users", ok, {"target": "sys_user+sys_user_role"}, {"msg": msg}, msg)
    if not ok:
        dump_results()
        return 1

    # 各角色 session
    def _login_user(uname: str) -> Tuple[Optional[requests.Session], Dict[str, Any]]:
        s = make_session("task8-rd-smoke/1.0")
        t, resp = login(s, uname, ADMIN_PASS)
        if t:
            s.headers.update({"Authorization": "Bearer " + t})
        return (s if t else None), resp

    sess_res, resp_res = _login_user(RES_USERNAME)
    record("11_login_researcher", sess_res is not None,
           {"username": RES_USERNAME}, {"status_code": resp_res.get("status_code")},
           "" if sess_res else str(resp_res)[:300])
    sess_lhr, resp_lhr = _login_user(LHR_USERNAME)
    record("12_login_labor_hr", sess_lhr is not None,
           {"username": LHR_USERNAME}, {"status_code": resp_lhr.get("status_code")},
           "" if sess_lhr else str(resp_lhr)[:300])
    sess_dl, resp_dl = _login_user(DL_USERNAME)
    record("13_login_dept_leader", sess_dl is not None,
           {"username": DL_USERNAME}, {"status_code": resp_dl.get("status_code")},
           "" if sess_dl else str(resp_dl)[:300])
    sess_sci, resp_sci = _login_user(SCI_USERNAME)
    record("14_login_sci_admin", sess_sci is not None,
           {"username": SCI_USERNAME}, {"status_code": resp_sci.get("status_code")},
           "" if sess_sci else str(resp_sci)[:300])
    sess_res2, resp_res2 = _login_user(RES2_USERNAME)
    record("15_login_researcher2", sess_res2 is not None,
           {"username": RES2_USERNAME}, {"status_code": resp_res2.get("status_code")},
           "" if sess_res2 else str(resp_res2)[:300])
    if not all([sess_res, sess_lhr, sess_dl, sess_sci, sess_res2]):
        cleanup_test_data()
        dump_results()
        return 1

    cases = [
        ("00_prepare_data",      lambda: case_00_prepare(sess, sess_res)),
        ("01_budget",            lambda: case_01_budget(sess_sci, sess_res)),
        ("02_salary",            lambda: case_02_salary(sess, sess_res, sess_lhr)),
        ("03_worktime",          lambda: case_03_worktime(sess_res, sess, sess_res2)),
        ("04_copy_last_month",   lambda: case_04_copy_last_month(sess_res)),
        ("05_algorithm_closure", lambda: case_05_algorithm_closure(sess, sess_res)),
        ("06_state_machine",     lambda: case_06_state_machine(sess, sess_res, sess_sci)),
        ("07_boundary",          lambda: case_07_boundary(sess)),
        ("08_data_permission",   lambda: case_08_data_permission(sess_res, sess_dl, sess_sci)),
        ("09_exports",           lambda: case_09_exports(sess, sess_res)),
        ("10_regression_honor",  case_10_regression),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/rd/*"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # 收尾
    cleanup_ok, cleanup_msg = cleanup_test_data()
    record("99_cleanup", cleanup_ok, {"method": "db"}, {"msg": cleanup_msg})

    # DB 零残留复查
    ok_zero, res_zero = db_query(
        "SELECT COUNT(*) FROM RUOYI.RD_LABOR_ALLOCATION WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    alloc_left = res_zero["rows"][0][0] if ok_zero and res_zero["rows"] else None
    ok_zero2, res_zero2 = db_query(
        "SELECT COUNT(*) FROM RUOYI.RD_WORKTIME_DAILY WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    wt_left = res_zero2["rows"][0][0] if ok_zero2 and res_zero2["rows"] else None
    ok_zero3, res_zero3 = db_query(
        "SELECT COUNT(*) FROM RUOYI.RD_LABOR_BUDGET WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    bd_left = res_zero3["rows"][0][0] if ok_zero3 and res_zero3["rows"] else None
    ok_zero4, res_zero4 = db_query(
        "SELECT COUNT(*) FROM RUOYI.RD_RESEARCHER_SALARY WHERE RESEARCHER_ID IN (?, ?, ?, ?, ?)",
        [RES_USER_ID, LHR_USER_ID, DL_USER_ID, SCI_USER_ID, RES2_USER_ID])
    sal_left = res_zero4["rows"][0][0] if ok_zero4 and res_zero4["rows"] else None
    ok_zero5, res_zero5 = db_query(
        "SELECT COUNT(*) FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    proj_left = res_zero5["rows"][0][0] if ok_zero5 and res_zero5["rows"] else None
    ok_zero6, res_zero6 = db_query(
        "SELECT COUNT(*) FROM RUOYI.SYS_USER WHERE REMARK = ?", [TEST_MARK])
    user_left = res_zero6["rows"][0][0] if ok_zero6 and res_zero6["rows"] else None
    zero_ok = (alloc_left == 0 and wt_left == 0 and bd_left == 0
               and sal_left == 0 and proj_left == 0 and user_left == 0)
    record("98_db_zero_leftover", zero_ok, {"method": "db"},
           {"alloc_left": alloc_left, "worktime_left": wt_left, "budget_left": bd_left,
            "salary_left": sal_left, "project_left": proj_left, "user_left": user_left})

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())