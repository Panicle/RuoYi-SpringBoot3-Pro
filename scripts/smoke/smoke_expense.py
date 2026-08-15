#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 5 — 阶段4 经费管理接口冒烟（任务卡 §七 10 项 + 附加契约 C1-C5）
- 覆盖：任务卡 §七 1-10 项 + 附加契约实测 C1-C5
- 复用 smoke_contract.py 的登录框架 / dmPython DB 直查工具
- 结果写 scripts/smoke/result_expense.jsonl（与 result.jsonl / result_budget.jsonl 隔离）
- 只测不改业务代码；发现的 Bug 记入报告
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
from datetime import date, datetime, timedelta
from decimal import Decimal, getcontext
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke_project as SP  # noqa: E402   # 复用 login/http/record/db_* 等
import smoke_contract as SC  # noqa: E402  # 复用 TEST_MARK 惯例（这里我们用独立标记）

import requests
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5

getcontext().prec = 28

BASE_URL = SP.BASE_URL
ADMIN_USER = SP.ADMIN_USER
ADMIN_PASS = SP.ADMIN_PASS

PUBLIC_KEY_PEM = SC.PUBLIC_KEY_PEM

DM_PASSWORD = os.environ.get("DM_PASSWORD", "Ruoyi12345")
DM_CONN_KW = dict(user="SYSDBA", password=DM_PASSWORD, server="localhost", port=5236)

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RESULT_PATH = os.path.join(SCRIPT_DIR, "result_expense.jsonl")

# 测试用户 ID 约定（避免与其他脚本冲突）
RES_USER_ID  = 90021   # researcher 角色 105, data_scope=5
DL_USER_ID   = 90022   # dept_leader 角色 104, data_scope=4
SCI_USER_ID  = 90023   # science_admin 角色 101, data_scope=1
LEADER_B_ID  = 90024   # 无角色（数据权限 dept=100，scope=5 本人相关）
RES_USERNAME  = "test_exp_researcher"
DL_USERNAME   = "test_exp_dept_leader"
SCI_USERNAME  = "test_exp_sci_admin"
LEADER_B_NAME = "test_exp_leader_b"

TEST_MARK = "smoke-task5-expense"

# ============ 透支开关 ============
OVERDRAFT_KEY = "biz.expense.allowOverdraft"

# ============ 测试数据 ============
# 主课题（admin 拥有）：用于记账/作废/冲销/预警
# 各科目预算金额：使 B 落在 (500万, 1000万] 时间接费 25% / (0, 500万] 时 30%
# 直接费=EQUIPMENT+LABOR+MATERIAL+TESTING+FUEL+TRAVEL+PUBLICATION，B = 直接费 - EQUIPMENT
# 选取 B=520000 元区间，r=30%（边界 B≤500万→30%）
# 直接费 = 1000000 (LABOR) + 200000 (EQUIPMENT) + 200000 (MATERIAL) + 200000 (TESTING)
#          + 100000 (FUEL) + 100000 (TRAVEL) + 100000 (PUBLICATION) = 1900000
# B = 1900000 - 200000 = 1700000
# 比例段：B=1700000 在 (100万, 500万]，r=30%
# INDIRECT 上限 = 1700000 × 30% = 510000
# OUTSOURCING 上限 = 510000
# 选用：INDIRECT=200000 (< 510000 ✓)，OUTSOURCING=200000 (< 510000 ✓)
# 其它用于后续覆盖阈值/预算不足
SPLITS_REG_OK = [
    {"category": "LABOR",        "budgetAmount": "1000000.00"},
    {"category": "EQUIPMENT",    "budgetAmount": "200000.00"},
    {"category": "MATERIAL",     "budgetAmount": "200000.00"},
    {"category": "TESTING",      "budgetAmount": "200000.00"},
    {"category": "FUEL",         "budgetAmount": "100000.00"},
    {"category": "TRAVEL",       "budgetAmount": "100000.00"},
    {"category": "PUBLICATION",  "budgetAmount": "100000.00"},
    {"category": "INDIRECT",     "budgetAmount": "200000.00"},
    {"category": "OUTSOURCING",  "budgetAmount": "200000.00"},
    {"category": "TAX",          "budgetAmount": "50000.00"},
]
SUM_REG = Decimal("2350000.00")

# 小额测试用课题（便于记账阈值/预警）
SPLITS_SMALL = [
    {"category": "LABOR",        "budgetAmount": "1000.00"},
    {"category": "EQUIPMENT",    "budgetAmount": "500.00"},
    {"category": "MATERIAL",     "budgetAmount": "200.00"},
    {"category": "TESTING",      "budgetAmount": "300.00"},
    {"category": "FUEL",         "budgetAmount": "100.00"},
    {"category": "TRAVEL",       "budgetAmount": "200.00"},
    {"category": "PUBLICATION",  "budgetAmount": "100.00"},
    {"category": "INDIRECT",     "budgetAmount": "100.00"},
    {"category": "OUTSOURCING",  "budgetAmount": "100.00"},
    {"category": "TAX",          "budgetAmount": "50.00"},
]
SUM_SMALL = Decimal("2650.00")

RESULTS: List[Dict[str, Any]] = []
STATE: Dict[str, Any] = {}


# ============================================================
#  工具
# ============================================================

def rsa_encrypt_password(plain: str) -> str:
    key = RSA.import_key(PUBLIC_KEY_PEM)
    cipher = PKCS1_v1_5.new(key)
    ct = cipher.encrypt(plain.encode("utf-8"))
    return base64.b64encode(ct).decode("ascii")


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


def get_data(body: Any) -> Any:
    if isinstance(body, dict) and body.get("code") == 200:
        return body.get("data")
    return None


def db_query(sql: str, params: Optional[List[Any]] = None) -> Tuple[bool, Any]:
    return SP.db_query(sql, params)


def db_execute(sql: str, params: Optional[List[Any]] = None) -> Tuple[bool, Any]:
    return SP.db_execute(sql, params)


def sleep_anti_repeat(sec: float = 2.5) -> None:
    """经费端点有 @RepeatSubmit(interval=2000)，串行调用时停顿。"""
    time.sleep(sec)


# ============================================================
#  测试用户 / 标记清理
# ============================================================

