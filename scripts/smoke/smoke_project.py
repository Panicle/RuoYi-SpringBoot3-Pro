#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 3 — 阶段2 课题管理接口冒烟 + researcher 数据权限端到端实测
- 覆盖：课题 CRUD / project_no 生成 / 状态机合法+非法迁移 / archive / 成员管理(唯一HOST) / changeHost /
        Excel 导出 / researcher(data_scope=5) 列表过滤 + 详情拦截 + 写接口 403（阶段1 挂账硬验收）
- 复用 smoke_user_profile.py 的登录/断言/记录框架
- 所有结果写 scripts/smoke/result.jsonl（同名文件会覆盖，勿并发跑）
- 只测不改业务代码；发现的 Bug 记入报告
"""
from __future__ import annotations

import base64
import io
import json
import os
import re
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

import requests
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5

try:
    import dmPython  # 达梦
except Exception:  # pragma: no cover
    dmPython = None  # 标记不可用，DB 用例将明确报缺失

BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8087")
ADMIN_USER = "admin"
ADMIN_PASS = "admin123"

# 达梦连接（口令走环境变量 DM_PASSWORD，不落明文；运行前 export DM_PASSWORD=xxx）
DM_PASSWORD = os.environ.get("DM_PASSWORD")
if not DM_PASSWORD:
    sys.stderr.write("[fatal] 缺少环境变量 DM_PASSWORD（达梦 SYSDBA 口令）\n")
    sys.exit(2)
DM_CONN_KW = dict(user="SYSDBA", password=DM_PASSWORD, server="localhost", port=5236)

# 前端 jsencrypt.js 硬编码公钥（与 smoke_user_profile.py 一致）
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
RESULT_PATH = os.path.join(SCRIPT_DIR, "result.jsonl")

# 测试用户（显式 user_id，避免依赖 identity）
SCI_USER_ID = 90010          # science_admin 角色 101，data_scope=1（驱动主用例，避开 admin 误判 researcher 的 bug）
SCI_USERNAME = "test_sci_admin"
RES_USER_ID = 90001          # researcher 角色 105
MEM2_USER_ID = 90002         # 普通成员（不登录）— I-2 中作为 D1 成员
MEM3_USER_ID = 90003         # 普通成员（不登录）— I-2 中作为 D2 成员
RES_USERNAME = "test_researcher"
DL_USER_ID = 90004           # dept_leader 角色 104，data_scope=3（本部门）— I-1/I-2 越权场景
DL_USERNAME = "test_dept_leader"
DEPT_D1 = 101                # test_dept_leader / test_member2 所属部门
DEPT_D2 = 100                # 跨部门（越权目标 B_dl 所属）

TEST_MARK = "smoke-task3"    # 课题 remark 标记，收尾按此清理

RESULTS: List[Dict[str, Any]] = []
STATE = {}  # 跨用例传递状态（projectId 等）


# ====================== 通用工具 ======================

def rsa_encrypt_password(plain: str) -> str:
    key = RSA.import_key(PUBLIC_KEY_PEM)
    cipher = PKCS1_v1_5.new(key)
    ct = cipher.encrypt(plain.encode("utf-8"))
    return base64.b64encode(ct).decode("ascii")


def wait_ready(timeout: int = 60) -> bool:
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
         params: Any = None) -> requests.Response:
    full = url if url.startswith("http://") or url.startswith("https://") else BASE_URL + url
    return session.request(method, full, json=json_body, params=params, timeout=20)


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
    """写操作（自动 commit）。返回 (ok, 影响行数或 err)。"""
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
    if isinstance(body, dict) and body.get("code") == 200:
        return body.get("data")
    return None


def db_project_field(pid: int, col: str) -> Tuple[bool, Any]:
    """DB 直查 project 单列（规避 BUG-A 导致详情 API 500，用 DB 验证数据状态）。"""
    ok, res = db_query(f"SELECT {col} FROM RUOYI.PROJECT WHERE PROJECT_ID = ?", [pid])
    if not ok or not res["rows"]:
        return False, None
    return True, res["rows"][0][0]


# ====================== 测试数据准备（DB 直连） ======================

def setup_test_users() -> Tuple[bool, str]:
    """造 test_researcher(105) / test_member2 / test_member3。password 复用 admin 哈希 → 可用 admin123 登录。"""
    ok, res = db_query("SELECT PASSWORD FROM RUOYI.SYS_USER WHERE USER_NAME='admin'")
    if not ok or not res["rows"]:
        return False, "admin hash 读取失败: " + str(res)
    admin_hash = res["rows"][0][0]
    users = [
        (SCI_USER_ID, SCI_USERNAME, "冒烟科管", 100, 101),
        (RES_USER_ID, RES_USERNAME, "冒烟科研人员", 100, 105),
        (DL_USER_ID, DL_USERNAME, "冒烟室主任", DEPT_D1, 104),
        (MEM2_USER_ID, "test_member2", "冒烟成员2", DEPT_D1, None),
        (MEM3_USER_ID, "test_member3", "冒烟成员3", DEPT_D2, None),
    ]
    for uid, uname, nick, dept, role_id in users:
        ok, r = db_execute(
            "INSERT INTO RUOYI.SYS_USER (USER_ID, DEPT_ID, USER_NAME, NICK_NAME, USER_TYPE, STATUS, "
            "DEL_FLAG, PASSWORD, CREATE_BY, CREATE_TIME, REMARK, TENANT_ID, TRY_COUNT) "
            "VALUES (?, ?, ?, ?, '00', '0', '0', ?, 'admin', SYSDATE, ?, '000000', 0)",
            [uid, dept, uname, nick, admin_hash, TEST_MARK],
        )
        if not ok:
            return False, f"建用户 {uname} 失败: {r}"
        if role_id is not None:
            ok2, r2 = db_execute(
                "INSERT INTO RUOYI.SYS_USER_ROLE (USER_ID, ROLE_ID) VALUES (?, ?)",
                [uid, role_id],
            )
            if not ok2:
                return False, f"绑角色 {uname} 失败: {r2}"
    return True, "ok"


def cleanup_test_data() -> Tuple[bool, str]:
    """收尾：按 TEST_MARK 清理课题/成员/测试用户。"""
    ok, r = db_execute("DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
                       "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清成员失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清课题失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID IN (?, ?, ?, ?, ?)",
                       [SCI_USER_ID, RES_USER_ID, DL_USER_ID, MEM2_USER_ID, MEM3_USER_ID])
    if not ok:
        return False, "清用户角色失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?, ?, ?)",
                       [SCI_USER_ID, RES_USER_ID, DL_USER_ID, MEM2_USER_ID, MEM3_USER_ID])
    if not ok:
        return False, "清用户失败: " + str(r)
    return True, "ok"


# ====================== 用例：课题 CRUD ======================

def add_project(sess, name: str, leader_id: int, project_type: str = "NATIONAL",
                dept_id: int = None, start_date: str = None, budget: str = None,
                project_category: str = "A", specialty: str = "Y") -> Tuple[Dict, bool, Optional[int]]:
    # 适配当前后端 insert 必填：阶段1变更1 projectNo 人工必填；阶段2变更2 projectCategory/specialty 必填
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {"projectName": name, "projectType": project_type, "leaderId": leader_id,
            "projectNo": f"KY-2026-{(STATE['no_seq'] % 900) + 100:03d}",
            "projectCategory": project_category, "specialty": specialty,
            "remark": TEST_MARK}
    if dept_id is not None:
        body["deptId"] = dept_id
    if start_date is not None:
        body["startDate"] = start_date
    if budget is not None:
        body["budgetTotal"] = budget
    r = http(sess, "POST", "/biz/project", json_body=body)
    b = safe_json(r)
    data = get_data(b)
    pid = data.get("projectId") if isinstance(data, dict) else None
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and pid is not None
    return {"request_body": body, "status_code": r.status_code, "body": b,
            "data": data, "raw": r.text[:500]}, ok, pid


def case_11_add_project_a(sess) -> Tuple[Dict[str, Any], bool]:
    """A：leader=test_researcher，供 researcher 数据权限用例。"""
    resp, ok, pid = add_project(sess, "冒烟课题A-科研人员主持", RES_USER_ID,
                                project_type="NATIONAL", start_date="2026-01-01")
    STATE["project_a_id"] = pid
    STATE["project_a_no"] = resp.get("data", {}).get("projectNo") if isinstance(resp.get("data"), dict) else None
    note = ""
    pno = STATE["project_a_no"]
    if ok and pno:
        if not re.fullmatch(r"KY-\d{4}-\d{3}", pno):
            ok, note = False, f"project_no 格式不符: {pno!r}（期望 KY-{{yyyy}}-{{3位流水}}）"
    return resp, ok


def case_12_add_project_b(sess) -> Tuple[Dict[str, Any], bool]:
    """B：leader=用户3（与 researcher 无关），供编辑/删除/归档拒绝用例。"""
    resp, ok, pid = add_project(sess, "冒烟课题B-无关课题", 3, project_type="PROVINCIAL")
    STATE["project_b_id"] = pid
    STATE["project_b_no"] = resp.get("data", {}).get("projectNo") if isinstance(resp.get("data"), dict) else None
    return resp, ok


def case_13_add_project_c(sess) -> Tuple[Dict[str, Any], bool]:
    """C：成员管理专用。"""
    resp, ok, pid = add_project(sess, "冒烟课题C-成员管理", 3, project_type="NATIONAL")
    STATE["project_c_id"] = pid
    return resp, ok


def case_14_add_project_d(sess) -> Tuple[Dict[str, Any], bool]:
    """D：非法迁移(DRAFT->COMPLETED) + 删除成功路径。"""
    resp, ok, pid = add_project(sess, "冒烟课题D-删除验证", 3, project_type="NATIONAL")
    STATE["project_d_id"] = pid
    return resp, ok


def case_15_add_project_e(sess) -> Tuple[Dict[str, Any], bool]:
    """E：状态机 ACCEPTED->ARCHIVED 路径。"""
    resp, ok, pid = add_project(sess, "冒烟课题E-状态机全链", 3, project_type="NATIONAL")
    STATE["project_e_id"] = pid
    return resp, ok


def case_16_add_project_f(sess) -> Tuple[Dict[str, Any], bool]:
    """F：状态机 COMPLETED->ARCHIVED 路径。"""
    resp, ok, pid = add_project(sess, "冒烟课题F-归档COMPLETED", 3, project_type="NATIONAL")
    STATE["project_f_id"] = pid
    return resp, ok


def case_17_project_no_unique(sess) -> Dict[str, Any]:
    nos = [STATE.get("project_a_no"), STATE.get("project_b_no")]
    ok = nos[0] is not None and nos[1] is not None and len(set(nos)) == len(nos)
    return {"project_nos": nos}, ok


def case_18_list_filters(sess) -> Dict[str, Any]:
    """多条件查询逐项验证。"""
    a_id, a_no = STATE["project_a_id"], STATE["project_a_no"]
    checks: Dict[str, Any] = {}

    # 18a projectNo 精确
    r = http(sess, "GET", "/biz/project/list", params={"projectNo": a_no, "pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["projectNo"] = {"total": b.get("total") if isinstance(b, dict) else None,
                           "found_a": any(x.get("projectId") == a_id for x in rows),
                           "status_code": r.status_code}

    # 18b projectName 模糊
    r = http(sess, "GET", "/biz/project/list", params={"projectName": "课题A", "pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["projectName"] = {"total": b.get("total") if isinstance(b, dict) else None,
                             "found_a": any(x.get("projectId") == a_id for x in rows)}

    # 18c projectType 精确
    r = http(sess, "GET", "/biz/project/list", params={"projectType": "NATIONAL", "pageNum": 1, "pageSize": 50})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["projectType"] = {"total": b.get("total") if isinstance(b, dict) else None,
                             "all_national": all(x.get("projectType") == "NATIONAL" for x in rows)}

    # 18d status 精确
    r = http(sess, "GET", "/biz/project/list", params={"status": "DRAFT", "pageNum": 1, "pageSize": 50})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["status"] = {"total": b.get("total") if isinstance(b, dict) else None,
                        "all_draft": all(x.get("status") == "DRAFT" for x in rows)}

    # 18e leaderId 精确
    r = http(sess, "GET", "/biz/project/list", params={"leaderId": RES_USER_ID, "pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["leaderId"] = {"total": b.get("total") if isinstance(b, dict) else None,
                          "found_a": any(x.get("projectId") == a_id for x in rows)}

    # 18f 起止日期范围（A start_date=2026-01-01）
    r = http(sess, "GET", "/biz/project/list",
             params={"params[beginStartDate]": "2026-01-01", "params[endStartDate]": "2026-01-01",
                     "pageNum": 1, "pageSize": 50})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["dateRange"] = {"total": b.get("total") if isinstance(b, dict) else None,
                           "found_a": any(x.get("projectId") == a_id for x in rows)}

    ok = (checks["projectNo"]["found_a"] and checks["projectNo"]["total"] == 1
          and checks["projectName"]["found_a"]
          and checks["projectType"]["all_national"]
          and checks["status"]["all_draft"]
          and checks["leaderId"]["found_a"] and checks["leaderId"]["total"] == 1
          and checks["dateRange"]["found_a"])
    return {"checks": checks}, ok


def case_19_detail(sess) -> Tuple[Dict[str, Any], bool]:
    """详情：含 leaderName(主持人姓名)、deptName(部门名)、projectNo、字典翻译。"""
    a_id = STATE["project_a_id"]
    r = http(sess, "GET", f"/biz/project/{a_id}")
    b = safe_json(r)
    data = get_data(b)
    ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
          and isinstance(data, dict)
          and data.get("leaderName") == "冒烟科研人员"
          and isinstance(data.get("deptName"), str) and data.get("deptName")
          and data.get("projectNo") == STATE["project_a_no"]
          and data.get("statusLabel") == "立项"
          and data.get("projectTypeLabel") == "国家级")
    note = ""
    if not ok and isinstance(b, dict) and "BindingException" in str(b.get("msg") or ""):
        note = "【业务代码 Bug】ProjectMapper.selectProjectScopedById(projectId, currentUserId) 无 @Param(\"params\")，XML `${params.dataScope}` 无法绑定 → MyBatis BindingException，详情接口对非 researcher 角色 500。"
    return {"status_code": r.status_code, "body": b,
            "data_subset": {k: data.get(k) for k in ("projectId", "projectNo", "projectName",
                                                     "leaderId", "leaderName", "deptId", "deptName",
                                                     "status", "statusLabel", "projectType", "projectTypeLabel")}
            if isinstance(data, dict) else None,
            "raw": r.text[:500]}, ok, note


def case_20_host_auto_row(sess) -> Dict[str, Any]:
    """创建课题时首个 HOST 自动成行（§3.6）。
    member/list API 因 BUG-A（selectProjectScopedById 无 params 绑定）对非 researcher 500，
    故用 DB 验证 HOST 行；API 现象一并记录。"""
    a_id = STATE["project_a_id"]
    api_note = ""
    r = http(sess, "GET", "/biz/project/member/list", params={"projectId": a_id, "pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    api_failed = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
    if api_failed:
        api_note = "member/list API 返回 500（BUG-A：selectProjectScopedById 缺 params 绑定）"
    ok, res = db_query(
        "SELECT MEMBER_ID, USER_ID, ROLE, DEL_FLAG FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID = ? AND ROLE='HOST' AND DEL_FLAG='0'",
        [a_id],
    )
    host_rows = res["rows"] if ok else []
    db_ok = len(host_rows) == 1 and host_rows[0][1] == RES_USER_ID
    return {"api_status_code": r.status_code, "api_body": b, "api_note": api_note,
            "db_host_rows": [list(x) for x in host_rows], "db_host_ok": db_ok}, db_ok


def case_21_add_members(sess) -> Tuple[Dict[str, Any], bool]:
    """新增成员（批量 90002/90003）。"""
    c_id = STATE["project_c_id"]
    body = {"projectId": c_id, "members": [{"userId": MEM2_USER_ID, "role": "PARTICIPANT"},
                                           {"userId": MEM3_USER_ID, "role": "PARTICIPANT"}]}
    r = http(sess, "POST", "/biz/project/member", json_body=body)
    b = safe_json(r)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    note = ""
    if not ok and isinstance(b, dict) and "BindingException" in str(b.get("msg") or ""):
        note = "【业务代码 Bug】addMembers 前置 selectProjectById → selectProjectScopedById 缺 params 绑定 → BindingException，成员新增对非 researcher 角色不可用。"
    return {"request_body": body, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok, note


def case_22_duplicate_member(sess) -> Dict[str, Any]:
    """重复添加同一用户 → UNIQUE 友好报错。"""
    c_id = STATE["project_c_id"]
    body = {"projectId": c_id, "members": [{"userId": MEM2_USER_ID, "role": "PARTICIPANT"}]}
    r = http(sess, "POST", "/biz/project/member", json_body=body)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "已是课题成员" in msg
    return {"request_body": body, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok


def case_23_add_member_host_rejected(sess) -> Dict[str, Any]:
    """addMembers 传 HOST → 业务错误。"""
    c_id = STATE["project_c_id"]
    body = {"projectId": c_id, "members": [{"userId": MEM2_USER_ID, "role": "HOST"}]}
    r = http(sess, "POST", "/biz/project/member", json_body=body)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "换组长请用专用接口" in msg
    return {"request_body": body, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok


def case_24_change_host(sess) -> Tuple[Dict[str, Any], bool]:
    """换主持人：leader_id=90002，HOST 唯一。"""
    c_id = STATE["project_c_id"]
    body = {"projectId": c_id, "newLeaderUserId": MEM2_USER_ID}
    r = http(sess, "PUT", "/biz/project/member/changeHost", json_body=body)
    b = safe_json(r)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    note = ""
    if not ok and isinstance(b, dict) and "BindingException" in str(b.get("msg") or ""):
        note = "【业务代码 Bug】changeHost 前置 selectProjectById → selectProjectScopedById 缺 params 绑定 → BindingException，换主持人对非 researcher 角色不可用。"
    return {"request_body": body, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok, note


def case_25_host_unique_db() -> Dict[str, Any]:
    """DB 断言：changeHost 事务后 HOST 唯一 + leader_id 同步。"""
    c_id = STATE["project_c_id"]
    ok, res = db_query(
        "SELECT USER_ID, ROLE, DEL_FLAG FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID = ? ORDER BY USER_ID",
        [c_id],
    )
    rows = res["rows"] if ok else []
    host_rows = [r for r in rows if r[1] == "HOST" and r[2] == "0"]
    ok2, res2 = db_query("SELECT LEADER_ID FROM RUOYI.PROJECT WHERE PROJECT_ID = ?", [c_id])
    leader_id = res2["rows"][0][0] if ok2 and res2["rows"] else None
    cond_unique_host = len(host_rows) == 1 and host_rows[0][0] == MEM2_USER_ID
    cond_leader_sync = leader_id == MEM2_USER_ID
    all_ok = ok and ok2 and cond_unique_host and cond_leader_sync
    return {"member_rows": [list(r) for r in rows], "host_rows": [list(r) for r in host_rows],
            "leader_id": leader_id,
            "checks": {"host_unique_and_mem2": cond_unique_host, "leader_synced": cond_leader_sync}}, all_ok


def case_26_remove_host_rejected(sess) -> Tuple[Dict[str, Any], bool]:
    """删唯一 HOST → 业务错误。"""
    c_id = STATE["project_c_id"]
    ok, res = db_query("SELECT MEMBER_ID FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID = ? AND ROLE='HOST' AND DEL_FLAG='0'",
                       [c_id])
    host_mid = res["rows"][0][0] if ok and res["rows"] else None
    if host_mid is None:
        return {"error": "未找到 HOST 成员"}, False
    r = http(sess, "DELETE", f"/biz/project/member/{host_mid}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "唯一组长" in msg
    return {"member_id": host_mid, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok


def case_27_remove_participant(sess) -> Tuple[Dict[str, Any], bool]:
    """删非 HOST 成员成功。"""
    c_id = STATE["project_c_id"]
    ok, res = db_query(
        "SELECT MEMBER_ID FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID = ? AND ROLE='PARTICIPANT' AND DEL_FLAG='0'",
        [c_id],
    )
    pids = [r[0] for r in res["rows"]] if ok else []
    if not pids:
        return {"error": "无参与人成员"}, False
    target = pids[0]
    r = http(sess, "DELETE", f"/biz/project/member/{target}")
    b = safe_json(r)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    # 复查不再出现
    r2 = http(sess, "GET", "/biz/project/member/list", params={"projectId": c_id, "pageNum": 1, "pageSize": 50})
    b2 = safe_json(r2)
    rows2 = b2.get("rows", []) if isinstance(b2, dict) else []
    gone = all(x.get("memberId") != target for x in rows2)
    ok = ok and gone
    return {"member_id": target, "status_code": r.status_code, "body": b,
            "after_list": [x.get("memberId") for x in rows2]}, ok


def case_28_edit_project(sess) -> Dict[str, Any]:
    """修改：改名/预算/备注；leaderId/projectNo/status 传入应被忽略（DB 直查验证，规避 BUG-A 详情 500）。"""
    b_id = STATE["project_b_id"]
    body = {"projectId": b_id, "projectName": "冒烟课题B-无关课题-已改名", "projectType": "PROVINCIAL",
            "budgetTotal": "888.00", "remark": TEST_MARK,
            "leaderId": 99999, "projectNo": "KY-HACK", "status": "ACTIVE"}
    r = http(sess, "PUT", "/biz/project", json_body=body)
    b = safe_json(r)
    ok_update = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    _, name = db_project_field(b_id, "PROJECT_NAME")
    _, leader = db_project_field(b_id, "LEADER_ID")
    _, no = db_project_field(b_id, "PROJECT_NO")
    _, status = db_project_field(b_id, "STATUS")
    _, budget = db_project_field(b_id, "BUDGET_TOTAL")
    # 变更1起 budgetTotal 由预算细分推导（本次 edit 未带 splits → 清空 → 0.0），不再支持直接改 budgetTotal
    ok = (ok_update
          and name == "冒烟课题B-无关课题-已改名"
          and leader == 3
          and no == STATE["project_b_no"]
          and status == "DRAFT"
          and budget is not None and abs(float(budget) - 0.0) < 0.001)
    return {"request_body": body, "status_code": r.status_code, "put_body": b,
            "db_verify": {"project_name": name, "leader_id": leader, "project_no": no,
                          "status": status, "budget_total": str(budget)},
            "raw": r.text[:400]}, ok


def case_29_delete_with_members_rejected(sess) -> Dict[str, Any]:
    """变更2：课题含组长+成员 → 删除级联成功（移除旧'仍有有效成员'阻塞）。
    用独立课题验证，避免误删 C 影响 case_33。"""
    resp, ok, pid = add_project(sess, "冒烟课题G-级联删除", 3, project_type="NATIONAL")
    if not ok:
        return {"error": "造课题失败: " + str(resp.get("body"))}, False
    body = {"projectId": pid, "members": [{"userId": MEM2_USER_ID, "role": "PARTICIPANT"}]}
    r = http(sess, "POST", "/biz/project/member", json_body=body)
    b_add = safe_json(r)
    if not (r.status_code == 200 and isinstance(b_add, dict) and b_add.get("code") == 200):
        return {"error": "加成员失败", "body": b_add}, False
    r = http(sess, "DELETE", f"/biz/project/{pid}")
    b = safe_json(r)
    ok_del = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok2, res = db_query("SELECT DEL_FLAG FROM RUOYI.PROJECT WHERE PROJECT_ID = ?", [pid])
    proj_del = res["rows"][0][0] if ok2 and res["rows"] else None
    ok3, res3 = db_query("SELECT DEL_FLAG FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID = ?", [pid])
    mem_dels = [x[0] for x in res3["rows"]] if ok3 and res3["rows"] else []
    ok = ok_del and proj_del == "2" and len(mem_dels) >= 2 and all(x == "2" for x in mem_dels)
    return {"status_code": r.status_code, "body": b, "db_project_del_flag": proj_del,
            "db_member_del_flags": mem_dels}, ok


def case_30_state_legal_chain(sess) -> Tuple[Dict[str, Any], bool]:
    """E：DRAFT->ACTIVE->COMPLETED->ACCEPTED->archive(ARCHIVED) 全链合法（终态 DB 直查）。"""
    e_id = STATE["project_e_id"]
    steps = ["ACTIVE", "COMPLETED", "ACCEPTED"]
    detail: Dict[str, Any] = {}
    all_ok = True
    for target in steps:
        r = http(sess, "POST", "/biz/project/changeStatus",
                 json_body={"projectId": e_id, "targetStatus": target})
        b = safe_json(r)
        ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
        detail[f"changeStatus->{target}"] = {"ok": ok, "body": b}
        all_ok = all_ok and ok
    r = http(sess, "POST", "/biz/project/archive", json_body={"projectId": e_id})
    b = safe_json(r)
    ok_arc = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    detail["archive->ARCHIVED"] = {"ok": ok_arc, "body": b}
    all_ok = all_ok and ok_arc
    _, final_status = db_project_field(e_id, "STATUS")
    detail["final_status_db"] = final_status
    all_ok = all_ok and final_status == "ARCHIVED"
    return detail, all_ok


def case_31_state_completed_archive(sess) -> Tuple[Dict[str, Any], bool]:
    """F：COMPLETED->archive(ARCHIVED)（COMPLETED 路径，终态 DB 直查）。"""
    f_id = STATE["project_f_id"]
    detail: Dict[str, Any] = {}
    r = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": f_id, "targetStatus": "ACTIVE"})
    detail["ACTIVE"] = safe_json(r).get("code")
    r = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": f_id, "targetStatus": "COMPLETED"})
    detail["COMPLETED"] = safe_json(r).get("code")
    r = http(sess, "POST", "/biz/project/archive", json_body={"projectId": f_id})
    b = safe_json(r)
    ok_arc = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    detail["archive"] = b
    _, final_status = db_project_field(f_id, "STATUS")
    detail["final_status_db"] = final_status
    ok = ok_arc and final_status == "ARCHIVED"
    return detail, ok


def case_32_illegal_draft_jump(sess) -> Tuple[Dict[str, Any], bool]:
    """D：DRAFT->COMPLETED（跳跃）→ 业务错误且状态不变（DB 验证）。"""
    d_id = STATE["project_d_id"]
    r = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": d_id, "targetStatus": "COMPLETED"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    _, status_after = db_project_field(d_id, "STATUS")
    ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
          and "状态不允许" in msg and status_after == "DRAFT")
    return {"status_code": r.status_code, "body": b, "status_after_db": status_after, "raw": r.text[:400]}, ok


def case_33_illegal_active_rollback(sess) -> Tuple[Dict[str, Any], bool]:
    """C：ACTIVE->DRAFT（回退）→ 业务错误且状态不变（DB 验证）。"""
    c_id = STATE["project_c_id"]
    r0 = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": c_id, "targetStatus": "ACTIVE"})
    b0 = safe_json(r0)
    if not (r0.status_code == 200 and isinstance(b0, dict) and b0.get("code") == 200):
        return {"pre_status_body": b0}, False
    r = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": c_id, "targetStatus": "DRAFT"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    _, status_after = db_project_field(c_id, "STATUS")
    ok = (isinstance(b, dict) and b.get("code") != 200 and "状态不允许" in msg
          and status_after == "ACTIVE")
    return {"status_code": r.status_code, "body": b, "status_after_db": status_after, "raw": r.text[:400]}, ok


def case_34_illegal_archived_reactivate(sess) -> Dict[str, Any]:
    """E：ARCHIVED->ACTIVE → 业务错误（DB 验证）。"""
    e_id = STATE["project_e_id"]
    r = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": e_id, "targetStatus": "ACTIVE"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    _, status_after = db_project_field(e_id, "STATUS")
    ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
          and ("已归档" in msg or "状态不允许" in msg) and status_after == "ARCHIVED")
    return {"status_code": r.status_code, "body": b, "status_after_db": status_after, "raw": r.text[:400]}, ok


def case_35_archive_reject_draft(sess) -> Dict[str, Any]:
    """B：DRAFT -> archive → 业务错误（DB 验证）。"""
    b_id = STATE["project_b_id"]
    r = http(sess, "POST", "/biz/project/archive", json_body={"projectId": b_id})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    _, status_after = db_project_field(b_id, "STATUS")
    ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
          and "仅 COMPLETED/ACCEPTED 可归档" in msg and status_after == "DRAFT")
    return {"status_code": r.status_code, "body": b, "status_after_db": status_after, "raw": r.text[:400]}, ok


def case_36_edit_archived_rejected(sess) -> Dict[str, Any]:
    """E：ARCHIVED 课题 edit → 业务错误。"""
    e_id = STATE["project_e_id"]
    body = {"projectId": e_id, "projectName": "尝试改归档课题", "projectType": "NATIONAL"}
    r = http(sess, "PUT", "/biz/project", json_body=body)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "已归档课题不可修改" in msg
    return {"request_body": body, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok


def case_37_delete_success(sess) -> Tuple[Dict[str, Any], bool]:
    """D：DB 清空成员后逻辑删除成功，del_flag='2'，列表不出现。"""
    d_id = STATE["project_d_id"]
    ok, r0 = db_execute("UPDATE RUOYI.PROJECT_MEMBER SET DEL_FLAG='2' WHERE PROJECT_ID = ?", [d_id])
    if not ok:
        return {"error": "清成员失败: " + str(r0)}, False
    r = http(sess, "DELETE", f"/biz/project/{d_id}")
    b = safe_json(r)
    ok_del = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    # DB 校验 del_flag
    ok2, res = db_query("SELECT DEL_FLAG FROM RUOYI.PROJECT WHERE PROJECT_ID = ?", [d_id])
    db_del_flag = res["rows"][0][0] if ok2 and res["rows"] else None
    # 列表不出现
    r2 = http(sess, "GET", "/biz/project/list", params={"pageNum": 1, "pageSize": 100})
    b2 = safe_json(r2)
    rows2 = b2.get("rows", []) if isinstance(b2, dict) else []
    not_in_list = all(x.get("projectId") != d_id for x in rows2)
    ok = ok_del and db_del_flag == "2" and not_in_list
    return {"delete_body": b, "db_del_flag": db_del_flag, "in_list_after": not_in_list,
            "status_code": r.status_code}, ok


def case_38_export_admin(sess) -> Dict[str, Any]:
    """Excel 导出：content-type + 文件头。"""
    r = http(sess, "POST", "/biz/project/export")
    ct = r.headers.get("Content-Type", "")
    head = r.content[:8]
    is_xlsx = head.startswith(b"PK\x03\x04")
    is_xls = head[:8] == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
    ok = r.status_code == 200 and (
        "spreadsheet" in ct or "excel" in ct.lower() or "octet-stream" in ct or is_xlsx or is_xls
    )
    return {"status_code": r.status_code, "content_type": ct, "content_length": len(r.content),
            "head_hex": head.hex(), "is_xlsx_magic": is_xlsx, "is_xls_magic": is_xls}, ok


def case_46_admin_detail_witness(sess) -> Dict[str, Any]:
    """admin 详情见证：§9.3.8 admin 应全部通过。实际因 isResearcher() 误判 → 无权访问（Bug 记录）。"""
    a_id = STATE["project_a_id"]
    r = http(sess, "GET", f"/biz/project/{a_id}")
    b = safe_json(r)
    data = get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and isinstance(data, dict)
    note = ""
    if not ok:
        note = "【业务代码 Bug】ProjectServiceImpl.isResearcher() 用 SecurityUtils.hasRole('researcher')，其对含 'admin' 角色 key 的用户恒 true（SUPER_ADMIN 捷径）→ admin 被路由到 researcher'本人相关'分支 → 详情无权访问。"
    return {"target_project_id": a_id, "status_code": r.status_code, "body": b, "raw": r.text[:300]}, ok, note


def case_47_admin_list_witness(sess) -> Dict[str, Any]:
    """admin 列表见证：§6.2 admin data_scope=1 应看到全部未删课题（非本人相关也可见）。
    断言阈值 = 未删课题数（A/B/C/E/F=5，D 在 case_37 已删）。"""
    r = http(sess, "GET", "/biz/project/list", params={"pageNum": 1, "pageSize": 100})
    b = safe_json(r)
    total = b.get("total") if isinstance(b, dict) else None
    rows = b.get("rows", []) if isinstance(b, dict) else []
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and total >= 5
    note = ""
    if not ok:
        note = "admin 列表未看到全部课题（应 data_scope=1 全量）。"
    return {"status_code": r.status_code, "total": total, "seen_ids": [x.get("projectId") for x in rows],
            "body": b, "raw": r.text[:400]}, ok, note


# ====================== researcher 数据权限端到端（硬验收） ======================

def case_40_res_login_and_list(sess) -> Tuple[Dict[str, Any], bool]:
    """researcher 登录 → list 只见 A（本人相关），不见 B（无关）。"""
    a_id, b_id = STATE["project_a_id"], STATE["project_b_id"]
    token, resp = login(sess, RES_USERNAME, ADMIN_PASS)
    login_ok = token is not None
    if not login_ok:
        return {"login_resp": resp}, False
    sess.headers.update({"Authorization": "Bearer " + token})
    r = http(sess, "GET", "/biz/project/list", params={"pageNum": 1, "pageSize": 100})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    ids = [x.get("projectId") for x in rows]
    seen_a = a_id in ids
    seen_b = b_id in ids
    all_self_related = True
    for x in rows:
        if x.get("projectId") == a_id:
            continue
        # 只允许相关课题（A）；此处 D/E/F 也是 90002/3 相关或 3 主持，与 researcher 无关，不应出现
        all_self_related = False
    ok = login_ok and seen_a and not seen_b and all_self_related
    return {"login_resp": {k: resp[k] for k in ("status_code", "body")},
            "list_status": r.status_code, "list_body": b, "seen_ids": ids,
            "seen_a": seen_a, "seen_b": seen_b, "all_self_related_only": all_self_related,
            "rows": rows}, ok


def case_41_res_detail_own(sess) -> Dict[str, Any]:
    """researcher 详情自己主持的 A → 通过。"""
    a_id = STATE["project_a_id"]
    r = http(sess, "GET", f"/biz/project/{a_id}")
    b = safe_json(r)
    data = get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and isinstance(data, dict)
    return {"status_code": r.status_code, "body": b, "data_subset": {k: data.get(k) for k in ("projectId", "projectNo", "leaderId")} if isinstance(data, dict) else None}, ok


def case_42_res_detail_forbidden(sess) -> Dict[str, Any]:
    """researcher 知道无关课题 B 的 id → 无权访问。"""
    b_id = STATE["project_b_id"]
    r = http(sess, "GET", f"/biz/project/{b_id}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "无权访问" in msg
    return {"target_project_id": b_id, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok


def case_43_res_write_forbidden(sess) -> Dict[str, Any]:
    """researcher 写接口（add/edit/remove/archive/changeStatus/member）→ 403。"""
    a_id = STATE["project_a_id"]
    out: Dict[str, Any] = {}
    all_ok = True
    # add
    r = http(sess, "POST", "/biz/project", json_body={"projectName": "res-add", "projectType": "NATIONAL", "leaderId": RES_USER_ID})
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    out["add"] = {"status_code": r.status_code, "code": c, "body": b}
    all_ok = all_ok and c == 403
    # edit
    r = http(sess, "PUT", "/biz/project", json_body={"projectId": a_id, "projectName": "res-edit", "projectType": "NATIONAL"})
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    out["edit"] = {"status_code": r.status_code, "code": c, "body": b}
    all_ok = all_ok and c == 403
    # remove
    r = http(sess, "DELETE", f"/biz/project/{a_id}")
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    out["remove"] = {"status_code": r.status_code, "code": c, "body": b}
    all_ok = all_ok and c == 403
    # archive
    r = http(sess, "POST", "/biz/project/archive", json_body={"projectId": a_id})
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    out["archive"] = {"status_code": r.status_code, "code": c, "body": b}
    all_ok = all_ok and c == 403
    # changeStatus
    r = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": a_id, "targetStatus": "ACTIVE"})
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    out["changeStatus"] = {"status_code": r.status_code, "code": c, "body": b}
    all_ok = all_ok and c == 403
    # member add
    r = http(sess, "POST", "/biz/project/member", json_body={"projectId": a_id, "members": [{"userId": MEM2_USER_ID, "role": "PARTICIPANT"}]})
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    out["member_add"] = {"status_code": r.status_code, "code": c, "body": b}
    all_ok = all_ok and c == 403
    return out, all_ok


def case_44_res_export(sess) -> Tuple[Dict[str, Any], bool]:
    """researcher 导出（§9.3 本人可导出）。修复后应返回 Excel 文件流（非 JSON）。"""
    r = http(sess, "POST", "/biz/project/export")
    ct = r.headers.get("Content-Type", "")
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    is_xlsx = r.content[:2] == b"PK"
    if isinstance(b, dict) and c == 403:
        ok = False
        note = "researcher export 仍 403（role 105 应已挂 2016，仍无权限）"
    elif r.status_code == 200 and c is None and len(r.content) > 0 and is_xlsx:
        ok = True
        note = "researcher 导出成功（返回 Excel 文件流）"
    else:
        ok = False
        note = "researcher export 返回异常（既非文件流也非 403）"
    return {"status_code": r.status_code, "code": c, "content_type": ct,
            "content_length": len(r.content), "is_xlsx_magic": is_xlsx,
            "body": b, "raw": r.text[:200]}, ok, note


# ====================== dept_leader 越权场景（终审 I-1 写操作闸门 / I-2 成员列表去别名过滤） ======================

def case_51_dept_leader_setup(sess) -> Tuple[Dict[str, Any], bool]:
    """造越权场景：A_dl(dept=D1 本部门)、B_dl(dept=D2 跨部门)；给 A_dl 加 D1/D2 两名成员。"""
    resp_a, ok_a, pid_a = add_project(sess, "冒烟课题DL-A(本部门)", 3, project_type="NATIONAL", dept_id=DEPT_D1)
    STATE["project_dl_a_id"] = pid_a
    resp_b, ok_b, pid_b = add_project(sess, "冒烟课题DL-B(跨部门)", 3, project_type="NATIONAL", dept_id=DEPT_D2)
    STATE["project_dl_b_id"] = pid_b
    body = {"projectId": pid_a, "members": [{"userId": MEM2_USER_ID, "role": "PARTICIPANT"},
                                            {"userId": MEM3_USER_ID, "role": "PARTICIPANT"}]}
    r = http(sess, "POST", "/biz/project/member", json_body=body)
    b = safe_json(r)
    ok_m = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok = ok_a and ok_b and ok_m
    return {"project_dl_a_id": pid_a, "project_dl_b_id": pid_b, "add_members_body": b}, ok


def case_52_dept_leader_own_dept_detail(sess) -> Tuple[Dict[str, Any], bool]:
    """dept_leader 登录 + 本部门课题 A_dl 详情可见（正向对照）。"""
    token, resp = login(sess, DL_USERNAME, ADMIN_PASS)
    if token is None:
        return {"login_resp": resp}, False
    sess.headers.update({"Authorization": "Bearer " + token})
    a_id = STATE["project_dl_a_id"]
    r = http(sess, "GET", f"/biz/project/{a_id}")
    b = safe_json(r)
    data = get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and isinstance(data, dict)
    return {"login_code": resp.get("body", {}).get("code"), "detail_status": r.status_code,
            "detail_code": b.get("code") if isinstance(b, dict) else None, "body": b}, ok


def case_53_dept_leader_i1_edit_cross_dept_forbidden(sess) -> Tuple[Dict[str, Any], bool]:
    """I-1：dept_leader PUT 修改跨部门课题 B_dl → 拒绝（业务错误），且 B_dl 未被改动。"""
    b_id = STATE["project_dl_b_id"]
    body = {"projectId": b_id, "projectName": "越权改名", "projectType": "NATIONAL", "remark": "i1-attempt"}
    r = http(sess, "PUT", "/biz/project", json_body=body)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    rejected = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
    _, name = db_project_field(b_id, "PROJECT_NAME")
    _, status = db_project_field(b_id, "STATUS")
    ok = rejected and name == "冒烟课题DL-B(跨部门)" and status == "DRAFT"
    note = ""
    if not ok and not rejected:
        note = "【疑似回归】跨部门编辑未被拒绝（I-1 闸门可能未生效）"
    return {"request_body": body, "status_code": r.status_code, "body": b, "msg": msg,
            "db_project_name": name, "db_status": status}, ok, note


def case_54_dept_leader_i1_changestatus_cross_dept_forbidden(sess) -> Tuple[Dict[str, Any], bool]:
    """I-1：dept_leader changeStatus 跨部门课题 B_dl → 拒绝，状态不变。"""
    b_id = STATE["project_dl_b_id"]
    r = http(sess, "POST", "/biz/project/changeStatus", json_body={"projectId": b_id, "targetStatus": "ACTIVE"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    rejected = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
    _, status = db_project_field(b_id, "STATUS")
    ok = rejected and status == "DRAFT"
    note = ""
    if not ok and not rejected:
        note = "【疑似回归】跨部门 changeStatus 未被拒绝（I-1 闸门可能未生效）"
    return {"status_code": r.status_code, "body": b, "msg": msg, "db_status": status}, ok, note


def case_55_dept_leader_i2_member_list_cross_dept(sess) -> Dict[str, Any]:
    """I-2：dept_leader member/list 本部门课题 A_dl → 跨部门成员不隐藏（D1+D2 两名成员都在）。"""
    a_id = STATE["project_dl_a_id"]
    r = http(sess, "GET", "/biz/project/member/list", params={"projectId": a_id, "pageNum": 1, "pageSize": 50})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    user_ids = [x.get("userId") for x in rows]
    ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
          and MEM2_USER_ID in user_ids and MEM3_USER_ID in user_ids)
    note = ""
    if not ok and isinstance(b, dict) and b.get("code") != 200:
        note = "【疑似回归】member/list 数据权限异常: " + str(b.get("msg") or "")[:80]
    elif not ok:
        note = "【疑似回归】跨部门成员被隐藏（D1/D2 成员未同时返回）"
    return {"status_code": r.status_code, "body": b, "user_ids": user_ids,
            "member_d1_present": MEM2_USER_ID in user_ids,
            "member_d2_present": MEM3_USER_ID in user_ids}, ok, note


def case_50_cleanup() -> Tuple[Dict[str, Any], bool]:
    ok, msg = cleanup_test_data()
    return {"cleanup": msg}, ok


# ====================== main ======================

def dump_results() -> None:
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        for r in RESULTS:
            f.write(json.dumps(r, ensure_ascii=False, default=str) + "\n")


def main() -> int:
    print(f"[boot] base url = {BASE_URL}")
    if not wait_ready(60):
        record("00_backend_ready", False, {}, {"elapsed": 60}, "后端 60 秒内未就绪")
        dump_results()
        return 1
    print("[boot] backend ready")

    # 清理历史残留（幂等）
    cleanup_test_data()

    sess = requests.Session()
    sess.headers.update({"User-Agent": "task3-project-smoke/1.0"})

    # ---- 准备测试用户 ----
    ok, msg = setup_test_users()
    record("05_setup_test_users", ok, {"target": "sys_user+sys_user_role"}, {"msg": msg}, msg)

    # ---- 主会话：science_admin（role 101, data_scope=1，全所全权，避开 admin 误判 researcher 的 bug）----
    token, login_resp = login(sess, SCI_USERNAME, ADMIN_PASS)
    ok = token is not None
    if ok:
        sess.headers.update({"Authorization": "Bearer " + token})
    record("10_login_sci_admin", ok, {"username": SCI_USERNAME},
           {"status_code": login_resp.get("status_code"), "body": login_resp.get("body")})

    cases = [
        ("11_add_project_a", lambda: case_11_add_project_a(sess)),
        ("12_add_project_b", lambda: case_12_add_project_b(sess)),
        ("13_add_project_c", lambda: case_13_add_project_c(sess)),
        ("14_add_project_d", lambda: case_14_add_project_d(sess)),
        ("15_add_project_e", lambda: case_15_add_project_e(sess)),
        ("16_add_project_f", lambda: case_16_add_project_f(sess)),
        ("17_project_no_unique", lambda: case_17_project_no_unique(sess)),
        ("18_list_filters", lambda: case_18_list_filters(sess)),
        ("19_detail", lambda: case_19_detail(sess)),
        ("20_host_auto_row", lambda: case_20_host_auto_row(sess)),
        ("21_add_members", lambda: case_21_add_members(sess)),
        ("22_duplicate_member_rejected", lambda: case_22_duplicate_member(sess)),
        ("23_add_member_host_rejected", lambda: case_23_add_member_host_rejected(sess)),
        ("24_change_host", lambda: case_24_change_host(sess)),
        ("25_host_unique_db", lambda: case_25_host_unique_db()),
        ("26_remove_host_rejected", lambda: case_26_remove_host_rejected(sess)),
        ("27_remove_participant", lambda: case_27_remove_participant(sess)),
        ("28_edit_project", lambda: case_28_edit_project(sess)),
        ("29_delete_with_members_rejected", lambda: case_29_delete_with_members_rejected(sess)),
        ("30_state_legal_chain", lambda: case_30_state_legal_chain(sess)),
        ("31_state_completed_archive", lambda: case_31_state_completed_archive(sess)),
        ("32_illegal_draft_jump", lambda: case_32_illegal_draft_jump(sess)),
        ("33_illegal_active_rollback", lambda: case_33_illegal_active_rollback(sess)),
        ("34_illegal_archived_reactivate", lambda: case_34_illegal_archived_reactivate(sess)),
        ("35_archive_reject_draft", lambda: case_35_archive_reject_draft(sess)),
        ("36_edit_archived_rejected", lambda: case_36_edit_archived_rejected(sess)),
        ("37_delete_success", lambda: case_37_delete_success(sess)),
        ("38_export_admin", lambda: case_38_export_admin(sess)),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/project/*"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # ---- admin 数据权限见证（bug 记录）----
    admin_sess = requests.Session()
    admin_sess.headers.update({"User-Agent": "task3-project-smoke/1.0"})
    atok, aresp = login(admin_sess, ADMIN_USER, ADMIN_PASS)
    if atok:
        admin_sess.headers.update({"Authorization": "Bearer " + atok})
    resp, ok, note46 = case_46_admin_detail_witness(admin_sess)
    record("46_admin_detail_witness", ok, {"url": f"/biz/project/{STATE['project_a_id']}", "as": "admin"}, resp, note46)
    resp, ok, note47 = case_47_admin_list_witness(admin_sess)
    record("47_admin_list_witness", ok, {"url": "/biz/project/list", "as": "admin"}, resp, note47)

    # ---- researcher 数据权限（独立会话）----
    res_sess = requests.Session()
    res_sess.headers.update({"User-Agent": "task3-project-smoke/1.0"})
    resp, ok = case_40_res_login_and_list(res_sess)
    record("40_res_login_and_list", ok, {"username": RES_USERNAME, "url": "/biz/project/list"}, resp)
    resp, ok = case_41_res_detail_own(res_sess)
    record("41_res_detail_own", ok, {"url": f"/biz/project/{STATE['project_a_id']}"}, resp)
    resp, ok = case_42_res_detail_forbidden(res_sess)
    record("42_res_detail_forbidden", ok, {"url": f"/biz/project/{STATE['project_b_id']}"}, resp)
    resp, ok = case_43_res_write_forbidden(res_sess)
    record("43_res_write_forbidden", ok, {"url": "/biz/project (write ops)"}, resp)
    resp, ok, note44 = case_44_res_export(res_sess)
    record("44_res_export", ok, {"url": "/biz/project/export (researcher)"}, resp, note44)

    # ---- dept_leader 越权场景（终审 I-1/I-2）----
    resp, ok = case_51_dept_leader_setup(sess)  # 用 sci_admin 会话造 A_dl/B_dl + 加成员
    record("51_dept_leader_setup", ok, {"url": "/biz/project (dept_leader scene)"}, resp)
    dl_sess = requests.Session()
    dl_sess.headers.update({"User-Agent": "task3-project-smoke/1.0"})
    resp, ok = case_52_dept_leader_own_dept_detail(dl_sess)
    record("52_dept_leader_own_dept_detail", ok, {"username": DL_USERNAME, "url": f"/biz/project/{STATE['project_dl_a_id']}"}, resp)
    resp, ok, note = case_53_dept_leader_i1_edit_cross_dept_forbidden(dl_sess)
    record("53_dept_leader_i1_edit_cross_dept_forbidden", ok, {"url": "PUT /biz/project (cross-dept B_dl)"}, resp, note)
    resp, ok, note = case_54_dept_leader_i1_changestatus_cross_dept_forbidden(dl_sess)
    record("54_dept_leader_i1_changestatus_cross_dept_forbidden", ok, {"url": "POST changeStatus (cross-dept B_dl)"}, resp, note)
    resp, ok, note = case_55_dept_leader_i2_member_list_cross_dept(dl_sess)
    record("55_dept_leader_i2_member_list_cross_dept", ok, {"url": "GET member/list (A_dl cross-dept member)"}, resp, note)

    # ---- 收尾 ----
    resp, ok = case_50_cleanup()
    record("50_cleanup", ok, {"method": "db"}, resp)

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
