#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 4 — 阶段3 合同管理接口冒烟
- 覆盖：任务卡 §六 8 项验收 + 3 项附加验证（显式 null 清空 / 节点列表契约 / DB 唯一索引兜底） + 回归
- 复用 smoke_project.py 的登录框架 / dmPython DB 直查工具
- 结果写 scripts/smoke/result.jsonl（同 result.jsonl 文件覆盖）
- 只测不改业务代码；发现的 Bug 记入报告
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
from datetime import date, datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

import requests
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5

try:
    import dmPython  # 达梦
except Exception:  # pragma: no cover
    dmPython = None

BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8087")
ADMIN_USER = os.environ.get("ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "admin123")

DM_PASSWORD = os.environ.get("DM_PASSWORD", "Ruoyi12345")  # application-devdm.yml
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
RESULT_PATH = os.path.join(SCRIPT_DIR, "result.jsonl")

# ============== 测试用户/标记 ==============
# 复用 smoke_project.py 的用户 ID 约定，便于两脚本独立跑后清理不留残留
RES_USER_ID = 90001      # researcher 角色 105, data_scope=5
SCI_USER_ID = 90010      # science_admin 角色 101, data_scope=1
LEADER_B_USER_ID = 90003 # 普通成员（不挂角色），作为 B 课题主持人（非 researcher 相关）
RES_USERNAME = "test_researcher"
SCI_USERNAME = "test_sci_admin"

TEST_MARK = "smoke-task4-contract"

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


# ============================================================
#  测试数据准备 / 清理
# ============================================================

def setup_test_users() -> Tuple[bool, str]:
    """建 test_researcher(105) + test_sci_admin(101) + test_leader_b。密码复用 admin 哈希。"""
    ok, res = db_query("SELECT PASSWORD FROM RUOYI.SYS_USER WHERE USER_NAME='admin'")
    if not ok or not res["rows"]:
        return False, "admin hash 读取失败: " + str(res)
    admin_hash = res["rows"][0][0]

    users = [
        (RES_USER_ID, RES_USERNAME, "冒烟科研人员", 100, 105),
        (SCI_USER_ID, SCI_USERNAME, "冒烟科管", 100, 101),
        (LEADER_B_USER_ID, "test_leader_b", "冒烟无关主持人", 100, None),
    ]
    for uid, uname, nick, dept, role_id in users:
        # 先删已有（如残留）
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
    """按 TEST_MARK 清理 contract / contract_node / project_member / project / unit_contact / unit / 用户。"""
    # 1. contract_node 级联软删后的物理清理
    ok, r = db_execute(
        "DELETE FROM RUOYI.CONTRACT_NODE WHERE CONTRACT_ID IN "
        "(SELECT CONTRACT_ID FROM RUOYI.CONTRACT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 contract_node 失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.CONTRACT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清 contract 失败: " + str(r)
    # 2. project / project_member（按 remark；含 DRAFT/状态各种）
    ok, r = db_execute(
        "DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 project_member 失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清 project 失败: " + str(r)
    # 3. unit_contact / unit
    ok, r = db_execute(
        "DELETE FROM RUOYI.UNIT_CONTACT WHERE UNIT_ID IN "
        "(SELECT UNIT_ID FROM RUOYI.COOPERATIVE_UNIT WHERE REMARK = ? OR UNIT_NAME LIKE ?)",
        [TEST_MARK, f"{TEST_MARK}%"])
    if not ok:
        return False, "清 unit_contact 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.COOPERATIVE_UNIT WHERE REMARK = ? OR UNIT_NAME LIKE ?",
        [TEST_MARK, f"{TEST_MARK}%"])
    if not ok:
        return False, "清 unit 失败: " + str(r)
    # 4. sys_user_role / sys_user
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID IN (?, ?, ?)",
        [RES_USER_ID, SCI_USER_ID, LEADER_B_USER_ID])
    if not ok:
        return False, "清 user_role 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?)",
        [RES_USER_ID, SCI_USER_ID, LEADER_B_USER_ID])
    if not ok:
        return False, "清 user 失败: " + str(r)
    return True, "ok"


# ============================================================
#  辅助：建合作单位 / 课题 / contract add
# ============================================================

def _find_unit_id(sess, unit_name: str, parent_id: int) -> Optional[int]:
    r = http(sess, "GET", "/biz/unit/list", params={"unitName": unit_name})
    b = safe_json(r)
    arr = b.get("data", []) if isinstance(b, dict) else []
    candidates = [x for x in arr if x.get("unitName") == unit_name
                  and (x.get("parentId") or 0) == parent_id]
    if not candidates:
        return None
    return candidates[-1].get("unitId")


def add_unit(sess, name: str, parent_id: int = 0) -> Tuple[Optional[int], Dict[str, Any]]:
    body = {
        "unitName": name,
        "parentId": parent_id,
        "externalUnitType": "COMPANY",
        "unitType": "EXTERNAL",
    }
    if parent_id == 0:
        body["companyType"] = "GENERAL"
        body["companyCategory"] = "PRIVATE"
        body["expertise"] = f"{name}-行业领域"
    body["remark"] = TEST_MARK
    r = http(sess, "POST", "/biz/unit", json_body=body)
    b = safe_json(r)
    uid = _find_unit_id(sess, name, parent_id) if isinstance(b, dict) and b.get("code") == 200 else None
    return uid, {"body": body, "resp": b, "unitId": uid}