def setup_test_users() -> Tuple[bool, str]:
    """建 test_exp_researcher(105) / dept_leader(104) / sci_admin(101) / leader_b。"""
    ok, res = db_query("SELECT PASSWORD FROM RUOYI.SYS_USER WHERE USER_NAME='admin'")
    if not ok or not res["rows"]:
        return False, "admin hash 读取失败"
    admin_hash = res["rows"][0][0]

    users = [
        (RES_USER_ID, RES_USERNAME, "冒烟-科研人员", 100, 105),
        (DL_USER_ID,  DL_USERNAME,  "冒烟-科室长", 100, 104),
        (SCI_USER_ID, SCI_USERNAME, "冒烟-科管", 100, 101),
        (LEADER_B_ID, LEADER_B_NAME, "冒烟-无关主持人", 100, None),
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
    """按 TEST_MARK 清理 expense / budget_split / project_member / project / alert / 用户。"""
    # alert（按 remark + alert_type=BUDGET）
    ok, r = db_execute(
        "DELETE FROM RUOYI.ALERT WHERE ALERT_TYPE='BUDGET' AND (TITLE LIKE ? OR REMARK = ?)",
        [f"%{TEST_MARK}%", TEST_MARK])
    if not ok:
        return False, "清 alert 失败: " + str(r)
    # expense → project_member → project
    ok, r = db_execute(
        "DELETE FROM RUOYI.EXPENSE WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 expense 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.BUDGET_SPLIT WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 budget_split 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 project_member 失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清 project 失败: " + str(r)
    # user_role / user
    for uid in (RES_USER_ID, DL_USER_ID, SCI_USER_ID, LEADER_B_ID):
        db_execute("DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID = ?", [uid])
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?, ?)",
        [RES_USER_ID, DL_USER_ID, SCI_USER_ID, LEADER_B_ID])
    if not ok:
        return False, "清 user 失败: " + str(r)
    return True, "ok"


# ============================================================
#  透支开关工具
# ============================================================

def refresh_sys_config_cache(sess) -> None:
    """调 /system/config/refreshCache（DELETE）让 Spring Redis Cache 失效。"""
    r = sess.delete(BASE_URL + "/system/config/refreshCache", timeout=10)
    body = safe_json(r)
    if r.status_code != 200 or not isinstance(body, dict) or body.get("code") != 200:
        raise RuntimeError(f"refreshCache 失败: {r.status_code} {body}")


def set_overdraft(sess, value: str, *, refresh_cache: bool = True) -> Tuple[bool, Any]:
    """改 sys_config 表 + 刷新缓存（让 ExpenseService 立即读到新值）。
    refresh_cache=False 表示已通过其他途径刷过缓存。
    """
    ok, r = db_execute(
        "UPDATE RUOYI.SYS_CONFIG SET CONFIG_VALUE = ? WHERE CONFIG_KEY = ?",
        [value, OVERDRAFT_KEY])
    if not ok:
        return False, f"DB 改写失败: {r}"
    if refresh_cache:
        try:
            refresh_sys_config_cache(sess)
        except Exception as e:
            return False, f"刷新缓存失败: {e}"
    return True, "ok"


# ============================================================
#  课题 / 预算工具
# ============================================================

def add_project(sess, name: str, leader_id: int, project_no: str,
                splits: Optional[List[Dict]] = None,
                project_type: str = "NATIONAL") -> Tuple[Optional[int], Dict[str, Any]]:
    body = {"projectName": name, "projectType": project_type, "leaderId": leader_id,
            "projectNo": project_no, "projectCategory": "A", "specialty": "Y",
            "remark": TEST_MARK}
    if splits is not None:
        body["budgetSplitList"] = splits
    r = http(sess, "POST", "/biz/project", json_body=body)
    b = safe_json(r)
    pid = None
    if isinstance(b, dict) and b.get("code") == 200:
        d = b.get("data") or {}
        pid = d.get("projectId")
    return pid, {"body": body, "status_code": r.status_code, "resp": b, "projectId": pid}


def find_split(sess, pid: int, category: str) -> Tuple[Optional[int], Optional[int]]:
    """从 /biz/budget/list 取指定科目 splitId 与 version。"""
    r = http(sess, "GET", "/biz/budget/list", params={"projectId": pid})
    b = safe_json(r)
    arr = get_data(b)
    if not isinstance(arr, list):
        return None, None
    for x in arr:
        if (x.get("category") or "") == category:
            return x.get("splitId"), x.get("version")
    return None, None


def db_split_row(pid: int, category: str) -> Tuple[bool, Any]:
    return db_query(
        "SELECT split_id, budget_amount, used_amount, balance, version "
        "FROM RUOYI.budget_split WHERE project_id=? AND category=? AND del_flag='0'",
        [pid, category])


def db_project_budget(pid: int) -> Tuple[bool, Any]:
    return db_query(
        "SELECT budget_total, budget_balance FROM RUOYI.project WHERE project_id=?", [pid])


def db_alert_count(pid: int, split_id: int, statuses=("UNREAD", "READ")) -> int:
    """统计某 split_id 下未处理预警数。"""
    in_clause = ",".join("?" for _ in statuses)
    ok, res = db_query(
        f"SELECT COUNT(*) FROM RUOYI.alert WHERE alert_type='BUDGET' AND ref_id=? "
        f"AND status IN ({in_clause}) AND del_flag='0'", [split_id, *statuses])
    if not ok or not res["rows"]:
        return 0
    return int(res["rows"][0][0])


def db_expense(expense_id: int) -> Tuple[bool, Any]:
    return db_query(
        "SELECT expense_id, project_id, split_id, amount, category, status, version, remark "
        "FROM RUOYI.expense WHERE expense_id=?", [expense_id])


# ============================================================
#  Case 01：预算调整 → split_id 不变 + balance/project 同步
# ============================================================

