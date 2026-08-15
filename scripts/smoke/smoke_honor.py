#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 4 — 阶段7 荣誉管理接口冒烟（任务卡 §七 9 项 + 数据权限三通道 + D5 软删重加 + 回归）
- 覆盖：CRUD 闭环 / 三类型关联 / 查重与软删重加 / 级联删 / researcher+dept_leader+science_admin 数据权限 / 导出 / 回归
- 复用 smoke_project.py 的登录/DB 直查工具 + smoke_contract.py 的 RSA 公钥
- 结果写 scripts/smoke/result_honor.jsonl（独立文件，不与 result.jsonl 撞车）
- 只测不改业务代码；发现的 Bug 记入报告，返回给控制方裁决
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
RESULT_PATH = os.path.join(SCRIPT_DIR, "result_honor.jsonl")

# ============== 测试用户（独占 ID 段 90051+，与既有 smoke 脚本不冲突） ==============
RES_USER_ID    = 90051   # researcher 角色 105, data_scope=5
SCI_USER_ID    = 90052   # science_admin 角色 101, data_scope=1
DL_USER_ID     = 90053   # dept_leader 角色 104, data_scope=3
RES2_USER_ID   = 90054   # 第二个 researcher（构造"他人课题"，不在本人范围）
RES_USERNAME   = "test_honor_res"
SCI_USERNAME   = "test_honor_sci"
DL_USERNAME    = "test_honor_dl"
RES2_USERNAME  = "test_honor_res2"

DEPT_D1 = 101             # dept_leader / res / res2 所属本室
DEPT_D2 = 100             # 他室（关联到他室课题 — 数据权限越权样本）

TEST_MARK = "smoke-task7-honor"

RESULTS: List[Dict[str, Any]] = []
STATE: Dict[str, Any] = {}  # 跨用例传递


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


def sleep_anti_repeat(sec: float = 2.5) -> None:
    """荣誉端点带 @RepeatSubmit(interval=2000)，串行调用需停顿。"""
    time.sleep(sec)


# ============================================================
#  测试用户 / 测试数据准备
# ============================================================