def add_project(sess, name: str, leader_id: int) -> Tuple[Optional[int], Dict[str, Any]]:
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {
        "projectName": name,
        "projectType": "NATIONAL",
        "leaderId": leader_id,
        "projectNo": f"KY-T4C-{(STATE['no_seq'] % 900) + 100:03d}",
        "projectCategory": "A",
        "specialty": "Y",
        "remark": TEST_MARK,
    }
    r = http(sess, "POST", "/biz/project", json_body=body)
    b = safe_json(r)
    data = get_data(b)
    pid = data.get("projectId") if isinstance(data, dict) else None
    return pid, {"body": body, "resp": b, "projectId": pid}


def find_contract_id(sess, contract_no: str) -> Optional[int]:
    """合同 add 不返 id；用 list 按 contractNo 精确查。"""
    r = http(sess, "GET", "/biz/contract/list",
             params={"contractNo": contract_no, "pageNum": 1, "pageSize": 5})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    if rows:
        return rows[-1].get("contractId")
    return None


def find_node_id(sess, contract_id: int, node_name: str) -> Optional[int]:
    r = http(sess, "GET", "/biz/contract/node/list", params={"contractId": contract_id})
    b = safe_json(r)
    arr = get_data(b)
    if isinstance(arr, list):
        matches = [x for x in arr if x.get("nodeName") == node_name]
        if matches:
            return matches[-1].get("nodeId")
    return None


def sleep_anti_repeat(sec: float = 2.5) -> None:
    """合同/节点接口有 @RepeatSubmit(interval=2000)，串行调用时停顿。"""
    time.sleep(sec)


# ============================================================
#  Case 01：新增合同（挂 DRAFT 课题 + party 选合作单位）成功；
#           party_name=单位名快照；contract_no 重复被拒
# ============================================================

def case_01_add_with_unit_and_dup_no(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}

    # 1) 准备：单位 + 课题
    uid, info = add_unit(sess, f"{TEST_MARK}-供应商A")
    if uid is None:
        return {"unit": info}, False, "建单位失败"
    out["unit"] = info

    pid, info2 = add_project(sess, f"{TEST_MARK}-A课题-合同挂载", leader_id=1)  # leader=admin
    if pid is None:
        return {"unit": info, "project": info2}, False, "建课题失败"
    out["project"] = info2
    STATE["project_a_id"] = pid
    STATE["unit_a_id"] = uid

    # 2) 新增合同（选单位）
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    contract_no = f"HT-T4C-{(STATE['no_seq'] % 900) + 100:03d}"
    STATE["contract_a_no"] = contract_no
    body = {
        "projectId": pid,
        "contractNo": contract_no,
        "contractName": f"{TEST_MARK}-A合同",
        "contractType": "RESEARCH",
        "amount": "100000.00",
        "signDate": "2026-08-01",
        "startDate": "2026-08-01",
        "expireDate": "2027-08-01",
        "status": "ACTIVE",
        "partyUnitId": uid,
        "partyName": "【应被覆盖为快照】",  # body 传值应被忽略
        "remark": TEST_MARK,
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/contract", json_body=body)
    b = safe_json(r)
    add_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["add"] = {"body": body, "status_code": r.status_code, "resp": b}
    if not add_ok:
        return out, False, f"新增合同失败: {str(b)[:200]}"

    # 3) DB 验证 party_name=单位名快照
    cid = find_contract_id(sess, contract_no)
    if cid is None:
        return out, False, "未取到 contractId"
    STATE["contract_a_id"] = cid
    ok, res = db_query(
        "SELECT c.CONTRACT_NO, c.PARTY_UNIT_ID, c.PARTY_NAME, c.STATUS, c.DEL_FLAG, u.UNIT_NAME "
        "FROM RUOYI.CONTRACT c LEFT JOIN RUOYI.COOPERATIVE_UNIT u "
        "  ON c.PARTY_UNIT_ID = u.UNIT_ID WHERE c.CONTRACT_ID = ?", [cid])
    rows = res["rows"] if ok else []
    db_no = rows[0][0] if rows else None
    db_puid = rows[0][1] if rows else None
    db_pname = rows[0][2] if rows else None
    db_status = rows[0][3] if rows else None
    db_delflag = rows[0][4] if rows else None
    db_unit_name = rows[0][5] if rows else None
    snapshot_ok = (db_pname == db_unit_name and db_unit_name is not None
                   and db_pname != "【应被覆盖为快照】")
    out["db_snapshot"] = {
        "contract_no": db_no, "party_unit_id": db_puid, "party_name": db_pname,
        "status": db_status, "del_flag": db_delflag, "unit_name": db_unit_name,
        "snapshot_match": snapshot_ok,
    }

    # 4) contract_no 重复被拒（Service 查重）
    dup_body = dict(body)
    dup_body["contractName"] = f"{TEST_MARK}-重复编号合同"
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/contract", json_body=dup_body)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    dup_ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
              and "合同编号已存在" in msg)
    out["dup_no_reject"] = {"body": dup_body, "status_code": r.status_code, "resp": b, "msg": msg}

    ok = add_ok and snapshot_ok and dup_ok
    return out, ok, "" if ok else f"add={add_ok} snapshot={snapshot_ok} dup={dup_ok}"