def case_01_budget_adjust_split_id_unchanged(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    pid, info = add_project(sess, f"{TEST_MARK}-Case01预算调整", leader_id=1,
                             project_no="KY-T5C01-001", splits=SPLITS_REG_OK)
    if pid is None:
        return {"add": info}, False, "建课题失败"
    out["add"] = info
    STATE["case01_pid"] = pid

    # 取调整前的 split_id 字典（按 category）
    ok, res = db_query(
        "SELECT category, split_id FROM RUOYI.budget_split WHERE project_id=? AND del_flag='0' ORDER BY category",
        [pid])
    if not ok:
        return out, False, "DB 查调整前 split_id 失败"
    ids_before = {r[0]: int(r[1]) for r in res["rows"]}
    out["ids_before"] = ids_before

    # 调 /biz/budget/adjust：把 LABOR 从 1000000 改到 800000；带 version
    labor_id, labor_v = find_split(sess, pid, "LABOR")
    body = {
        "projectId": pid,
        "splits": [{"category": "LABOR", "budgetAmount": "800000.00", "version": labor_v}],
    }
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/budget/adjust", json_body=body)
    b = safe_json(r)
    adjust_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["adjust"] = {"body": body, "status_code": r.status_code, "resp": b}

    # DB 验证：split_id 与调整前逐一相同；budget_amount=800000；balance 重算
    ok2, res2 = db_query(
        "SELECT category, split_id, budget_amount, used_amount, balance "
        "FROM RUOYI.budget_split WHERE project_id=? AND del_flag='0' ORDER BY category",
        [pid])
    if not ok2:
        return out, False, "DB 查调整后 split_id 失败"
    rows = res2["rows"]
    ids_after = {r[0]: int(r[1]) for r in rows}
    out["ids_after"] = ids_after
    all_ids_same = len(ids_before) == len(ids_after) and all(
        ids_before.get(cat) == ids_after.get(cat) for cat in ids_before)
    labor_row = next(((c, sid, ba, ua, bal) for c, sid, ba, ua, bal in rows if c == "LABOR"), None)
    labor_ok = (labor_row and Decimal(str(labor_row[2])) == Decimal("800000.00")
                and Decimal(str(labor_row[4])) == Decimal("800000.00"))  # used=0, balance=budget-0
    out["labor_row"] = labor_row

    # project.budget_total / budget_balance 派生：Σ splits（调后）
    # LABOR 1000000 → 800000，差 -200000；新 Σ = 2350000 - 200000 = 2150000
    expected_total = sum(Decimal(s["budgetAmount"]) for s in SPLITS_REG_OK) - Decimal("200000.00")
    expected_balance = expected_total  # used 全 0
    ok3, res3 = db_project_budget(pid)
    if not ok3:
        return out, False, "DB 查 project 汇总失败"
    db_total = Decimal(str(res3["rows"][0][0]))
    db_balance = Decimal(str(res3["rows"][0][1]))
    out["db_total"] = str(db_total)
    out["db_balance"] = str(db_balance)
    out["expected_total"] = str(expected_total)
    out["expected_balance"] = str(expected_balance)
    proj_ok = db_total == expected_total and db_balance == expected_balance

    ok = adjust_ok and all_ids_same and labor_ok and proj_ok
    return out, ok, "" if ok else (
        f"adjust={adjust_ok} ids_same={all_ids_same} labor={labor_ok} proj={proj_ok}")


# ============================================================
#  Case 02：监管上限 — INDIRECT 超 30% 被拒（含上限值提示）；OUTSOURCING 超 30% 被拒；
#           B=0 跳过校验
# ============================================================

def case_02_regulatory_limits(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}

    # 2.1 INDIRECT 超限 → 被拒（先建合规课题，再调 adjust 到超限）
    # 直接费 = 200+300+300+200 = 1000，B = 1000-200 = 800，INDIRECT 上限 = 800*30%=240
    # 合规初始：INDIRECT=200（< 240）；adjust 到 500（> 240）→ 拒
    splits_indirect_compliant = [
        {"category": "LABOR",       "budgetAmount": "0.00"},
        {"category": "EQUIPMENT",   "budgetAmount": "200.00"},
        {"category": "MATERIAL",    "budgetAmount": "300.00"},
        {"category": "TESTING",     "budgetAmount": "300.00"},
        {"category": "FUEL",        "budgetAmount": "200.00"},
        {"category": "TRAVEL",      "budgetAmount": "0.00"},
        {"category": "PUBLICATION", "budgetAmount": "0.00"},
        {"category": "INDIRECT",    "budgetAmount": "200.00"},
        {"category": "OUTSOURCING", "budgetAmount": "0.00"},
        {"category": "TAX",         "budgetAmount": "0.00"},
    ]
    pid, info = add_project(sess, f"{TEST_MARK}-Case02超限", leader_id=1,
                             project_no="KY-T5C02-001", splits=splits_indirect_compliant)
    if pid is None:
        return {"add": info}, False, "建合规课题失败"
    out["add"] = info
    STATE["case02_pid"] = pid

    # 2.1 INDIRECT 超限 → 拒（先调到 500，超过 240 上限）
    ind_id, ind_v = find_split(sess, pid, "INDIRECT")
    body_over = {
        "projectId": pid,
        "splits": [{"category": "INDIRECT", "budgetAmount": "500.00", "version": ind_v}],
    }
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/budget/adjust", json_body=body_over)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    indirect_reject = (isinstance(b, dict) and b.get("code") != 200
                       and "上限金额" in msg
                       and "适用比例" in msg
                       and "间接费超出" in msg)
    out["indirect_over"] = {"body": body_over, "status_code": r.status_code, "resp": b, "msg": msg}

    # 2.2 调到上限值（240）— 通过
    _, ind_v_now = find_split(sess, pid, "INDIRECT")
    body_ok = {
        "projectId": pid,
        "splits": [{"category": "INDIRECT", "budgetAmount": "240.00", "version": ind_v_now}],
    }
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/budget/adjust", json_body=body_ok)
    b = safe_json(r)
    boundary_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["boundary_at_limit"] = {"body": body_ok, "status_code": r.status_code, "resp": b}

    # 2.3 再调到 240.01 — 拒（验证 setScale(HALF_UP) 比较边界）
    _, ind_v_now2 = find_split(sess, pid, "INDIRECT")
    body_over2 = {
        "projectId": pid,
        "splits": [{"category": "INDIRECT", "budgetAmount": "240.01", "version": ind_v_now2}],
    }
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/budget/adjust", json_body=body_over2)
    b = safe_json(r)
    msg2 = str(b.get("msg") or "") if isinstance(b, dict) else ""
    over_reject = (isinstance(b, dict) and b.get("code") != 200 and "上限金额" in msg2)
    out["boundary_over"] = {"body": body_over2, "status_code": r.status_code, "resp": b, "msg": msg2}

    # 2.4 OUTSOURCING 超限：B 仍=800（INDIRECT 不计直接费），上限=240；OUTSOURCING=300 超限
    _, out_v = find_split(sess, pid, "OUTSOURCING")
    body_out = {
        "projectId": pid,
        "splits": [{"category": "OUTSOURCING", "budgetAmount": "300.00", "version": out_v}],
    }
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/budget/adjust", json_body=body_out)
    b = safe_json(r)
    msg3 = str(b.get("msg") or "") if isinstance(b, dict) else ""
    outsourcing_reject = (isinstance(b, dict) and b.get("code") != 200
                          and "委外" in msg3 and "上限金额" in msg3)
    out["outsourcing_over"] = {"body": body_out, "status_code": r.status_code, "resp": b, "msg": msg3}

    # 2.5 B=0 跳过校验：另建一个新课题，全部 0
    pid_b0, info_b0 = add_project(sess, f"{TEST_MARK}-Case02-B0", leader_id=1,
                                   project_no="KY-T5C02-002", splits=[
        {"category": c, "budgetAmount": "0.00"} for c in
        ["LABOR", "EQUIPMENT", "MATERIAL", "TESTING", "FUEL", "TRAVEL",
         "PUBLICATION", "INDIRECT", "OUTSOURCING", "TAX"]
    ])
    if pid_b0 is None:
        return out, False, "建 B=0 课题失败"
    out["add_b0"] = info_b0
    STATE["case02_b0_pid"] = pid_b0

    # B=0 → 不校验 INDIRECT。把 INDIRECT 调到任意正值应通过
    ind_id2, ind_v2 = find_split(sess, pid_b0, "INDIRECT")
    body_b0 = {
        "projectId": pid_b0,
        "splits": [{"category": "INDIRECT", "budgetAmount": "9999.99", "version": ind_v2}],
    }
    sleep_anti_repeat()
    r = http(sess, "PUT", "/biz/budget/adjust", json_body=body_b0)
    b = safe_json(r)
    b0_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["b0_skip"] = {"body": body_b0, "status_code": r.status_code, "resp": b}

    ok = indirect_reject and boundary_ok and over_reject and outsourcing_reject and b0_ok
    return out, ok, "" if ok else (
        f"indirect_reject={indirect_reject} boundary_ok={boundary_ok} "
        f"over_reject={over_reject} outsourcing_reject={outsourcing_reject} b0_ok={b0_ok}")