def setup_test_users() -> Tuple[bool, str]:
    """建 test_honor_researcher(105)/test_honor_sci_admin(101)/test_honor_dept_leader(104)/test_honor_researcher_b(105)。"""
    ok, res = db_query("SELECT PASSWORD FROM RUOYI.SYS_USER WHERE USER_NAME='admin'")
    if not ok or not res["rows"]:
        return False, "admin hash 读取失败: " + str(res)
    admin_hash = res["rows"][0][0]
    users = [
        # 第二个 researcher 与 test_honor_researcher 都在 DEPT_D1 本室 — 便于 dept_leader 看本室
        (RES_USER_ID,  RES_USERNAME,  "冒烟荣誉科研人员",  DEPT_D1, 105),
        (RES2_USER_ID, RES2_USERNAME, "冒烟荣誉他人",     DEPT_D1, 105),
        (SCI_USER_ID,  SCI_USERNAME,  "冒烟荣誉科管",      DEPT_D1, 101),
        (DL_USER_ID,   DL_USERNAME,   "冒烟荣誉室主任",    DEPT_D1, 104),
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
    """按 TEST_MARK 物理清理：honor_relation → honor → 合作单位 → 课题成员 → 课题 → 用户。"""
    # 1. honor_relation（按本批 honor_id 级联）
    ok, r = db_execute(
        "DELETE FROM RUOYI.HONOR_RELATION WHERE HONOR_ID IN "
        "(SELECT HONOR_ID FROM RUOYI.HONOR WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 honor_relation 失败: " + str(r)
    # 2. honor
    ok, r = db_execute("DELETE FROM RUOYI.HONOR WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清 honor 失败: " + str(r)
    # 3. cooperative_unit（用 TEST_MARK 在 unit_name 区分）
    ok, r = db_execute("DELETE FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_NAME = ?", [TEST_MARK + "-UNIT"])
    if not ok:
        return False, "清 cooperative_unit 失败: " + str(r)
    # 4. project_member + project（用 REMARK 区分）
    ok, r = db_execute(
        "DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 project_member 失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清 project 失败: " + str(r)
    # 5. 测试用户
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID IN (?, ?, ?, ?)",
        [RES_USER_ID, SCI_USER_ID, DL_USER_ID, RES2_USER_ID])
    if not ok:
        return False, "清 user_role 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?, ?)",
        [RES_USER_ID, SCI_USER_ID, DL_USER_ID, RES2_USER_ID])
    if not ok:
        return False, "清 user 失败: " + str(r)
    return True, "ok"


# ============================================================
#  辅助
# ============================================================

def add_project(sess, name: str, leader_id: int, dept_id: int) -> Tuple[Optional[int], Dict[str, Any]]:
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {
        "projectName": name,
        "projectType": "NATIONAL",
        "leaderId": leader_id,
        "projectNo": f"KY-HONOR-{(STATE['no_seq'] % 900) + 100:03d}",
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


def add_unit(sess, name: str) -> Tuple[Optional[int], Dict[str, Any]]:
    """造顶级合作单位（externalUnitType 必填：COMPANY/SCHOOL 等）。返回 unit_id。
    端点响应不带 unitId，需 list 查询同名回拿。"""
    body = {
        "unitName": name,
        "parentId": 0,
        "externalUnitType": "COMPANY",
        "unitType": "EXTERNAL",
    }
    r = http(sess, "POST", "/biz/unit", json_body=body)
    b = safe_json(r)
    add_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    if not add_ok:
        return None, {"body": body, "resp": b, "unitId": None}
    # 回查 list 取 unitId（按 unitName + parentId）
    rl = http(sess, "GET", "/biz/unit/list", params={"unitName": name})
    bl = safe_json(rl)
    arr = bl.get("data", []) if isinstance(bl, dict) else []
    uid = None
    for x in arr:
        if x.get("unitName") == name and (x.get("parentId") or 0) == 0:
            uid = x.get("unitId")
            break
    return uid, {"body": body, "resp": b, "list_resp": bl, "unitId": uid}


def add_honor(sess, body: Dict[str, Any]) -> Tuple[Optional[int], Dict[str, Any]]:
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/honor", json_body=body)
    b = safe_json(r)
    data = get_data(b)
    hid = data.get("honorId") if isinstance(data, dict) else None
    return hid, {"body": body, "resp": b, "honorId": hid}


def update_honor(sess, body: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/honor", json_body=body)
    b = safe_json(r)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    return ok, {"body": body, "resp": b}


def get_honor(sess, hid: int) -> Tuple[Optional[Dict], Dict[str, Any]]:
    r = http(sess, "GET", f"/biz/honor/{hid}")
    b = safe_json(r)
    data = get_data(b)
    return (data if isinstance(data, dict) else None), {"resp": b}


def list_honor(sess, **params) -> Tuple[Optional[Dict], Dict[str, Any]]:
    base = {"pageNum": 1, "pageSize": 200}
    base.update(params)
    r = http(sess, "GET", "/biz/honor/list", params=base)
    b = safe_json(r)
    return b, {"resp": b}


def add_relation(sess, body: Dict[str, Any]) -> Tuple[Optional[Dict], Dict[str, Any]]:
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/honor/relation", json_body=body)
    b = safe_json(r)
    data = get_data(b)
    return (data if isinstance(data, dict) else None), {"body": body, "resp": b}


def list_relation(sess, hid: int) -> Tuple[Optional[List[Dict]], Dict[str, Any]]:
    r = http(sess, "GET", f"/biz/honor/relation/list", params={"honorId": hid})
    b = safe_json(r)
    data = get_data(b)
    return (data if isinstance(data, list) else None), {"resp": b}


def del_relation(sess, rid: int) -> Tuple[bool, Dict[str, Any]]:
    sleep_anti_repeat()
    r = http(sess, "DELETE", f"/biz/honor/relation/{rid}")
    b = safe_json(r)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    return ok, {"resp": b}


def del_honor(sess, hids: List[int]) -> Tuple[bool, Dict[str, Any]]:
    sleep_anti_repeat()
    r = http(sess, "DELETE", "/biz/honor/" + ",".join(str(x) for x in hids))
    b = safe_json(r)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    return ok, {"resp": b}


def export_honor(sess, **params) -> Tuple[int, Dict[str, Any]]:
    r = http(sess, "POST", "/biz/honor/export", json_body={}, params=params)
    return r.status_code, {
        "resp_first_300": r.text[:300],
        "content_type": r.headers.get("Content-Type", ""),
        "content_length": len(r.content),
    }


def db_honor_field(hid: int, col: str) -> Tuple[bool, Any]:
    ok, res = db_query(f"SELECT {col} FROM RUOYI.HONOR WHERE HONOR_ID = ?", [hid])
    if not ok or not res["rows"]:
        return False, None
    return True, res["rows"][0][0]


def db_relations(hid: int) -> Tuple[bool, Any]:
    ok, res = db_query(
        "SELECT RELATION_ID, HONOR_ID, REF_TYPE, REF_ID, ROLE_DESC, CONTRIBUTION_DESC, DEL_FLAG "
        "FROM RUOYI.HONOR_RELATION WHERE HONOR_ID = ? ORDER BY RELATION_ID ASC", [hid])
    return ok, res["rows"] if ok else None


# ============================================================
#  Case 00：造测试数据（res 主持的本室课题 + res2 主持的他室课题 + 合作单位）
# ============================================================

def case_00_prepare(sess_admin, sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # P_OWN：res 主持，本室 dept 101（researcher 本人相关 — case05 用）
    pid_own, info = add_project(sess_admin, f"{TEST_MARK}-本室本人课题", leader_id=RES_USER_ID, dept_id=DEPT_D1)
    if pid_own is None:
        return {"own": info}, False, "建本室本人课题失败"
    STATE["project_own_id"] = pid_own
    out["own"] = info
    # P_OWN_MEMBER：res 参与（成员），本室（researcher 参与 — case05 用，区别于主持人）
    pid_member, info2 = add_project(sess_admin, f"{TEST_MARK}-本室参与课题", leader_id=DL_USER_ID, dept_id=DEPT_D1)
    if pid_member is None:
        return {"member": info2}, False, "建本室参与课题失败"
    ok_m, info_m = add_project_member(sess_admin, pid_member, RES_USER_ID)
    out["member"] = {"project_id": pid_member, "add_member_resp": info_m["resp"], "ok": ok_m}
    if not ok_m:
        return out, False, "加 res 为成员失败"
    STATE["project_member_id"] = pid_member
    # P_DEPT_OWN：本室其他人主持，本室（dept_leader 本室可见 — case06 用）
    pid_dept, info3 = add_project(sess_admin, f"{TEST_MARK}-本室他人课题", leader_id=DL_USER_ID, dept_id=DEPT_D1)
    if pid_dept is None:
        return {"dept": info3}, False, "建本室他人课题失败"
    STATE["project_dept_id"] = pid_dept
    out["dept"] = info3
    # P_OTHER：res2 主持，他室 dept 100（researcher 不可见 — case05 用）
    pid_other, info4 = add_project(sess_admin, f"{TEST_MARK}-他室他人课题", leader_id=RES2_USER_ID, dept_id=DEPT_D2)
    if pid_other is None:
        return {"other": info4}, False, "建他室课题失败"
    STATE["project_other_id"] = pid_other
    out["other"] = info4
    # UNIT：合作单位（case02 用）
    unit_id, info_u = add_unit(sess_admin, f"{TEST_MARK}-UNIT")
    if unit_id is None:
        return {"unit": info_u}, False, "建合作单位失败"
    STATE["unit_id"] = unit_id
    out["unit"] = info_u
    return out, True, ""


# ============================================================
#  Case 01：CRUD 闭环（新增 → 详情回读 → 修改 → 列表筛选命中）
# ============================================================

def case_01_crud(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    body = {
        "honorName": f"{TEST_MARK}-国家级科技进步奖",
        "honorType": "COLLECTIVE",
        "awardDate": "2025-12-15",
        "awardLevel": "NATIONAL",
        "awardOrg": "国务院",
        "description": "本批国家级",
        "certificateNo": f"CERT-{TEST_MARK}-001",
        "certificateUrl": "/upload/smoke/cert-001.pdf",
        "remark": TEST_MARK,
    }
    hid, info = add_honor(sess_admin, body)
    if hid is None:
        return {"add": info}, False, "新增荣誉失败: " + str(info["resp"])[:200]
    STATE["h1_id"] = hid
    out["add"] = info
    # 1.1 详情回读（逐一字段相等）
    sleep_anti_repeat()
    detail, info_d = get_honor(sess_admin, hid)
    out["detail"] = info_d
    if not isinstance(detail, dict):
        return out, False, "详情回读失败（非字典）"
    fields_eq = (
        detail.get("honorName") == body["honorName"]
        and detail.get("honorType") == body["honorType"]
        and detail.get("awardDate") == body["awardDate"]
        and detail.get("awardLevel") == body["awardLevel"]
        and detail.get("awardOrg") == body["awardOrg"]
        and detail.get("description") == body["description"]
        and detail.get("certificateNo") == body["certificateNo"]
        and detail.get("certificateUrl") == body["certificateUrl"]
    )
    out["detail_fields_eq"] = fields_eq
    # DB 验证字段落库
    db_ok = True
    db_pull = {}
    for col in ("HONOR_NAME", "HONOR_TYPE", "AWARD_LEVEL", "AWARD_ORG",
                "DESCRIPTION", "CERTIFICATE_NO", "CERTIFICATE_URL", "DEL_FLAG"):
        ok, val = db_honor_field(hid, col)
        db_pull[col] = val
        if not ok or val is None:
            db_ok = False
    out["db_pull"] = db_pull
    out["db_ok"] = db_ok
    # 1.2 修改（修改 honorName + honorType）
    update_body = dict(body)
    update_body["honorId"] = hid
    update_body["honorName"] = f"{TEST_MARK}-国家级科技进步奖-MOD"
    update_body["honorType"] = "INDIVIDUAL"
    update_body["awardLevel"] = "PROVINCIAL"
    update_body["certificateNo"] = f"CERT-{TEST_MARK}-001-MOD"
    ok_u, info_u = update_honor(sess_admin, update_body)
    out["update"] = info_u
    if not ok_u:
        return out, False, "修改失败: " + str(info_u["resp"])[:200]
    sleep_anti_repeat()
    detail2, _ = get_honor(sess_admin, hid)
    update_eq = (
        isinstance(detail2, dict)
        and detail2.get("honorName") == update_body["honorName"]
        and detail2.get("honorType") == update_body["honorType"]
        and detail2.get("awardLevel") == update_body["awardLevel"]
        and detail2.get("certificateNo") == update_body["certificateNo"]
    )
    out["update_eq"] = update_eq
    # 1.3 列表筛选：honorName like + honorType + awardLevel + awardDate 区间
    # 先用刚才修改后的全名匹配
    b1, _ = list_honor(sess_admin, honorName=update_body["honorName"])
    rows1 = b1.get("rows", []) if isinstance(b1, dict) else []
    hit_name = hid in [r.get("honorId") for r in rows1]
    # honorType 匹配
    b2, _ = list_honor(sess_admin, honorType="INDIVIDUAL")
    rows2 = b2.get("rows", []) if isinstance(b2, dict) else []
    hit_type = hid in [r.get("honorId") for r in rows2]
    # awardLevel 匹配
    b3, _ = list_honor(sess_admin, awardLevel="PROVINCIAL")
    rows3 = b3.get("rows", []) if isinstance(b3, dict) else []
    hit_level = hid in [r.get("honorId") for r in rows3]
    # awardDate 区间（包含 2025-12-15）
    b4, _ = list_honor(sess_admin, **{
        "params[beginAwardDate]": "2025-12-01",
        "params[endAwardDate]": "2025-12-31",
    })
    rows4 = b4.get("rows", []) if isinstance(b4, dict) else []
    hit_date = hid in [r.get("honorId") for r in rows4]
    # 不命中样本：honorType=INDIVIDUAL 但是本测试荣誉名
    b5, _ = list_honor(sess_admin, honorType="COLLECTIVE")
    rows5 = b5.get("rows", []) if isinstance(b5, dict) else []
    exclude = hid not in [r.get("honorId") for r in rows5]
    out["filter"] = {
        "hit_name": hit_name, "hit_type": hit_type,
        "hit_level": hit_level, "hit_date": hit_date,
        "exclude_after_type_change": exclude,
    }
    ok_all = fields_eq and db_ok and update_eq and hit_name and hit_type and hit_level and hit_date and exclude
    return out, ok_all, "" if ok_all else (
        f"fields_eq={fields_eq} db={db_ok} update={update_eq} "
        f"name={hit_name} type={hit_type} level={hit_level} date={hit_date} exclude={exclude}")


# ============================================================
#  Case 02：三类型关联（PROJECT/RESEARCHER/UNIT 三条；refName 解析正确）
# ============================================================

def case_02_three_relations(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    h = STATE["h1_id"]
    proj_own = STATE["project_own_id"]
    # PROJECT：本室本人课题 P_OWN
    rel_p, info_p = add_relation(sess_admin, {
        "honorId": h, "refType": "PROJECT", "refId": proj_own,
        "roleDesc": "主持课题关联", "contributionDesc": "项目主持",
    })
    if not isinstance(rel_p, dict) or rel_p.get("relationId") is None:
        return {"rel_p": info_p}, False, "PROJECT 关联失败: " + str(info_p["resp"])[:200]
    out["rel_p"] = {"relation_id": rel_p.get("relationId"), "resp": info_p["resp"]}
    # RESEARCHER：本人 user_id
    rel_r, info_r = add_relation(sess_admin, {
        "honorId": h, "refType": "RESEARCHER", "refId": RES_USER_ID,
        "roleDesc": "本人作为人员", "contributionDesc": "主要完成人",
    })
    if not isinstance(rel_r, dict) or rel_r.get("relationId") is None:
        return {"rel_r": info_r}, False, "RESEARCHER 关联失败: " + str(info_r["resp"])[:200]
    out["rel_r"] = {"relation_id": rel_r.get("relationId"), "resp": info_r["resp"]}
    # UNIT
    rel_u, info_u = add_relation(sess_admin, {
        "honorId": h, "refType": "UNIT", "refId": STATE["unit_id"],
        "roleDesc": "合作单位", "contributionDesc": "协作贡献",
    })
    if not isinstance(rel_u, dict) or rel_u.get("relationId") is None:
        return {"rel_u": info_u}, False, "UNIT 关联失败: " + str(info_u["resp"])[:200]
    out["rel_u"] = {"relation_id": rel_u.get("relationId"), "resp": info_u["resp"]}
    # relation/list 拉回
    sleep_anti_repeat()
    arr, info_l = list_relation(sess_admin, h)
    out["list"] = info_l
    if not isinstance(arr, list) or len(arr) != 3:
        return out, False, f"relation/list 应返 3 条实际 {len(arr) if isinstance(arr, list) else '非列表'}"
    types = [r.get("refType") for r in arr]
    type_ok = sorted(types) == ["PROJECT", "RESEARCHER", "UNIT"]
    # refName 验证：PROJECT→"课题编号 课题名"；RESEARCHER→用户 nick_name；UNIT→单位名
    proj_row = next((x for x in arr if x.get("refType") == "PROJECT"), None)
    res_row = next((x for x in arr if x.get("refType") == "RESEARCHER"), None)
    unit_row = next((x for x in arr if x.get("refType") == "UNIT"), None)
    proj_ref_name = proj_row.get("refName") if proj_row else None
    res_ref_name = res_row.get("refName") if res_row else None
    unit_ref_name = unit_row.get("refName") if unit_row else None
    # PROJECT refName 期望："KY-HONOR-xxx 项目名"
    proj_ok = bool(proj_ref_name) and proj_ref_name.strip() != ""
    res_ok = res_ref_name == "冒烟荣誉科研人员"
    unit_ok = unit_ref_name == f"{TEST_MARK}-UNIT"
    out["ref_names"] = {
        "PROJECT": proj_ref_name, "RESEARCHER": res_ref_name, "UNIT": unit_ref_name,
        "proj_ok": proj_ok, "res_ok": res_ok, "unit_ok": unit_ok,
    }
    # roleDesc/contributionDesc 落库回读
    ok_db, db_rows = db_relations(h)
    desc_ok = False
    if ok_db and db_rows:
        # 取 PROJECT 关联行
        proj_db = next((r for r in db_rows if r[2] == "PROJECT"), None)
        res_db = next((r for r in db_rows if r[2] == "RESEARCHER"), None)
        unit_db = next((r for r in db_rows if r[2] == "UNIT"), None)
        desc_ok = (
            proj_db and proj_db[4] == "主持课题关联" and proj_db[5] == "项目主持"
            and res_db and res_db[4] == "本人作为人员" and res_db[5] == "主要完成人"
            and unit_db and unit_db[4] == "合作单位" and unit_db[5] == "协作贡献"
            and all(r[6] == "0" for r in db_rows)
        )
        out["db_rows"] = [(r[2], r[4], r[5], r[6]) for r in db_rows]
    out["desc_db_ok"] = desc_ok
    ok_all = type_ok and proj_ok and res_ok and unit_ok and desc_ok
    return out, ok_all, "" if ok_all else (
        f"types={type_ok} proj={proj_ok} res={res_ok} unit={unit_ok} desc_db={desc_ok}")


# ============================================================
#  Case 03：查重 + 软删重加（D5 无唯一索引路径）
# ============================================================

def case_03_duplicate_and_redo(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    h = STATE["h1_id"]
    proj_own = STATE["project_own_id"]
    # 3.1 重复添加 (h, PROJECT, proj_own) → 拒绝（业务错误，非 500）
    sleep_anti_repeat()
    dup, info_dup = add_relation(sess_admin, {
        "honorId": h, "refType": "PROJECT", "refId": proj_own,
        "roleDesc": "dup", "contributionDesc": "dup",
    })
    b_dup = info_dup["resp"]
    msg_dup = str(b_dup.get("msg") or "") if isinstance(b_dup, dict) else ""
    dup_reject = (
        dup is None
        and isinstance(b_dup, dict)
        and b_dup.get("code") != 200
        and "该关联已存在" in msg_dup
    )
    out["dup"] = {"resp": b_dup, "msg": msg_dup, "rejected": dup_reject}
    # 3.2 删除该 PROJECT 关联
    rel_rows = []
    ok_db, rows = db_relations(h)
    rel_rows = rows if ok_db else []
    proj_rel = next((r for r in rel_rows if r[2] == "PROJECT"), None)
    if proj_rel is None:
        return out, False, "未找到 PROJECT 关联行"
    STATE["project_rel_id"] = proj_rel[0]
    ok_del, info_del = del_relation(sess_admin, proj_rel[0])
    if not ok_del:
        return {"del": info_del}, False, "删除关联失败"
    out["del"] = {"resp": info_del["resp"]}
    # DB 验证 del_flag='2'
    ok_d, val_d = db_query(
        "SELECT DEL_FLAG FROM RUOYI.HONOR_RELATION WHERE RELATION_ID = ?", [proj_rel[0]])
    db_del_flag = val_d["rows"][0][0] if ok_d and val_d["rows"] else None
    out["del_db"] = {"del_flag": db_del_flag, "ok": db_del_flag == "2"}
    # 3.3 再次添加（应成功）
    sleep_anti_repeat()
    rel_re, info_re = add_relation(sess_admin, {
        "honorId": h, "refType": "PROJECT", "refId": proj_own,
        "roleDesc": "re-add", "contributionDesc": "re-add",
    })
    re_add_ok = (
        isinstance(rel_re, dict)
        and rel_re.get("relationId") is not None
        and info_re["resp"].get("code") == 200
    )
    out["re_add"] = {"relation_id": rel_re.get("relationId") if isinstance(rel_re, dict) else None,
                     "resp": info_re["resp"], "ok": re_add_ok}
    ok_all = dup_reject and db_del_flag == "2" and re_add_ok
    return out, ok_all, "" if ok_all else f"dup={dup_reject} db_del={db_del_flag} re_add={re_add_ok}"


# ============================================================
#  Case 04：级联删荣誉（DB 验证 honor + 全部 relation 均 del_flag='2'）
# ============================================================

def case_04_cascade_delete(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    h = STATE["h1_id"]
    ok_d, info_d = del_honor(sess_admin, [h])
    out["del"] = info_d
    if not ok_d:
        return out, False, "删除荣誉失败: " + str(info_d["resp"])[:200]
    # DB：honor.del_flag='2'
    ok_h, hflag = db_query("SELECT DEL_FLAG FROM RUOYI.HONOR WHERE HONOR_ID = ?", [h])
    honor_del = hflag["rows"][0][0] if ok_h and hflag["rows"] else None
    out["honor_del"] = honor_del
    # DB：全部关联 del_flag='2'
    ok_r, rflag = db_query(
        "SELECT RELATION_ID, DEL_FLAG FROM RUOYI.HONOR_RELATION WHERE HONOR_ID = ?", [h])
    rel_rows = rflag["rows"] if ok_r else []
    all_del2 = len(rel_rows) > 0 and all(r[1] == "2" for r in rel_rows)
    out["rel_dels"] = rel_rows
    ok_all = honor_del == "2" and all_del2
    return out, ok_all, "" if ok_all else f"honor={honor_del} rel_all_del2={all_del2}"


# ============================================================
#  Case 05：researcher 数据权限（A 本人 → B 本人课题 → C 无关联 → D 他人课题）
# ============================================================

def case_05_researcher_permission(sess_res, sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 造四个荣誉
    # HA：本人作为 RESEARCHER 关联
    hA, infoA = add_honor(sess_admin, {
        "honorName": f"{TEST_MARK}-A-本人科研人员",
        "honorType": "INDIVIDUAL", "awardLevel": "PROVINCIAL",
        "awardDate": "2025-11-01", "remark": TEST_MARK,
    })
    if hA is None:
        return {"addA": infoA}, False, "建 HA 失败"
    STATE["ha_id"] = hA
    sleep_anti_repeat()
    add_relation(sess_admin, {
        "honorId": hA, "refType": "RESEARCHER", "refId": RES_USER_ID,
        "roleDesc": "本人", "contributionDesc": "本人",
    })
    # HB：本人主持课题 P_OWN 关联
    hB, infoB = add_honor(sess_admin, {
        "honorName": f"{TEST_MARK}-B-本人主持课题",
        "honorType": "COLLECTIVE", "awardLevel": "GROUP",
        "awardDate": "2025-11-01", "remark": TEST_MARK,
    })
    if hB is None:
        return {"addB": infoB}, False, "建 HB 失败"
    STATE["hb_id"] = hB
    sleep_anti_repeat()
    add_relation(sess_admin, {
        "honorId": hB, "refType": "PROJECT", "refId": STATE["project_own_id"],
        "roleDesc": "主持", "contributionDesc": "主持",
    })
    # HB2：本人参与课题（成员） — 覆盖"参与"通道
    hB2, infoB2 = add_honor(sess_admin, {
        "honorName": f"{TEST_MARK}-B2-本人参与课题",
        "honorType": "COLLECTIVE", "awardLevel": "GROUP",
        "awardDate": "2025-11-01", "remark": TEST_MARK,
    })
    if hB2 is None:
        return {"addB2": infoB2}, False, "建 HB2 失败"
    STATE["hb2_id"] = hB2
    sleep_anti_repeat()
    add_relation(sess_admin, {
        "honorId": hB2, "refType": "PROJECT", "refId": STATE["project_member_id"],
        "roleDesc": "参与", "contributionDesc": "参与",
    })
    # HC：无关联（tester 造，不加任何关联）
    hC, infoC = add_honor(sess_admin, {
        "honorName": f"{TEST_MARK}-C-无关联",
        "honorType": "INDIVIDUAL", "awardLevel": "COMPANY",
        "awardDate": "2025-11-01", "remark": TEST_MARK,
    })
    if hC is None:
        return {"addC": infoC}, False, "建 HC 失败"
    STATE["hc_id"] = hC
    # HD：他人课题 P_OTHER 关联
    hD, infoD = add_honor(sess_admin, {
        "honorName": f"{TEST_MARK}-D-他人课题",
        "honorType": "COLLECTIVE", "awardLevel": "INSTITUTE",
        "awardDate": "2025-11-01", "remark": TEST_MARK,
    })
    if hD is None:
        return {"addD": infoD}, False, "建 HD 失败"
    STATE["hd_id"] = hD
    sleep_anti_repeat()
    add_relation(sess_admin, {
        "honorId": hD, "refType": "PROJECT", "refId": STATE["project_other_id"],
        "roleDesc": "他室", "contributionDesc": "他室",
    })

    # 5.1 researcher 列表：含 HA/HB/HB2，不含 HC/HD
    b_list, info_l = list_honor(sess_res)
    rows = b_list.get("rows", []) if isinstance(b_list, dict) else []
    ids = [r.get("honorId") for r in rows]
    out["list"] = {"ids": ids, "total": b_list.get("total") if isinstance(b_list, dict) else None}
    list_ok = (
        hA in ids and hB in ids and hB2 in ids
        and hC not in ids and hD not in ids
    )
    # 5.2 researcher GET HD → 无权访问
    sleep_anti_repeat()
    detail, info_d2 = get_honor(sess_res, hD)
    b_d2 = info_d2["resp"]
    msg_d2 = str(b_d2.get("msg") or "") if isinstance(b_d2, dict) else ""
    detail_forbid = (
        detail is None and isinstance(b_d2, dict) and b_d2.get("code") != 200
        and "无权访问" in msg_d2
    )
    out["detail_hd"] = {"resp": b_d2, "msg": msg_d2, "forbidden": detail_forbid}
    # 5.3 researcher POST/PUT/DELETE/关联增删均 403
    # POST 新增
    sleep_anti_repeat()
    r_post = http(sess_res, "POST", "/biz/honor", json_body={
        "honorName": f"{TEST_MARK}-res-越权新增", "remark": TEST_MARK,
    })
    bp = safe_json(r_post)
    cp = bp.get("code") if isinstance(bp, dict) else None
    post_403 = (r_post.status_code in (200, 403) and cp == 403)
    out["post_403"] = {"status": r_post.status_code, "code": cp, "forbidden": post_403}
    # PUT 修改
    sleep_anti_repeat()
    r_put = http(sess_res, "PUT", "/biz/honor", json_body={
        "honorId": hA, "honorName": f"{TEST_MARK}-res-越权改",
    })
    bu = safe_json(r_put)
    cu = bu.get("code") if isinstance(bu, dict) else None
    put_403 = (r_put.status_code in (200, 403) and cu == 403)
    out["put_403"] = {"status": r_put.status_code, "code": cu, "forbidden": put_403}
    # DELETE 荣誉
    sleep_anti_repeat()
    r_del = http(sess_res, "DELETE", f"/biz/honor/{hA}")
    bd = safe_json(r_del)
    cd = bd.get("code") if isinstance(bd, dict) else None
    del_403 = (r_del.status_code in (200, 403) and cd == 403)
    out["del_403"] = {"status": r_del.status_code, "code": cd, "forbidden": del_403}
    # 关联新增
    sleep_anti_repeat()
    r_arp = http(sess_res, "POST", "/biz/honor/relation", json_body={
        "honorId": hA, "refType": "PROJECT", "refId": STATE["project_other_id"],
    })
    barp = safe_json(r_arp)
    carp = barp.get("code") if isinstance(barp, dict) else None
    rel_add_403 = (r_arp.status_code in (200, 403) and carp == 403)
    out["rel_add_403"] = {"status": r_arp.status_code, "code": carp, "forbidden": rel_add_403}
    # 关联删除
    sleep_anti_repeat()
    r_drp = http(sess_res, "DELETE", "/biz/honor/relation/1")  # 任意 ID
    bdrp = safe_json(r_drp)
    cdrp = bdrp.get("code") if isinstance(bdrp, dict) else None
    rel_del_403 = (r_drp.status_code in (200, 403) and cdrp == 403)
    out["rel_del_403"] = {"status": r_drp.status_code, "code": cdrp, "forbidden": rel_del_403}
    # HB2 验证本人作为 member 也命中列表
    ok_all = (list_ok and detail_forbid and post_403 and put_403 and del_403
              and rel_add_403 and rel_del_403)
    return out, ok_all, "" if ok_all else (
        f"list={list_ok} detail={detail_forbid} post={post_403} put={put_403} "
        f"del={del_403} rel_add={rel_add_403} rel_del={rel_del_403}")


# ============================================================
#  Case 06：dept_leader 数据权限（本室 → 可见；他室 → 不可见；无关联 → 不可见）
# ============================================================

def case_06_dept_leader_permission(sess_dl, sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 取已有荣誉：HB（主持人 DL → 本室 dept101）应该可见
    # HD 关联到他室（dept100） → DL 不可见
    # HC 无关联 → DL 不可见（按语义："无关联荣誉仅全所范围角色可见"）
    # 补充：本室其他人员关联的荣誉（本室 RES2_USER_ID 关联）
    hE, infoE = add_honor(sess_admin, {
        "honorName": f"{TEST_MARK}-E-本室他人关联",
        "honorType": "INDIVIDUAL", "awardLevel": "PROVINCIAL",
        "awardDate": "2025-11-01", "remark": TEST_MARK,
    })
    if hE is None:
        return {"addE": infoE}, False, "建 HE 失败"
    STATE["he_id"] = hE
    sleep_anti_repeat()
    add_relation(sess_admin, {
        "honorId": hE, "refType": "RESEARCHER", "refId": RES2_USER_ID,
        "roleDesc": "本室他人", "contributionDesc": "本室他人",
    })
    # HE2：本室课题 P_DEPT_OWN 关联 → DL 应可见
    hE2, infoE2 = add_honor(sess_admin, {
        "honorName": f"{TEST_MARK}-E2-本室课题关联",
        "honorType": "COLLECTIVE", "awardLevel": "GROUP",
        "awardDate": "2025-11-01", "remark": TEST_MARK,
    })
    if hE2 is None:
        return {"addE2": infoE2}, False, "建 HE2 失败"
    STATE["he2_id"] = hE2
    sleep_anti_repeat()
    add_relation(sess_admin, {
        "honorId": hE2, "refType": "PROJECT", "refId": STATE["project_dept_id"],
        "roleDesc": "本室主持人", "contributionDesc": "本室",
    })

    # DL 列表
    b_list, info_l = list_honor(sess_dl)
    rows = b_list.get("rows", []) if isinstance(b_list, dict) else []
    ids = [r.get("honorId") for r in rows]
    out["list"] = {"ids": ids, "total": b_list.get("total") if isinstance(b_list, dict) else None}
    # DL 期望：可见 HB（主持人 DL 本室）、HE（本室他人作为 RESEARCHER）、HE2（本室课题关联）
    #       不可见：HD（他室课题）、HC（无关联）、HB2（res 成员 — res 是 DL 的下属？查 data_scope=3 本部门 → 应可见）
    #       注：DL data_scope=3 "本部门"，res / res2 / DL 都在 DEPT_D1，HB2 (res 参与 P_OWN_member 在 DEPT_D1) 应可见
    expected_visible = {STATE["hb_id"], hE, hE2, STATE["hb2_id"]}
    expected_invisible = {STATE["hd_id"], STATE["hc_id"]}
    visible_set = set(x for x in ids if x in expected_visible | expected_invisible)
    visible_ok = expected_visible.issubset(set(ids))
    invisible_ok = expected_invisible.isdisjoint(set(ids))
    out["visibility"] = {
        "expected_visible": sorted(expected_visible),
        "expected_invisible": sorted(expected_invisible),
        "visible_ok": visible_ok, "invisible_ok": invisible_ok,
    }
    ok_all = visible_ok and invisible_ok
    return out, ok_all, "" if ok_all else (
        f"visible_ok={visible_ok} invisible_ok={invisible_ok} ids={ids}")


# ============================================================
#  Case 07：science_admin 全所可见 + 关联增删全通 + 导出 200
# ============================================================

def case_07_sci_admin(sess_sci) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 7.1 列表全所：含 HC（无关联）
    b_list, info_l = list_honor(sess_sci)
    rows = b_list.get("rows", []) if isinstance(b_list, dict) else []
    ids = [r.get("honorId") for r in rows]
    out["list"] = {"ids": ids, "total": b_list.get("total") if isinstance(b_list, dict) else None}
    sci_see_all = (
        STATE["ha_id"] in ids
        and STATE["hb_id"] in ids
        and STATE["hb2_id"] in ids
        and STATE["hc_id"] in ids  # 无关联荣誉 — 全所应见
        and STATE["hd_id"] in ids
    )
    # 7.2 关联增删全通（增 → 删）
    sleep_anti_repeat()
    proj_own = STATE["project_own_id"]
    rel, info_r = add_relation(sess_sci, {
        "honorId": STATE["hc_id"], "refType": "PROJECT", "refId": proj_own,
        "roleDesc": "sci_add", "contributionDesc": "sci_add",
    })
    rel_id = rel.get("relationId") if isinstance(rel, dict) else None
    rel_add_ok = rel_id is not None
    out["sci_rel_add"] = {"resp": info_r["resp"], "relation_id": rel_id, "ok": rel_add_ok}
    if not rel_add_ok:
        return out, False, "sci_admin 关联新增失败: " + str(info_r["resp"])[:200]
    ok_del, info_d = del_relation(sess_sci, rel_id)
    out["sci_rel_del"] = {"resp": info_d["resp"], "ok": ok_del}
    # 7.3 导出 200
    code, info_e = export_honor(sess_sci)
    out["export"] = {"status": code, "info": info_e}
    export_ok = code == 200
    ok_all = sci_see_all and rel_add_ok and ok_del and export_ok
    return out, ok_all, "" if ok_all else (
        f"sci_see_all={sci_see_all} rel_add={rel_add_ok} rel_del={ok_del} export={export_ok}")


# ============================================================
#  Case 08：researcher 导出（不报错；如可解析，仅含本人相关）
# ============================================================

def case_08_researcher_export(sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    code, info_e = export_honor(sess_res)
    out["export"] = {"status": code, "info": info_e}
    # 不强断言解析 Excel（lu子集简化为 status_code == 200）
    export_ok = code == 200
    return out, export_ok, "" if export_ok else f"researcher export status={code}"


# ============================================================
#  Case 09：回归 — 调 smoke_project 的 main()
# ============================================================

def case_09_regression() -> Tuple[Dict[str, Any], bool]:
    """委托 smoke_project.py.main() 重跑全量；结果同步到本脚本 results。"""
    out: Dict[str, Any] = {}
    try:
        # smoke_project 直接 import 调用 — 它会写自己的 result.jsonl
        import smoke_project as SP  # noqa: E402
        ret = SP.main()
        out["smoke_project_main_rc"] = ret
        ok = ret == 0
    except SystemExit as e:
        out["smoke_project_main_rc"] = e.code
        ok = e.code == 0
    except Exception as e:  # noqa: BLE001
        out["smoke_project_main_rc"] = repr(e)
        ok = False
    return out, ok, "" if ok else f"smoke_project rc={out.get('smoke_project_main_rc')}"


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
    sess = make_session("task7-honor-smoke/1.0")
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
    sess_res = make_session("task7-honor-smoke/1.0")
    t, resp = login(sess_res, RES_USERNAME, ADMIN_PASS)
    if t:
        sess_res.headers.update({"Authorization": "Bearer " + t})
    record("11_login_researcher", bool(t), {"username": RES_USERNAME},
           {"status_code": resp.get("status_code")}, "" if t else str(resp)[:300])
    sess_dl = make_session("task7-honor-smoke/1.0")
    t, resp = login(sess_dl, DL_USERNAME, ADMIN_PASS)
    if t:
        sess_dl.headers.update({"Authorization": "Bearer " + t})
    record("12_login_dept_leader", bool(t), {"username": DL_USERNAME},
           {"status_code": resp.get("status_code")}, "" if t else str(resp)[:300])
    sess_sci = make_session("task7-honor-smoke/1.0")
    t, resp = login(sess_sci, SCI_USERNAME, ADMIN_PASS)
    if t:
        sess_sci.headers.update({"Authorization": "Bearer " + t})
    record("13_login_sci_admin", bool(t), {"username": SCI_USERNAME},
           {"status_code": resp.get("status_code")}, "" if t else str(resp)[:300])
    if not (sess_res.headers.get("Authorization") and sess_dl.headers.get("Authorization")
            and sess_sci.headers.get("Authorization")):
        cleanup_test_data()
        dump_results()
        return 1

    cases = [
        ("00_prepare_data",      lambda: case_00_prepare(sess, sess_res)),
        ("01_crud_loop",          lambda: case_01_crud(sess)),
        ("02_three_relations",    lambda: case_02_three_relations(sess)),
        ("03_duplicate_and_redo", lambda: case_03_duplicate_and_redo(sess)),
        ("04_cascade_delete",     lambda: case_04_cascade_delete(sess)),
        ("05_researcher_perm",    lambda: case_05_researcher_permission(sess_res, sess)),
        ("06_dept_leader_perm",   lambda: case_06_dept_leader_permission(sess_dl, sess)),
        ("07_sci_admin",          lambda: case_07_sci_admin(sess_sci)),
        ("08_researcher_export",  lambda: case_08_researcher_export(sess_res)),
        ("09_regression",         case_09_regression),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/honor|/biz/project|/biz/unit"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # 收尾
    cleanup_ok, cleanup_msg = cleanup_test_data()
    record("99_cleanup", cleanup_ok, {"method": "db"}, {"msg": cleanup_msg})

    # DB 零残留复查
    ok_zero, res_zero = db_query(
        "SELECT COUNT(*) FROM RUOYI.HONOR WHERE REMARK = ?", [TEST_MARK])
    honor_left = res_zero["rows"][0][0] if ok_zero and res_zero["rows"] else None
    ok_zero2, res_zero2 = db_query(
        "SELECT COUNT(*) FROM RUOYI.HONOR_RELATION WHERE HONOR_ID IN "
        "(SELECT HONOR_ID FROM RUOYI.HONOR WHERE REMARK = ?)", [TEST_MARK])
    rel_left = res_zero2["rows"][0][0] if ok_zero2 and res_zero2["rows"] else None
    ok_zero3, res_zero3 = db_query(
        "SELECT COUNT(*) FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    proj_left = res_zero3["rows"][0][0] if ok_zero3 and res_zero3["rows"] else None
    ok_zero4, res_zero4 = db_query(
        "SELECT COUNT(*) FROM RUOYI.SYS_USER WHERE REMARK = ?", [TEST_MARK])
    user_left = res_zero4["rows"][0][0] if ok_zero4 and res_zero4["rows"] else None
    zero_ok = (honor_left == 0 and rel_left == 0 and proj_left == 0 and user_left == 0)
    record("98_db_zero_leftover", zero_ok, {"method": "db"},
           {"honor_left": honor_left, "rel_left": rel_left,
            "project_left": proj_left, "user_left": user_left})

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())