# ============================================================
#  Case 02：party 手工填名称（partyUnitId=null）成功；
#           partyUnitId 与 partyName 都空被拒
# ============================================================

def case_02_party_manual_and_reject(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    pid = STATE["project_a_id"]

    # 2.1 手填成功
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    manual_no = f"HT-T4C-M{(STATE['no_seq'] % 900) + 100:03d}"
    manual_body = {
        "projectId": pid,
        "contractNo": manual_no,
        "contractName": f"{TEST_MARK}-手填合同",
        "contractType": "SERVICE",
        "amount": "50000.00",
        "partyUnitId": None,
        "partyName": "未建档-某某公司",
        "remark": TEST_MARK,
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/contract", json_body=manual_body)
    b = safe_json(r)
    manual_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["manual_ok"] = {"body": manual_body, "status_code": r.status_code, "resp": b}
    if manual_ok:
        cid = find_contract_id(sess, manual_no)
        STATE["contract_manual_id"] = cid
        if cid is not None:
            ok2, res = db_query(
                "SELECT PARTY_UNIT_ID, PARTY_NAME FROM RUOYI.CONTRACT WHERE CONTRACT_ID = ?", [cid])
            rows = res["rows"] if ok2 else []
            db_puid = rows[0][0] if rows else None
            db_pname = rows[0][1] if rows else None
            manual_db_ok = db_puid is None and db_pname == "未建档-某某公司"
            out["manual_db"] = {"party_unit_id": db_puid, "party_name": db_pname, "ok": manual_db_ok}
        else:
            manual_db_ok = False
            out["manual_db"] = {"err": "未取到 contractId"}
    else:
        manual_db_ok = False

    # 2.2 都空被拒
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    empty_no = f"HT-T4C-E{(STATE['no_seq'] % 900) + 100:03d}"
    empty_body = {
        "projectId": pid,
        "contractNo": empty_no,
        "contractName": f"{TEST_MARK}-空对方合同",
        "contractType": "SERVICE",
        "partyUnitId": None,
        "partyName": "",
        "remark": TEST_MARK,
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/contract", json_body=empty_body)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    empty_reject = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                    and "对方名称不能为空" in msg)
    out["empty_reject"] = {"body": empty_body, "status_code": r.status_code, "resp": b, "msg": msg}

    ok = manual_ok and manual_db_ok and empty_reject
    return out, ok, "" if ok else (
    f"manual={manual_ok} db={manual_db_ok} empty_reject={empty_reject}")


# ============================================================
#  Case 03：节点 CRUD：PAYMENT/DELIVERY/ACCEPTANCE 各建一个，改名/删除正常
# ============================================================

def case_03_node_crud(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    cid = STATE["contract_a_id"]

    def add_node(name: str, node_type: str, plan_date: str) -> Dict[str, Any]:
        body = {
            "contractId": cid,
            "nodeName": name,
            "nodeType": node_type,
            "planDate": plan_date,
            "remark": TEST_MARK,
        }
        sleep_anti_repeat()
        r = http(sess, "POST", "/biz/contract/node", json_body=body)
        b = safe_json(r)
        nid = find_node_id(sess, cid, name) if isinstance(b, dict) and b.get("code") == 200 else None
        return {"body": body, "status_code": r.status_code, "resp": b, "nodeId": nid}

    n_pay = add_node(f"{TEST_MARK}-付款节点", "PAYMENT", "2026-12-31")
    n_del = add_node(f"{TEST_MARK}-交付节点", "DELIVERY", "2027-06-30")
    n_acc = add_node(f"{TEST_MARK}-验收节点", "ACCEPTANCE", "2027-09-30")
    STATE["node_payment_id"] = n_pay["nodeId"]
    STATE["node_delivery_id"] = n_del["nodeId"]
    STATE["node_acceptance_id"] = n_acc["nodeId"]

    created_ok = (n_pay["nodeId"] is not None and n_del["nodeId"] is not None
                  and n_acc["nodeId"] is not None)

    # list 验证 status 强制 PENDING
    r = http(sess, "GET", "/biz/contract/node/list", params={"contractId": cid})
    b = safe_json(r)
    arr = get_data(b)
    list_ok = (isinstance(arr, list) and len(arr) == 3
               and all(x.get("status") == "PENDING" for x in arr))
    out["add_pay"] = n_pay
    out["add_del"] = n_del
    out["add_acc"] = n_acc
    out["list"] = {"status_code": r.status_code, "resp": b, "data_is_array": isinstance(arr, list),
                   "count": len(arr) if isinstance(arr, list) else None,
                   "all_pending": list_ok if isinstance(arr, list) else False}

    # 改名：PAYMENT 改名 + 改 plan_date
    if n_pay["nodeId"]:
        upd = {"nodeId": n_pay["nodeId"], "nodeName": f"{TEST_MARK}-付款节点-改名",
               "nodeType": "PAYMENT", "planDate": "2026-12-15", "contractId": cid,
               "remark": TEST_MARK}
        sleep_anti_repeat()
        r = http(sess, "PUT", "/biz/contract/node", json_body=upd)
        b = safe_json(r)
        upd_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
        ok2, res = db_query(
            "SELECT NODE_NAME, PLAN_DATE FROM RUOYI.CONTRACT_NODE WHERE NODE_ID = ?",
            [n_pay["nodeId"]])
        rows = res["rows"] if ok2 else []
        db_name = rows[0][0] if rows else None
        db_plan = rows[0][1] if rows else None
        db_plan_str = db_plan.strftime("%Y-%m-%d") if hasattr(db_plan, "strftime") else str(db_plan)[:10]
        db_upd_ok = db_name == f"{TEST_MARK}-付款节点-改名" and db_plan_str == "2026-12-15"
        out["update"] = {"body": upd, "status_code": r.status_code, "resp": b,
                         "db_name": db_name, "db_plan_date": db_plan_str, "db_ok": db_upd_ok}
    else:
        upd_ok, db_upd_ok = False, False
        out["update"] = {"skipped": True}

    # 删除：DELIVERY
    if n_del["nodeId"]:
        sleep_anti_repeat()
        r = http(sess, "DELETE", f"/biz/contract/node/{n_del['nodeId']}")
        b = safe_json(r)
        del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
        ok3, res = db_query(
            "SELECT DEL_FLAG FROM RUOYI.CONTRACT_NODE WHERE NODE_ID = ?", [n_del["nodeId"]])
        row = res["rows"][0] if ok3 and res["rows"] else None
        db_del = row[0] if row else None
        db_del_ok = db_del == "2"
        out["delete"] = {"id": n_del["nodeId"], "status_code": r.status_code, "resp": b,
                         "db_del_flag": db_del, "db_ok": db_del_ok}
    else:
        del_ok, db_del_ok = False, False
        out["delete"] = {"skipped": True}

    ok = created_ok and list_ok and upd_ok and db_upd_ok and del_ok and db_del_ok
    return out, ok, "" if ok else (
        f"create={created_ok} list={list_ok} upd={upd_ok} db_upd={db_upd_ok} del={del_ok} db_del={db_del_ok}")


# ============================================================
#  Case 04：完成动作：actualDate+voucherUrl → status=DONE；
#           已 DONE 再 finish 被拒；DONE 节点 update 改 plan_date 应被忽略，
#           改 remark 生效
# ============================================================

def case_04_finish_node(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    nid = STATE["node_payment_id"]
    if nid is None:
        return {"err": "无 node_payment_id"}, False, "前置失败"

    # 4.1 finish
    body = {"nodeId": nid, "actualDate": "2026-12-20", "voucherUrl": "/upload/voucher/abc123.pdf"}
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/contract/node/finish", json_body=body)
    b = safe_json(r)
    finish_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["finish"] = {"body": body, "status_code": r.status_code, "resp": b}
    if not finish_ok:
        return out, False, "finish 失败: " + str(b)[:200]

    # DB 验证 status=DONE + actual_date + voucher_url
    ok2, res = db_query(
        "SELECT STATUS, ACTUAL_DATE, VOUCHER_URL FROM RUOYI.CONTRACT_NODE WHERE NODE_ID = ?", [nid])
    rows = res["rows"] if ok2 else []
    if rows:
        db_status, db_actual, db_voucher = rows[0]
        db_actual_str = db_actual.strftime("%Y-%m-%d") if hasattr(db_actual, "strftime") else str(db_actual)[:10]
        finish_db_ok = (db_status == "DONE" and db_actual_str == "2026-12-20"
                        and db_voucher == "/upload/voucher/abc123.pdf")
    else:
        finish_db_ok = False
        db_status, db_actual_str, db_voucher = None, None, None
    out["finish_db"] = {"status": db_status, "actual_date": db_actual_str,
                        "voucher_url": db_voucher, "ok": finish_db_ok}

    # 4.2 已 DONE 再 finish 被拒
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/contract/node/finish",
             json_body={"nodeId": nid, "actualDate": "2026-12-21", "voucherUrl": "/x.pdf"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    dup_reject = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                  and ("已完成" in msg or "状态已变更" in msg))
    out["dup_finish_reject"] = {"body": {"nodeId": nid, "actualDate": "2026-12-21"},
                                 "status_code": r.status_code, "resp": b, "msg": msg}

    # 4.3 DONE 节点 update 改 plan_date 应被忽略；改 remark 生效
    upd = {"nodeId": nid, "nodeName": f"{TEST_MARK}-付款节点-尝试改名",
           "nodeType": "DELIVERY", "planDate": "2030-01-01", "contractId": STATE["contract_a_id"],
           "remark": f"{TEST_MARK}-DONE改备注生效"}
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/contract/node", json_body=upd)
    b = safe_json(r)
    upd_api_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    # DB 直查
    ok3, res = db_query(
        "SELECT NODE_NAME, NODE_TYPE, PLAN_DATE, REMARK FROM RUOYI.CONTRACT_NODE WHERE NODE_ID = ?",
        [nid])
    rows = res["rows"] if ok3 else []
    if rows:
        db_name2, db_type2, db_plan2, db_remark2 = rows[0]
        db_plan2_str = db_plan2.strftime("%Y-%m-%d") if hasattr(db_plan2, "strftime") else str(db_plan2)[:10]
    else:
        db_name2 = db_type2 = db_plan2_str = db_remark2 = None
    plan_unchanged = db_plan2_str == "2026-12-15"  # 改名时的原 plan_date（case_03 改名后）
    remark_changed = db_remark2 == f"{TEST_MARK}-DONE改备注生效"
    name_unchanged = db_name2 == f"{TEST_MARK}-付款节点-改名"  # 改名不应生效
    type_unchanged = db_type2 == "PAYMENT"
    out["done_update"] = {
        "body": upd, "status_code": r.status_code, "resp": b, "api_ok": upd_api_ok,
        "db": {"name": db_name2, "type": db_type2, "plan_date": db_plan2_str, "remark": db_remark2},
        "checks": {"plan_unchanged": plan_unchanged, "remark_changed": remark_changed,
                   "name_unchanged": name_unchanged, "type_unchanged": type_unchanged}
    }
    done_update_ok = upd_api_ok and plan_unchanged and remark_changed and name_unchanged and type_unchanged

    ok = finish_ok and finish_db_ok and dup_reject and done_update_ok
    return out, ok, "" if ok else (
        f"finish={finish_ok} finish_db={finish_db_ok} dup_reject={dup_reject} done_update={done_update_ok}")


# ============================================================
#  Case 05：逾期标志：建 plan_date=昨天 的 PENDING 节点 → overdue=true；
#           今天及未来的 PENDING → overdue=false
# ============================================================

def case_05_overdue_flag(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    cid = STATE["contract_a_id"]
    if cid is None:
        return {"err": "无 contract_a_id"}, False, "前置失败"

    today = date.today()
    yesterday = today - timedelta(days=1)
    tomorrow = today + timedelta(days=1)
    far_future = today + timedelta(days=30)

    def add_overdue_node(name: str, plan_date: date) -> Optional[int]:
        body = {"contractId": cid, "nodeName": name, "nodeType": "DELIVERY",
                "planDate": plan_date.isoformat(), "remark": TEST_MARK}
        sleep_anti_repeat()
        r = http(sess, "POST", "/biz/contract/node", json_body=body)
        b = safe_json(r)
        if isinstance(b, dict) and b.get("code") == 200:
            return find_node_id(sess, cid, name)
        return None

    nid_yest = add_overdue_node(f"{TEST_MARK}-逾期节点-昨天", yesterday)
    nid_today = add_overdue_node(f"{TEST_MARK}-今天节点", today)
    nid_future = add_overdue_node(f"{TEST_MARK}-未来节点", far_future)

    # 查 list 校验 overdue
    r = http(sess, "GET", "/biz/contract/node/list", params={"contractId": cid})
    b = safe_json(r)
    arr = get_data(b)
    nodes = {x.get("nodeName"): x for x in (arr if isinstance(arr, list) else [])}
    overdue_yest = nodes.get(f"{TEST_MARK}-逾期节点-昨天", {}).get("overdue")
    overdue_today = nodes.get(f"{TEST_MARK}-今天节点", {}).get("overdue")
    overdue_future = nodes.get(f"{TEST_MARK}-未来节点", {}).get("overdue")
    # 期望：昨天=True(1), 今天/未来=False(0)；达梦 boolean 输出 1/0
    overdue_yest_ok = overdue_yest in (True, 1, "1")
    overdue_today_ok = overdue_today in (False, 0, "0", None)
    overdue_future_ok = overdue_future in (False, 0, "0", None)
    out["nodes"] = {
        "yesterday_node_id": nid_yest, "today_node_id": nid_today, "future_node_id": nid_future,
        "list_data_sample": {k: {"planDate": v.get("planDate"), "status": v.get("status"),
                                  "overdue": v.get("overdue")}
                              for k, v in nodes.items()},
    }
    out["create_yest_ok"] = nid_yest is not None
    out["create_today_ok"] = nid_today is not None
    out["create_future_ok"] = nid_future is not None

    ok = (nid_yest is not None and nid_today is not None and nid_future is not None
          and overdue_yest_ok and overdue_today_ok and overdue_future_ok)
    return out, ok, "" if ok else (
        f"yest_node={nid_yest} today_node={nid_today} future_node={nid_future} "
        f"yest_overdue={overdue_yest} today_overdue={overdue_today} future_overdue={overdue_future}")


# ============================================================
#  Case 06：researcher 数据权限：登录只见本人相关课题的合同；
#           越权合同 GET /{id} 返回"无权访问"
# ============================================================

def case_06_researcher_data_scope(sess_admin, sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}

    # 6.1 admin 建：项目 A（leader=researcher）+ 合同 A_c；项目 B（leader=other）+ 合同 B_c
    pid_a, info_a = add_project(sess_admin, f"{TEST_MARK}-ResA课题", leader_id=RES_USER_ID)
    out["project_res_a"] = info_a
    pid_b, info_b = add_project(sess_admin, f"{TEST_MARK}-ResB课题", leader_id=LEADER_B_USER_ID)
    out["project_res_b"] = info_b
    if pid_a is None or pid_b is None:
        return out, False, "造 Res 课题失败"

    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    a_no = f"HT-T4C-RA{(STATE['no_seq'] % 900) + 100:03d}"
    b_no = f"HT-T4C-RB{(STATE['no_seq'] % 900) + 100:03d}"
    body_a = {"projectId": pid_a, "contractNo": a_no, "contractName": f"{TEST_MARK}-ResA合同",
              "contractType": "RESEARCH", "amount": "10000", "partyUnitId": None,
              "partyName": "对方A", "remark": TEST_MARK}
    body_b = {"projectId": pid_b, "contractNo": b_no, "contractName": f"{TEST_MARK}-ResB合同",
              "contractType": "RESEARCH", "amount": "20000", "partyUnitId": None,
              "partyName": "对方B", "remark": TEST_MARK}
    sleep_anti_repeat()
    r = http(sess_admin, "POST", "/biz/contract", json_body=body_a)
    b = safe_json(r)
    out["add_res_a"] = {"body": body_a, "resp": b}
    a_ok = isinstance(b, dict) and b.get("code") == 200
    sleep_anti_repeat()
    r = http(sess_admin, "POST", "/biz/contract", json_body=body_b)
    b = safe_json(r)
    out["add_res_b"] = {"body": body_b, "resp": b}
    b_ok = isinstance(b, dict) and b.get("code") == 200
    cid_a = find_contract_id(sess_admin, a_no)
    cid_b = find_contract_id(sess_admin, b_no)
    STATE["contract_res_a_id"] = cid_a
    STATE["contract_res_b_id"] = cid_b

    if not (a_ok and b_ok and cid_a is not None and cid_b is not None):
        return out, False, f"建 Res 合同失败 a={a_ok} b={b_ok} cida={cid_a} cidb={cid_b}"

    # 6.2 researcher 登录 + list
    token, resp = login(sess_res, RES_USERNAME, ADMIN_PASS)
    if not token:
        return {"login_resp": resp}, False, "researcher 登录失败"
    sess_res.headers.update({"Authorization": "Bearer " + token})
    out["login_res"] = {"status_code": resp.get("status_code"), "body": resp.get("body")}

    r = http(sess_res, "GET", "/biz/contract/list", params={"pageNum": 1, "pageSize": 100})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    ids = [x.get("contractId") for x in rows]
    seen_a = cid_a in ids
    seen_b = cid_b in ids
    out["res_list"] = {"status_code": r.status_code, "rows": [{"contractId": x.get("contractId"),
                                                                "contractNo": x.get("contractNo"),
                                                                "contractName": x.get("contractName")}
                                                               for x in rows],
                       "seen_a": seen_a, "seen_b": seen_b, "total": b.get("total")}

    # 6.3 researcher 详情 A（应 200）
    r = http(sess_res, "GET", f"/biz/contract/{cid_a}")
    b = safe_json(r)
    detail_a_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["res_detail_a"] = {"status_code": r.status_code, "resp": b}

    # 6.4 researcher 详情 B（应无权访问）
    r = http(sess_res, "GET", f"/biz/contract/{cid_b}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    detail_b_forbid = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                       and "无权访问" in msg)
    out["res_detail_b_forbid"] = {"status_code": r.status_code, "resp": b, "msg": msg}

    ok = seen_a and not seen_b and detail_a_ok and detail_b_forbid
    return out, ok, "" if ok else (
        f"seen_a={seen_a} seen_b={seen_b} detail_a={detail_a_ok} detail_b_forbid={detail_b_forbid}")


# ============================================================
#  Case 07：删除合同：级联逻辑删节点（DB 直查 contract.del_flag='2'
#           且其节点全部 del_flag='2'）
# ============================================================

def case_07_delete_contract_cascade(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    cid = STATE["contract_a_id"]
    if cid is None:
        return {"err": "无 contract_a_id"}, False, "前置失败"

    # 取当前未删节点数
    ok, res = db_query(
        "SELECT COUNT(*) FROM RUOYI.CONTRACT_NODE WHERE CONTRACT_ID = ? AND DEL_FLAG = '0'", [cid])
    before_active = res["rows"][0][0] if ok else None

    sleep_anti_repeat()
    r = http(sess, "DELETE", f"/biz/contract/{cid}")
    b = safe_json(r)
    del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["delete"] = {"status_code": r.status_code, "resp": b}

    # DB 直查 contract.del_flag
    ok2, res2 = db_query(
        "SELECT DEL_FLAG FROM RUOYI.CONTRACT WHERE CONTRACT_ID = ?", [cid])
    contract_del = res2["rows"][0][0] if ok2 and res2["rows"] else None
    # DB 直查所有节点 del_flag（应全部 '2'，无论前后）
    ok3, res3 = db_query(
        "SELECT NODE_ID, DEL_FLAG FROM RUOYI.CONTRACT_NODE WHERE CONTRACT_ID = ?", [cid])
    node_flags = res3["rows"] if ok3 else []
    all_node_del = all(r[1] == "2" for r in node_flags) if node_flags else False
    active_after = sum(1 for r in node_flags if r[1] == "0")

    out["db"] = {"contract_del_flag": contract_del, "before_active": before_active,
                 "all_node_del": all_node_del, "active_after": active_after,
                 "node_count_total": len(node_flags)}

    # DB 唯一索引兜底：绕过 Service，dmPython 直接 INSERT 重复编号应被拒
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    dup_no = f"HT-T4C-DUP{(STATE['no_seq'] % 900) + 100:03d}"
    # 先正常建一条
    pid = STATE["project_a_id"]
    body_ok = {"projectId": pid, "contractNo": dup_no, "contractName": "DUP唯一性兜底-1",
               "contractType": "RESEARCH", "partyUnitId": None, "partyName": "X",
               "remark": TEST_MARK}
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/contract", json_body=body_ok)
    b = safe_json(r)
    base_ok = isinstance(b, dict) and b.get("code") == 200
    out["unique_setup"] = {"resp": b, "ok": base_ok}

    # 用 dmPython 直接 INSERT 重复编号（绕过 Service 查重）
    if base_ok:
        ok_dup, dup_res = db_execute(
            "INSERT INTO RUOYI.CONTRACT (CONTRACT_NO, PROJECT_ID, CONTRACT_NAME, CONTRACT_TYPE, "
            "PARTY_NAME, STATUS, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
            "VALUES (?, ?, 'DUP唯一性兜底-2', 'RESEARCH', 'X', 'ACTIVE', '0', 'admin', SYSDATE, ?)",
            [dup_no, pid, TEST_MARK])
        # 期望：失败（唯一索引拒绝）。返回 rowcount=-3 或 False
        out["unique_bypass"] = {"ok": ok_dup, "res": dup_res}
        unique_reject = not ok_dup
    else:
        unique_reject = False
        out["unique_bypass"] = {"skipped": "前置失败"}

    ok = del_ok and contract_del == "2" and all_node_del and active_after == 0 and unique_reject
    return out, ok, "" if ok else (
        f"del={del_ok} contract_del={contract_del} all_node_del={all_node_del} "
        f"active_after={active_after} unique_reject={unique_reject}")


# ============================================================
#  Case 08：回归 smoke_project + smoke_coop_unit 关键子集不退化
# ============================================================

def case_08_regression(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}

    # 8.1 课题 list
    r = http(sess, "GET", "/biz/project/list", params={"pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    list_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["project_list"] = {"status_code": r.status_code, "total": b.get("total")}

    # 8.2 合作单位 list
    r = http(sess, "GET", "/biz/unit/list", params={"pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    unit_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["unit_list"] = {"status_code": r.status_code, "total": b.get("total")}

    # 8.3 合同 list（说明合同模块就绪）
    r = http(sess, "GET", "/biz/contract/list", params={"pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    contract_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["contract_list"] = {"status_code": r.status_code, "total": b.get("total")}

    ok = list_ok and unit_ok and contract_ok
    return out, ok, "" if ok else f"project_list={list_ok} unit_list={unit_ok} contract_list={contract_ok}"


# ============================================================
#  附加 A：显式 null 清空
# ============================================================

def case_a_explicit_null_clear(sess) -> Tuple[Dict[str, Any], bool]:
    """先建带 partyUnitId 的合同，再 PUT body 含 `"partyUnitId": null, "partyName": "手填名"`
    → DB party_unit_id 变 NULL；同样验证 `"fileUrl": null` 清空。"""
    out: Dict[str, Any] = {}

    # 准备：先建一个带单位的合同
    pid = STATE["project_a_id"]
    uid = STATE["unit_a_id"]
    if pid is None or uid is None:
        return {"err": "前置 state 缺失"}, False, "前置缺失"

    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    no_a = f"HT-T4C-A{(STATE['no_seq'] % 900) + 100:03d}"
    body = {"projectId": pid, "contractNo": no_a, "contractName": f"{TEST_MARK}-Null清空测试",
            "contractType": "RESEARCH", "partyUnitId": uid,
            "partyName": "初始", "fileUrl": "/upload/initial.pdf", "remark": TEST_MARK}
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/contract", json_body=body)
    b = safe_json(r)
    if not (isinstance(b, dict) and b.get("code") == 200):
        return {"add": b}, False, "建初始合同失败"
    cid = find_contract_id(sess, no_a)
    STATE["contract_null_id"] = cid
    out["add"] = {"body": body, "resp": b, "contractId": cid}

    # PUT 显式 null 清空 partyUnitId + 改 partyName；同时 fileUrl=null 清空
    upd = {"contractId": cid, "partyUnitId": None, "partyName": f"{TEST_MARK}-手填名",
           "fileUrl": None}
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/contract", json_body=upd)
    b = safe_json(r)
    upd_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["update"] = {"body": upd, "status_code": r.status_code, "resp": b}

    # DB 验证
    ok2, res = db_query(
        "SELECT PARTY_UNIT_ID, PARTY_NAME, FILE_URL FROM RUOYI.CONTRACT WHERE CONTRACT_ID = ?", [cid])
    rows = res["rows"] if ok2 else []
    if rows:
        db_puid, db_pname, db_file = rows[0]
    else:
        db_puid, db_pname, db_file = "MISSING", "MISSING", "MISSING"
    party_unit_cleared = db_puid is None
    party_name_set = db_pname == f"{TEST_MARK}-手填名"
    file_url_cleared = db_file is None
    out["db"] = {"party_unit_id": db_puid, "party_name": db_pname, "file_url": db_file,
                 "party_unit_cleared": party_unit_cleared,
                 "party_name_set": party_name_set, "file_url_cleared": file_url_cleared}

    ok = upd_ok and party_unit_cleared and party_name_set and file_url_cleared
    return out, ok, "" if ok else (
        f"upd={upd_ok} puid={party_unit_cleared} pname={party_name_set} file={file_url_cleared}")


# ============================================================
#  附加 B：节点列表契约
# ============================================================

def case_b_node_list_contract(sess) -> Tuple[Dict[str, Any], bool]:
    """GET /biz/contract/node/list?contractId=x 响应结构 {code,msg,data:[...]}
    （data 是数组非分页 rows）"""
    out: Dict[str, Any] = {}
    cid = STATE["contract_a_id"]
    if cid is None:
        return {"err": "无 contract_a_id"}, False, "前置缺失"
    r = http(sess, "GET", "/biz/contract/node/list", params={"contractId": cid})
    b = safe_json(r)
    out["raw_status_code"] = r.status_code
    out["raw_body_type"] = str(type(b).__name__)
    code_ok = isinstance(b, dict) and b.get("code") == 200
    data = b.get("data") if isinstance(b, dict) else None
    is_array = isinstance(data, list)
    has_rows = "rows" not in b  # 确认不是 TableDataInfo 形态
    out["body_keys"] = list(b.keys()) if isinstance(b, dict) else None
    out["data_is_array"] = is_array
    out["data_count"] = len(data) if is_array else None
    out["no_rows_key"] = has_rows
    out["code_ok"] = code_ok
    ok = code_ok and is_array and has_rows
    return out, ok, "" if ok else f"code={code_ok} array={is_array} no_rows={has_rows}"


# ============================================================
#  附加 C：contract_no 唯一索引兜底（dmPython INSERT 重复）
# ============================================================

def case_c_unique_index_db_guard(sess) -> Tuple[Dict[str, Any], bool]:
    """绕 Service 直接 dmPython INSERT 重复编号应被 DB 拒（验证 idx_contract_no_uk）。"""
    out: Dict[str, Any] = {}
    pid = STATE["project_a_id"]
    if pid is None:
        return {"err": "前置缺失"}, False, "前置缺失"

    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    dup_no = f"HT-T4C-IX{(STATE['no_seq'] % 900) + 100:03d}"
    # 1) 先正常 INSERT 一条（contract 表无 TENANT_ID 列，按 schema 显式列）
    ok1, r1 = db_execute(
        "INSERT INTO RUOYI.CONTRACT (CONTRACT_NO, PROJECT_ID, CONTRACT_NAME, CONTRACT_TYPE, "
        "PARTY_NAME, STATUS, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES (?, ?, '唯一索引兜底-1', 'RESEARCH', 'X', 'ACTIVE', '0', 'admin', SYSDATE, ?)",
        [dup_no, pid, TEST_MARK])
    out["first_insert"] = {"ok": ok1, "res": r1, "no": dup_no}
    if not ok1:
        return out, False, "首次 INSERT 失败"

    # 2) 直接 INSERT 重复编号（不同 contract_id），期望被唯一索引拒绝
    ok2, r2 = db_execute(
        "INSERT INTO RUOYI.CONTRACT (CONTRACT_NO, PROJECT_ID, CONTRACT_NAME, CONTRACT_TYPE, "
        "PARTY_NAME, STATUS, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES (?, ?, '唯一索引兜底-2', 'RESEARCH', 'X', 'ACTIVE', '0', 'admin', SYSDATE, ?)",
        [dup_no, pid, TEST_MARK])
    out["dup_insert"] = {"ok": ok2, "res": r2}
    dup_rejected = not ok2

    # 清理（物理删，避免留污染）
    db_execute("DELETE FROM RUOYI.CONTRACT WHERE CONTRACT_NO = ?", [dup_no])

    ok = ok1 and dup_rejected
    return out, ok, "" if ok else f"first={ok1} dup_rejected={dup_rejected}"


# ============================================================
#  main
# ============================================================

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

    # 清残留（幂等）
    cleanup_test_data()

    # 主会话：admin（覆盖所有合同权限）
    sess = requests.Session()
    sess.headers.update({"User-Agent": "task4-contract-smoke/1.0"})
    token, login_resp = login(sess, ADMIN_USER, ADMIN_PASS)
    if not token:
        record("10_login_admin", False, {"username": ADMIN_USER}, login_resp, "admin 登录失败")
        dump_results()
        return 1
    sess.headers.update({"Authorization": "Bearer " + token})
    record("10_login_admin", True, {"username": ADMIN_USER},
           {"status_code": login_resp.get("status_code")})

    # 准备 test_researcher/leader_b 用户（清理时一并删）
    ok, msg = setup_test_users()
    record("05_setup_test_users", ok, {"target": "sys_user+sys_user_role"}, {"msg": msg}, msg)

    # 准备 researcher 视角的独立 session
    sess_res = requests.Session()
    sess_res.headers.update({"User-Agent": "task4-contract-smoke/1.0"})

    cases = [
        ("01_add_with_unit_and_dup_no", lambda: case_01_add_with_unit_and_dup_no(sess)),
        ("02_party_manual_and_reject", lambda: case_02_party_manual_and_reject(sess)),
        ("03_node_crud", lambda: case_03_node_crud(sess)),
        ("04_finish_node", lambda: case_04_finish_node(sess)),
        ("05_overdue_flag", lambda: case_05_overdue_flag(sess)),
        # a/b/c 必须在 case_07 之前跑——b/c 依赖 contract_a_id，case_07 会级联逻辑删除
        ("a_explicit_null_clear", lambda: case_a_explicit_null_clear(sess)),
        ("b_node_list_contract", lambda: case_b_node_list_contract(sess)),
        ("c_unique_index_db_guard", lambda: case_c_unique_index_db_guard(sess)),
        ("06_researcher_data_scope", lambda: case_06_researcher_data_scope(sess, sess_res)),
        ("07_delete_contract_cascade", lambda: case_07_delete_contract_cascade(sess)),
        ("08_regression", lambda: case_08_regression(sess)),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/contract/*"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # 收尾
    cleanup_ok, cleanup_msg = cleanup_test_data()
    record("99_cleanup", cleanup_ok, {"method": "db"}, {"msg": cleanup_msg})

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())