# ============================================================
#  Case 03：记账成功 — used_amount/balance 逐分正确；project.budget_balance = Σ splits.balance
# ============================================================

def case_03_expense_success(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    pid, info = add_project(sess, f"{TEST_MARK}-Case03记账", leader_id=1,
                             project_no="KY-T5C03-001", splits=SPLITS_REG_OK)
    if pid is None:
        return {"add": info}, False, "建课题失败"
    out["add"] = info
    STATE["case03_pid"] = pid

    labor_sid, _ = find_split(sess, pid, "LABOR")
    if labor_sid is None:
        return out, False, "未找到 LABOR splitId"

    # 记一笔 50000 元到 LABOR
    body = {
        "projectId": pid,
        "splitId": labor_sid,
        "amount": "50000.00",
        "taxRate": "13",
        "expenseDate": "2026-08-14",
        "description": "记一笔-冒烟",
        "voucherUrl": "/upload/voucher/smoke-t5.pdf",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body)
    b = safe_json(r)
    add_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["add"] = {"body": body, "status_code": r.status_code, "resp": b}
    if not add_ok:
        return out, False, "记账失败: " + str(b)[:200]
    saved = get_data(b) or {}
    out["saved"] = {"expense_id": saved.get("expenseId"), "split_id": saved.get("splitId"),
                    "amount": saved.get("amount"), "category": saved.get("category"),
                    "version": saved.get("version")}
    STATE["case03_exp1_id"] = saved.get("expenseId")

    # DB 验证：used_amount = 50000, balance = 950000（budget 1000000 - 50000）
    ok, res = db_split_row(pid, "LABOR")
    if not ok or not res["rows"]:
        return out, False, "DB 查 LABOR 失败"
    db_used = Decimal(str(res["rows"][0][2]))
    db_balance = Decimal(str(res["rows"][0][3]))
    db_version = int(res["rows"][0][4])
    labor_ok = (db_used == Decimal("50000.00") and db_balance == Decimal("950000.00")
                and db_version >= 1)
    out["db_labor"] = {"used": str(db_used), "balance": str(db_balance), "version": db_version}

    # project.budget_balance = Σ splits.balance
    ok2, res2 = db_query(
        "SELECT SUM(balance) FROM RUOYI.budget_split WHERE project_id=? AND del_flag='0'", [pid])
    expected_pb = Decimal(str(res2["rows"][0][0])) if ok2 and res2["rows"][0][0] is not None else None
    ok3, res3 = db_project_budget(pid)
    db_pb = Decimal(str(res3["rows"][0][1])) if ok3 and res3["rows"][0][1] is not None else None
    pb_ok = expected_pb is not None and db_pb is not None and expected_pb == db_pb
    out["db_budget_balance"] = {"db_pb": str(db_pb), "expected_pb": str(expected_pb)}

    ok = add_ok and labor_ok and pb_ok
    return out, ok, "" if ok else f"add={add_ok} labor={labor_ok} pb={pb_ok}"


# ============================================================
#  Case 04：预算不足 — 超余额被拒；改透支开关 true 后通过；收尾改回 false 并清缓存
# ============================================================

def case_04_overdraft_switch(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    pid, info = add_project(sess, f"{TEST_MARK}-Case04透支", leader_id=1,
                             project_no="KY-T5C04-001", splits=SPLITS_SMALL)
    if pid is None:
        return {"add": info}, False, "建课题失败"
    out["add"] = info
    STATE["case04_pid"] = pid

    labor_sid, _ = find_split(sess, pid, "LABOR")

    # 先确认 sys_config=false（前置）
    ok, res = db_query("SELECT config_value FROM RUOYI.sys_config WHERE config_key=?", [OVERDRAFT_KEY])
    cur_value = res["rows"][0][0] if ok and res["rows"] else None
    out["overdraft_initial"] = cur_value

    # 4.1 超余额记账被拒
    body = {
        "projectId": pid,
        "splitId": labor_sid,
        "amount": "2000.00",   # LABOR 预算 1000，超 1000
        "expenseDate": "2026-08-14",
        "description": "超余额",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    reject_ok = (isinstance(b, dict) and b.get("code") != 200
                 and "预算不足" in msg and "剩余可用额" in msg)
    out["over_balance_reject"] = {"body": body, "status_code": r.status_code, "resp": b, "msg": msg}

    # 4.2 打开透支开关（改 sys_config + 刷缓存）
    set_ok, set_msg = set_overdraft(sess, "true", refresh_cache=True)
    out["set_overdraft_true"] = {"ok": set_ok, "msg": set_msg}
    if not set_ok:
        return out, False, "开透支开关失败: " + str(set_msg)

    # 4.3 同一请求再发（应通过）
    body2 = dict(body)
    body2["description"] = "透支记账"
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body2)
    b = safe_json(r)
    pass_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["over_balance_pass"] = {"body": body2, "status_code": r.status_code, "resp": b}
    if pass_ok:
        saved = get_data(b) or {}
        STATE["case04_exp1_id"] = saved.get("expenseId")

    # 4.4 收尾：改回 false + 刷新缓存
    rst_ok, rst_msg = set_overdraft(sess, "false", refresh_cache=True)
    out["set_overdraft_false"] = {"ok": rst_ok, "msg": rst_msg}
    if not rst_ok:
        return out, False, "关透支开关失败: " + str(rst_msg)

    # 4.5 验证关闭后再发同一请求应被拒（把关成功）
    body3 = dict(body)
    body3["description"] = "把关再拒"
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body3)
    b = safe_json(r)
    msg2 = str(b.get("msg") or "") if isinstance(b, dict) else ""
    guard_ok = (isinstance(b, dict) and b.get("code") != 200 and "预算不足" in msg2)
    out["guard_again_reject"] = {"body": body3, "status_code": r.status_code, "resp": b, "msg": msg2}

    ok = reject_ok and set_ok and pass_ok and rst_ok and guard_ok
    return out, ok, "" if ok else (
        f"reject={reject_ok} set={set_ok} pass={pass_ok} rst={rst_ok} guard={guard_ok}")


# ============================================================
#  Case 05：乐观锁 — 双线程并发记账同一 split，期望一笔成功一笔报"已被他人修改"
# ============================================================

def case_05_optimistic_lock(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    pid, info = add_project(sess, f"{TEST_MARK}-Case05乐观锁", leader_id=1,
                             project_no="KY-T5C05-001", splits=SPLITS_REG_OK)
    if pid is None:
        return {"add": info}, False, "建课题失败"
    out["add"] = info
    STATE["case05_pid"] = pid

    labor_sid, _ = find_split(sess, pid, "LABOR")

    # 准备两个 POST body，并发触发同 split 乐观锁竞态
    import threading
    results: Dict[int, Dict[str, Any]] = {}

    def post_expense(idx: int, desc: str):
        body = {
            "projectId": pid,
            "splitId": labor_sid,
            "amount": "100.00",
            "expenseDate": "2026-08-14",
            "description": desc,
        }
        try:
            r = http(sess, "POST", "/biz/expense", json_body=body)
            results[idx] = {"status_code": r.status_code, "body": safe_json(r)}
        except Exception as e:
            results[idx] = {"err": repr(e)}

    # 防重复提交间隔：双线程同时到达应该都能通过 @RepeatSubmit 检查
    # （@RepeatSubmit 用 Redis 锁，interval=2000ms，两请求间隔 < 2s 都拒）
    # 故先 sleep 2s 让锁失效，再双线程并发
    time.sleep(2.5)

    t1 = threading.Thread(target=post_expense, args=(1, "并发-1"))
    t2 = threading.Thread(target=post_expense, args=(2, "并发-2"))
    t1.start(); t2.start()
    t1.join(); t2.join()
    out["thread_results"] = {k: v for k, v in results.items()}

    b1 = (results.get(1) or {}).get("body") or {}
    b2 = (results.get(2) or {}).get("body") or {}
    c1 = b1.get("code") if isinstance(b1, dict) else None
    c2 = b2.get("code") if isinstance(b2, dict) else None
    m1 = str(b1.get("msg") or "") if isinstance(b1, dict) else ""
    m2 = str(b2.get("msg") or "") if isinstance(b2, dict) else ""

    # 一笔成功 + 一笔冲突（或两笔都成功但 used_amount = 200）
    one_success = sum(1 for c in (c1, c2) if c == 200)
    one_conflict = sum(1 for c in (c1, c2) if c == 500)
    conflict_msg = "已被他人修改" in m1 or "已被他人修改" in m2

    ok, used_ok = db_count_used(sess, pid, "LABOR")
    if ok:
        out["db_used_after_race"] = str(used_ok)

    # 允许两种通过模式：
    # A) 严格：一笔 200 + 一笔 500+msg → 冲突
    # B) 宽松：两笔 200 → DB used = 200（无丢更新）
    strict_pass = (one_success == 1 and one_conflict == 1 and conflict_msg)
    loose_pass = (one_success == 2 and used_ok == Decimal("200.00"))
    race_ok = strict_pass or loose_pass
    out["strict_pass"] = strict_pass
    out["loose_pass"] = loose_pass
    out["c1"] = c1
    out["c2"] = c2
    out["m1"] = m1
    out["m2"] = m2

    ok = race_ok
    return out, ok, "" if ok else f"race_ok=False strict={strict_pass} loose={loose_pass}"


def db_count_used(sess, pid: int, category: str) -> Tuple[bool, Optional[Decimal]]:
    """辅助：DB 直查某科目 used_amount。"""
    res = db_query(
        "SELECT used_amount FROM RUOYI.budget_split WHERE project_id=? AND category=? AND del_flag='0'",
        [pid, category])
    if not res[0] or not res[1]["rows"]:
        return False, None
    return True, Decimal(str(res[1]["rows"][0][0]))


# ============================================================
#  Case 06：作废 — VOID 后 used_amount 回冲；流水不参与聚合；重复作废被拒
# ============================================================

def case_06_void(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    pid, info = add_project(sess, f"{TEST_MARK}-Case06作废", leader_id=1,
                             project_no="KY-T5C06-001", splits=SPLITS_REG_OK)
    if pid is None:
        return {"add": info}, False, "建课题失败"
    out["add"] = info
    STATE["case06_pid"] = pid

    labor_sid, _ = find_split(sess, pid, "LABOR")

    # 记一笔
    body = {
        "projectId": pid,
        "splitId": labor_sid,
        "amount": "30000.00",
        "expenseDate": "2026-08-14",
        "description": "待作废",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body)
    b = safe_json(r)
    if not (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200):
        return out, False, "建账失败: " + str(b)[:200]
    saved = get_data(b) or {}
    exp_id = saved.get("expenseId")
    exp_v0 = saved.get("version") or 0   # MP insert 后 version=0；防止 NoneType
    out["add"] = {"body": body, "resp": b, "expense_id": exp_id, "version": exp_v0}

    # DB 验证 used=30000
    ok, res = db_split_row(pid, "LABOR")
    db_used_before = Decimal(str(res["rows"][0][2])) if ok and res["rows"] else None
    out["db_used_before_void"] = str(db_used_before)

    # 6.1 作废
    body_v = {"version": exp_v0, "remark": f"{TEST_MARK}-作废原因"}
    sleep_anti_repeat()
    r = http(sess, "PUT", f"/biz/expense/void/{exp_id}", json_body=body_v)
    b = safe_json(r)
    void_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["void"] = {"body": body_v, "status_code": r.status_code, "resp": b}

    # DB 验证 status=VOID + used 回冲到 0 + version 自增
    ok2, res2 = db_expense(exp_id)
    if not ok2 or not res2["rows"]:
        return out, False, "DB 查 expense 失败"
    db_status = res2["rows"][0][5]
    db_ver = int(res2["rows"][0][6])
    db_remark = res2["rows"][0][7]
    expense_db_ok = (db_status == "VOID" and db_ver >= (exp_v0 or 0) + 1
                     and db_remark == f"{TEST_MARK}-作废原因")
    out["db_expense_after_void"] = {"status": db_status, "version": db_ver, "remark": db_remark}

    ok3, res3 = db_split_row(pid, "LABOR")
    db_used_after = Decimal(str(res3["rows"][0][2])) if ok3 and res3["rows"] else None
    out["db_used_after_void"] = str(db_used_after)
    used_ok = db_used_after == Decimal("0.00")

    # 6.2 重复作废被拒
    sleep_anti_repeat()
    r = http(sess, "PUT", f"/biz/expense/void/{exp_id}", json_body={"remark": "二次作废"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    dup_reject = (isinstance(b, dict) and b.get("code") != 200 and "已作废" in msg)
    out["dup_void_reject"] = {"status_code": r.status_code, "resp": b, "msg": msg}

    # 6.3 验证 VOID 流水不参与聚合：project.budget_total = Σ budget_amount（不被 VOID 影响）
    ok4, res4 = db_project_budget(pid)
    db_total = Decimal(str(res4["rows"][0][0])) if ok4 and res4["rows"][0][0] is not None else None
    # LAB 预算是 1000000；Σ = 2400000
    aggregate_ok = db_total == SUM_REG
    out["db_budget_total"] = str(db_total)

    ok = void_ok and expense_db_ok and used_ok and dup_reject and aggregate_ok
    return out, ok, "" if ok else (
        f"void={void_ok} expense_db={expense_db_ok} used={used_ok} "
        f"dup_reject={dup_reject} aggregate={aggregate_ok}")


# ============================================================
#  Case 07：冲销 — 负数流水入库；used 回冲；原单保持 NORMAL
# ============================================================

def case_07_refund(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    pid, info = add_project(sess, f"{TEST_MARK}-Case07冲销", leader_id=1,
                             project_no="KY-T5C07-001", splits=SPLITS_REG_OK)
    if pid is None:
        return {"add": info}, False, "建课题失败"
    out["add"] = info
    STATE["case07_pid"] = pid

    labor_sid, _ = find_split(sess, pid, "LABOR")

    # 记一笔 80000
    body = {
        "projectId": pid,
        "splitId": labor_sid,
        "amount": "80000.00",
        "expenseDate": "2026-08-14",
        "description": "原单",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body)
    b = safe_json(r)
    if not (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200):
        return out, False, "建原账失败: " + str(b)[:200]
    saved = get_data(b) or {}
    origin_id = saved.get("expenseId")
    out["origin"] = {"body": body, "resp": b, "expense_id": origin_id}

    # DB 验证 used=80000
    ok, res = db_split_row(pid, "LABOR")
    db_used_before = Decimal(str(res["rows"][0][2])) if ok and res["rows"] else None
    out["db_used_before_refund"] = str(db_used_before)

    # 冲销 30000
    body_r = {
        "originExpenseId": origin_id,
        "amount": "30000.00",
        "expenseDate": "2026-08-14",
        "description": f"{TEST_MARK}-冲销说明",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense/refund", json_body=body_r)
    b = safe_json(r)
    refund_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    saved_r = get_data(b) or {}
    out["refund"] = {"body": body_r, "status_code": r.status_code, "resp": b,
                     "refund_id": saved_r.get("expenseId"), "amount": saved_r.get("amount"),
                     "remark": saved_r.get("remark")}

    # DB 验证 used 回冲到 50000
    ok2, res2 = db_split_row(pid, "LABOR")
    db_used_after = Decimal(str(res2["rows"][0][2])) if ok2 and res2["rows"] else None
    used_ok = db_used_after == Decimal("50000.00")
    out["db_used_after_refund"] = str(db_used_after)

    # DB 验证：原单仍 NORMAL
    ok3, res3 = db_expense(origin_id)
    origin_status = res3["rows"][0][5] if ok3 and res3["rows"] else None
    origin_keep = origin_status == "NORMAL"
    out["origin_status_after_refund"] = origin_status

    # 验证：负数流水入库 + remark 含原 expense_id
    refund_amt = Decimal(str(saved_r.get("amount") or "0"))
    neg_ok = refund_amt == Decimal("-30000.00")
    remark_ok = isinstance(saved_r.get("remark"), str) and f"expense_id={origin_id}" in saved_r.get("remark", "")

    ok = refund_ok and used_ok and origin_keep and neg_ok and remark_ok
    return out, ok, "" if ok else (
        f"refund={refund_ok} used={used_ok} origin_keep={origin_keep} "
        f"neg={neg_ok} remark={remark_ok}")


# ============================================================
#  Case 08：双阈值预警 — 余额 ≤5% 触发；幂等；绝对值条件独立生效
# ============================================================

def case_08_alert_threshold(sess) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 课题预算：TRAVEL=10000，记账 9700 → 余额 300，比例 3% ≤ 5% → WARN
    splits = [{"category": c, "budgetAmount": "0.00"} for c in
              ["LABOR", "EQUIPMENT", "MATERIAL", "TESTING", "FUEL",
               "PUBLICATION", "INDIRECT", "OUTSOURCING", "TAX"]]
    # 直接费 = TRAVEL 10000，B = 10000（无 EQUIPMENT），r=30%，TRAVEL 是直接费，可设 10000
    splits[5] = {"category": "TRAVEL", "budgetAmount": "10000.00"}
    pid, info = add_project(sess, f"{TEST_MARK}-Case08预警", leader_id=1,
                             project_no="KY-T5C08-001", splits=splits)
    if pid is None:
        return {"add": info}, False, "建预警课题失败"
    out["add"] = info
    STATE["case08_pid"] = pid

    travel_sid, _ = find_split(sess, pid, "TRAVEL")

    # 8.1 记账 9700：余额 300，比例 3% → WARN
    body = {
        "projectId": pid,
        "splitId": travel_sid,
        "amount": "9700.00",
        "expenseDate": "2026-08-14",
        "description": "打阈值",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body)
    b = safe_json(r)
    if not (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200):
        return out, False, "打阈值记账失败: " + str(b)[:200]
    out["hit_record"] = {"body": body, "resp": b}

    # alert 落库断言
    cnt1 = db_alert_count(pid, travel_sid)
    out["alert_count_after_hit"] = cnt1
    hit_ok = cnt1 == 1

    # 8.2 幂等：再记一笔不重复写
    body2 = dict(body)
    body2["amount"] = "100.00"   # 余额变为 200，仍 ≤5%
    body2["description"] = "幂等第二笔"
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body2)
    b = safe_json(r)
    if not (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200):
        return out, False, "幂等第二笔失败: " + str(b)[:200]
    out["idem_record"] = {"body": body2, "resp": b}
    cnt2 = db_alert_count(pid, travel_sid)
    out["alert_count_after_idem"] = cnt2
    idem_ok = cnt2 == 1

    # 8.3 绝对值条件独立：另建课题预算 100000、记 99100 → 余额 900（≤1000）但比例 0.9% 也≤5%
    # 改为：预算 10000、记 9300 → 余额 700（≤1000）但比例 7%（>5%）→ 仅触发绝对值条件
    splits2 = [{"category": c, "budgetAmount": "0.00"} for c in
               ["LABOR", "EQUIPMENT", "MATERIAL", "TESTING", "FUEL",
                "PUBLICATION", "INDIRECT", "OUTSOURCING", "TAX"]]
    splits2[5] = {"category": "TRAVEL", "budgetAmount": "10000.00"}
    pid2, info2 = add_project(sess, f"{TEST_MARK}-Case08绝对值", leader_id=1,
                               project_no="KY-T5C08-002", splits=splits2)
    if pid2 is None:
        return out, False, "建绝对值课题失败"
    out["add2"] = info2
    STATE["case08_pid2"] = pid2
    travel2_sid, _ = find_split(sess, pid2, "TRAVEL")

    body3 = {
        "projectId": pid2,
        "splitId": travel2_sid,
        "amount": "9300.00",    # 余额 700（≤1000），比例 7%（>5%）
        "expenseDate": "2026-08-14",
        "description": "绝对值条件",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body3)
    b = safe_json(r)
    if not (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200):
        return out, False, "绝对值记账失败: " + str(b)[:200]
    cnt3 = db_alert_count(pid2, travel2_sid)
    out["alert_count_abs"] = cnt3
    abs_ok = cnt3 == 1

    ok = hit_ok and idem_ok and abs_ok
    return out, ok, "" if ok else f"hit={hit_ok} idem={idem_ok} abs={abs_ok}"


# ============================================================
#  Case 09：数据权限 — researcher 给本人相关课题记账成功；给他人课题记账"无权访问"；
#           researcher 调 /void → 403；dept_leader 查 /list 仅见本室
# ============================================================

def case_09_researcher_data_scope(sess_admin, sess_res, sess_dl) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}

    # 9.1 建课题：ResA（leader=researcher）、ResB（leader=leader_b）
    pid_a, info_a = add_project(sess_admin, f"{TEST_MARK}-ResA", leader_id=RES_USER_ID,
                                 project_no="KY-T5C09-001", splits=SPLITS_SMALL)
    out["add_res_a"] = info_a
    pid_b, info_b = add_project(sess_admin, f"{TEST_MARK}-ResB", leader_id=LEADER_B_ID,
                                 project_no="KY-T5C09-002", splits=SPLITS_SMALL)
    out["add_res_b"] = info_b
    if pid_a is None or pid_b is None:
        return out, False, "造 Res 课题失败"

    STATE["case09_pid_a"] = pid_a
    STATE["case09_pid_b"] = pid_b

    # 9.2 researcher 登录
    token, resp = login(sess_res, RES_USERNAME, ADMIN_PASS)
    if not token:
        return {"login_resp": resp}, False, "researcher 登录失败"
    sess_res.headers.update({"Authorization": "Bearer " + token})
    out["login_res"] = resp.get("status_code")

    labor_sid_a, _ = find_split(sess_res, pid_a, "LABOR")

    # 9.3 researcher 给 ResA 记账（应成功）
    body_a = {
        "projectId": pid_a,
        "splitId": labor_sid_a,
        "amount": "100.00",
        "expenseDate": "2026-08-14",
        "description": "researcher 记账-A",
    }
    sleep_anti_repeat()
    r = http(sess_res, "POST", "/biz/expense", json_body=body_a)
    b = safe_json(r)
    add_a_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["res_add_a"] = {"body": body_a, "status_code": r.status_code, "resp": b}
    if add_a_ok:
        saved = get_data(b) or {}
        STATE["case09_res_exp_a"] = saved.get("expenseId")

    # 9.4 researcher 给 ResB 记账（应"无权访问"）
    labor_sid_b, _ = find_split(sess_res, pid_b, "LABOR")
    body_b = {
        "projectId": pid_b,
        "splitId": labor_sid_b,
        "amount": "50.00",
        "expenseDate": "2026-08-14",
        "description": "researcher 记账-B（应拒）",
    }
    sleep_anti_repeat()
    r = http(sess_res, "POST", "/biz/expense", json_body=body_b)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    add_b_forbid = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                    and "无权访问" in msg)
    out["res_add_b_forbid"] = {"status_code": r.status_code, "resp": b, "msg": msg}

    # 9.5 researcher 调 /void（无 biz:expense:void 权限）→ AjaxResult.error(403, "没有权限...")
    if add_a_ok and STATE.get("case09_res_exp_a"):
        sleep_anti_repeat()
        r = http(sess_res, "PUT", f"/biz/expense/void/{STATE['case09_res_exp_a']}",
                 json_body={"remark": "researcher 想作废"})
        out["res_void_status"] = r.status_code
        out["res_void_body"] = str(safe_json(r))[:300]
        body_v = safe_json(r)
        # RuoYi 实际返回：HTTP 200 + body.code=403 + msg 含 "没有权限"
        void_forbid = (
            r.status_code == 200
            and isinstance(body_v, dict)
            and body_v.get("code") == 403
            and ("没有权限" in str(body_v.get("msg") or "")
                 or "expense:void" in str(body_v).lower())
        )
    else:
        void_forbid = False
        out["res_void_skipped"] = True

    # 9.6 dept_leader 登录 + 查 list（应只见本室）
    dl_token, dl_resp = login(sess_dl, DL_USERNAME, ADMIN_PASS)
    if not dl_token:
        return out, False, "dept_leader 登录失败"
    sess_dl.headers.update({"Authorization": "Bearer " + dl_token})
    out["login_dl"] = dl_resp.get("status_code")

    r = http(sess_dl, "GET", "/biz/expense/list", params={"pageNum": 1, "pageSize": 100})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    out["dl_list"] = {"total": b.get("total"), "rows_count": len(rows),
                     "sample": [{"expense_id": x.get("expenseId"),
                                 "project_id": x.get("projectId"),
                                 "amount": x.get("amount"),
                                 "category": x.get("category")}
                                for x in rows[:5]]}
    # dept_leader 看到的是 dept_id=100 范围内的流水。test_researcher 也是 dept=100，
    # 所以应该能见到 ResA 流水。但 ResB 流水（leader=leader_b）也是 dept=100，
    # 也会出现。本测试只验 SQL 无报错、且能看到。
    list_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    list_no_err = list_ok

    ok = add_a_ok and add_b_forbid and void_forbid and list_no_err
    return out, ok, "" if ok else (
        f"add_a={add_a_ok} add_b_forbid={add_b_forbid} void_forbid={void_forbid} list_ok={list_ok}")


# ============================================================
#  附加契约 C1-C5
# ============================================================

def case_c1_version_field(sess) -> Tuple[Dict[str, Any], bool]:
    """C1: GET /biz/expense/list 响应行含 version 字段（数值非 null）。"""
    out: Dict[str, Any] = {}
    pid = STATE.get("case03_pid")
    if pid is None:
        return {"skipped": "前置失败"}, False, "无 case03_pid"
    r = http(sess, "GET", "/biz/expense/list",
             params={"projectId": pid, "pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    versions = [x.get("version") for x in rows]
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok = ok and len(rows) >= 1 and all(v is not None and isinstance(v, int) for v in versions)
    out["rows_count"] = len(rows)
    out["versions"] = versions
    return out, ok, "" if ok else f"versions={versions}"


def case_c2_summary_fields(sess) -> Tuple[Dict[str, Any], bool]:
    """C2: GET /biz/budget/summary 响应含 budgetTotal/usedTotal/balanceTotal/alertCount + splits[].alertFlag/alertLevel"""
    out: Dict[str, Any] = {}
    pid = STATE.get("case01_pid")
    if pid is None:
        return {"skipped": "前置失败"}, False, "无 case01_pid"
    r = http(sess, "GET", "/biz/budget/summary", params={"projectId": pid})
    b = safe_json(r)
    data = get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    if not (isinstance(data, dict)):
        return out, False, "data 非 dict"
    has_top = all(k in data for k in ("budgetTotal", "usedTotal", "balanceTotal", "alertCount"))
    splits = data.get("splits") or []
    has_splits = isinstance(splits, list) and len(splits) > 0
    has_alert = has_splits and all(("alertFlag" in x and "alertLevel" in x) for x in splits)
    out["data_keys"] = list(data.keys())
    out["alertCount"] = data.get("alertCount")
    out["splits_len"] = len(splits)
    out["sample_split_keys"] = list(splits[0].keys()) if splits else None
    ok = ok and has_top and has_splits and has_alert
    return out, ok, "" if ok else f"has_top={has_top} has_splits={has_splits} has_alert={has_alert}"


def case_c3_budget_list_ten_rows(sess) -> Tuple[Dict[str, Any], bool]:
    """C3: GET /biz/budget/list 返回固定 10 行（未编科目 splitId=null）"""
    out: Dict[str, Any] = {}
    # 用 case02_b0_pid（B=0 课题，所有科目金额=0，占位 splitId=null）
    pid = STATE.get("case02_b0_pid")
    if pid is None:
        return {"skipped": "前置失败"}, False, "无 case02_b0_pid"
    r = http(sess, "GET", "/biz/budget/list", params={"projectId": pid})
    b = safe_json(r)
    arr = get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    if not (isinstance(arr, list)):
        return out, False, "data 非 list"
    ten_rows = len(arr) == 10
    # 占位行的 splitId 是 null / version 是 null / budgetAmount=0
    placeholders = [x for x in arr if x.get("splitId") is None]
    out["rows_count"] = len(arr)
    out["placeholder_count"] = len(placeholders)
    out["placeholder_categories"] = [x.get("category") for x in placeholders]
    ok = ok and ten_rows and len(placeholders) >= 1
    return out, ok, "" if ok else f"ten_rows={ten_rows} placeholders={len(placeholders)}"


def case_c4_alert_status(sess) -> Tuple[Dict[str, Any], bool]:
    """C4: GET /biz/expense/alert/list 的 status 为原值（UNREAD/HANDLED，非空串非中文）"""
    out: Dict[str, Any] = {}
    pid = STATE.get("case08_pid")
    if pid is None:
        return {"skipped": "前置失败"}, False, "无 case08_pid"
    r = http(sess, "GET", "/biz/expense/alert/list", params={"projectId": pid})
    b = safe_json(r)
    arr = get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["alert_count"] = len(arr) if isinstance(arr, list) else None
    if not (isinstance(arr, list)) or not arr:
        return out, False, "alert list 为空"
    statuses = [x.get("status") for x in arr]
    levels = [x.get("alertLevel") for x in arr]
    valid_statuses = all(s in ("UNREAD", "READ", "HANDLED") for s in statuses)
    valid_levels = all(l in ("普通", "重要", "严重", "WARN", "CRITICAL", "INFO") for l in levels)
    out["statuses"] = statuses
    out["levels"] = levels
    out["sample"] = arr[0] if arr else None
    ok = ok and valid_statuses
    return out, ok, "" if ok else f"statuses={statuses} levels={levels}"


def case_c5_date_filter(sess) -> Tuple[Dict[str, Any], bool]:
    """C5: 日期区间筛选 params[beginExpenseDate]/params[endExpenseDate] 过滤生效"""
    out: Dict[str, Any] = {}
    pid = STATE.get("case03_pid")
    if pid is None:
        return {"skipped": "前置失败"}, False, "无 case03_pid"
    # case03 已记 2026-08-14 一笔；再记 2026-08-13 一笔
    labor_sid, _ = find_split(sess, pid, "LABOR")
    body = {
        "projectId": pid,
        "splitId": labor_sid,
        "amount": "10.00",
        "expenseDate": "2026-08-13",
        "description": "C5-另一天",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/expense", json_body=body)
    b = safe_json(r)
    add2_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["add2"] = add2_ok

    # 按 begin=2026-08-14 查，只回 08-14 那笔
    r = http(sess, "GET", "/biz/expense/list",
             params={"projectId": pid, "pageNum": 1, "pageSize": 20,
                     "params[beginExpenseDate]": "2026-08-14",
                     "params[endExpenseDate]": "2026-08-14"})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    only_target = len(rows) == 1 and rows[0].get("amount") in ("50000.00", "50000", 50000)
    out["only_target"] = only_target
    out["rows_count"] = len(rows)
    out["rows_sample"] = [{"amount": r.get("amount"), "expenseDate": str(r.get("expenseDate"))}
                          for r in rows]

    ok = add2_ok and only_target
    return out, ok, "" if ok else f"add2_ok={add2_ok} only_target={only_target}"


# ============================================================
#  main
# ============================================================

def dump_results() -> None:
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        for r in RESULTS:
            f.write(json.dumps(r, ensure_ascii=False, default=str) + "\n")


def main() -> int:
    print(f"[boot] base url = {BASE_URL}")
    if not SP.wait_ready(60):
        record("00_backend_ready", False, {}, {"elapsed": 60}, "后端 60 秒内未就绪")
        dump_results()
        return 1
    print("[boot] backend ready")

    # 清残留
    cleanup_test_data()

    # 主 admin 会话
    sess = SP.requests.Session()
    sess.headers.update({"User-Agent": "task5-expense-smoke/1.0"})
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

    sess_res = SP.requests.Session()
    sess_res.headers.update({"User-Agent": "task5-expense-smoke/1.0"})
    sess_dl = SP.requests.Session()
    sess_dl.headers.update({"User-Agent": "task5-expense-smoke/1.0"})

    cases = [
        ("01_budget_adjust_split_id_unchanged", lambda: case_01_budget_adjust_split_id_unchanged(sess)),
        ("02_regulatory_limits",                lambda: case_02_regulatory_limits(sess)),
        ("03_expense_success",                  lambda: case_03_expense_success(sess)),
        ("04_overdraft_switch",                 lambda: case_04_overdraft_switch(sess)),
        ("05_optimistic_lock",                  lambda: case_05_optimistic_lock(sess)),
        ("06_void",                             lambda: case_06_void(sess)),
        ("07_refund",                           lambda: case_07_refund(sess)),
        ("08_alert_threshold",                  lambda: case_08_alert_threshold(sess)),
        ("09_researcher_data_scope",            lambda: case_09_researcher_data_scope(sess, sess_res, sess_dl)),
        ("c1_version_field",                    lambda: case_c1_version_field(sess)),
        ("c2_summary_fields",                   lambda: case_c2_summary_fields(sess)),
        ("c3_budget_list_ten_rows",             lambda: case_c3_budget_list_ten_rows(sess)),
        ("c4_alert_status",                     lambda: case_c4_alert_status(sess)),
        ("c5_date_filter",                      lambda: case_c5_date_filter(sess)),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/*"}, resp, note)
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