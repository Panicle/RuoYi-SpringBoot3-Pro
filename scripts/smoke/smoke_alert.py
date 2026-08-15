#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 6 — 阶段9 预警引擎+对话精灵 端到端冒烟（任务卡 §九 9 组用例 + 回归）
- 覆盖：合同节点预警 / 经费超限预警 / 资料逾期预警 / 通知派生与读态 / 数据权限 /
        遗留 BUDGET 兼容 / 对话精灵降级 / 确认卡片链路 / 回归 smoke_rd
- 复用 smoke_rd.py 的登录/DB 直查工具 + RSA 公钥
- 结果写 scripts/smoke/result_alert.jsonl（独立文件）
- 只测不改业务代码；发现的 Bug 记入报告，返回给控制方裁决
- 测试账号：test_al_res / test_al_sci / test_al_dl / test_al_res2（用户名 ≤15 字符）
- 前置（简报约定）：
  1. V1.0.17__alert_scan_job.sql 已执行（本脚本仅 DB 断言 sys_job 行；执行动作由冒烟阶段完成）
  2. 后端新 jar 已起 8087；LLM key 未配置 → 对话精灵走降级路径
- 幂等可重跑：清残留 → 快照 alert/notification → 暂停 Quartz 预警任务 → 造数 → 跑用例 → 清理恢复
- 扫描端点 /biz/alert/scan 带 @RepeatSubmit(interval=5000)，串行扫描间隔需 ≥5.5s
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
RESULT_PATH = os.path.join(SCRIPT_DIR, "result_alert.jsonl")

# ============== 测试用户（独占 ID 段 90071+，用户名 ≤15 字符） ==============
# RES ：researcher（105，data_scope=5）本课题 leader（P_OWN）→ 预警本人相关 + 通知 leader
# SCI ：science_admin（101，data_scope=1）全所 → 通知 science_admin 通道
# DL  ：dept_leader（104，data_scope=3）本室 101 → 数据权限本室样本
# RES2：researcher（105）他室 100 课题 leader（P_OTHER）→ 数据权限反向样本
RES_USER_ID   = 90071
SCI_USER_ID   = 90072
DL_USER_ID    = 90073
RES2_USER_ID  = 90074
RES_USERNAME  = "test_al_res"
SCI_USERNAME  = "test_al_sci"
DL_USERNAME   = "test_al_dl"
RES2_USERNAME = "test_al_res2"

DEPT_D1 = 101   # 本室
DEPT_D2 = 100   # 他室（总公司）

TEST_MARK = "smoke-task9-alert"

RESULTS: List[Dict[str, Any]] = []
STATE: Dict[str, Any] = {}


# ============================================================
#  通用工具（照 smoke_rd.py）
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
    if isinstance(body, dict) and body.get("code") == 200:
        return body.get("data")
    return None


def tdi_rows(body: Any) -> Optional[List[Dict[str, Any]]]:
    if isinstance(body, dict) and body.get("code") == 200:
        return body.get("rows")
    return None


def is_business_reject(body: Any) -> bool:
    if not isinstance(body, dict):
        return False
    return body.get("code") not in (200, None)


def sleep_anti_repeat(sec: float = 5.5) -> None:
    """扫描端点 @RepeatSubmit(interval=5000)，串行调用需停顿 ≥5.5s。"""
    time.sleep(sec)


# ============================================================
#  DB 辅助
# ============================================================

def q1(sql: str, params: Optional[List[Any]] = None) -> Tuple[bool, Any]:
    """单行查询，返回 (ok, row)。"""
    ok, res = db_query(sql, params)
    if not ok:
        return False, None
    rows = res["rows"]
    return True, (rows[0] if rows else None)


def q_rows(sql: str, params: Optional[List[Any]] = None) -> Tuple[bool, List[Any]]:
    ok, res = db_query(sql, params)
    if not ok:
        return False, []
    return True, list(res["rows"])


def insert_and_get_id(insert_sql: str, params: List[Any], get_sql: str, get_params: List[Any]) -> Tuple[bool, Any]:
    ok, res = db_execute(insert_sql, params)
    if not ok:
        return False, None
    ok2, row = q1(get_sql, get_params)
    if not ok2 or row is None:
        return False, None
    return True, row[0]


def snapshot_alert_notify() -> Tuple[bool, str]:
    """快照 alert / notification 全表 ID（清理时恢复零残留）。"""
    ok_a, res_a = q_rows("SELECT ALERT_ID FROM RUOYI.ALERT")
    if not ok_a:
        return False, "alert 快照失败"
    STATE["pre_alert_ids"] = [r[0] for r in res_a]
    ok_n, res_n = q_rows("SELECT NOTIFY_ID FROM RUOYI.NOTIFICATION")
    if not ok_n:
        return False, "notification 快照失败"
    STATE["pre_notify_ids"] = [r[0] for r in res_n]
    STATE["pre_alert_cnt"] = len(STATE["pre_alert_ids"])
    STATE["pre_notify_cnt"] = len(STATE["pre_notify_ids"])
    return True, f"alert={len(STATE['pre_alert_ids'])} notify={len(STATE['pre_notify_ids'])}"


def _in_clause(ids: List[Any]) -> str:
    if not ids:
        return "(0)"   # 空快照 → 全删（NOT IN (0) 恒真）
    return "(" + ",".join(str(i) for i in ids) + ")"


def pause_alert_job() -> Tuple[bool, str]:
    """暂停 Quartz 预警任务（防 smoke 期间 08:00 自动扫描干扰）；结束恢复。"""
    ok, row = q1("SELECT STATUS FROM RUOYI.SYS_JOB WHERE JOB_NAME = '预警扫描'")
    if not ok or row is None:
        return True, "无预警任务（V1.0.17 未执行？）跳过暂停"
    STATE["job_orig_status"] = row[0]
    db_execute("UPDATE RUOYI.SYS_JOB SET STATUS = '1' WHERE JOB_NAME = '预警扫描'")
    return True, f"预警任务暂停 status={row[0]}→1"


def restore_alert_job() -> Tuple[bool, str]:
    if "job_orig_status" not in STATE:
        return True, "未暂停，跳过恢复"
    db_execute("UPDATE RUOYI.SYS_JOB SET STATUS = ? WHERE JOB_NAME = '预警扫描'",
               [STATE["job_orig_status"]])
    return True, f"预警任务恢复 status={STATE['job_orig_status']}"


# ============================================================
#  测试用户 / 测试数据准备与清理
# ============================================================

def setup_test_users() -> Tuple[bool, str]:
    ok, res = db_query("SELECT PASSWORD FROM RUOYI.SYS_USER WHERE USER_NAME='admin'")
    if not ok or not res["rows"]:
        return False, "admin hash 读取失败: " + str(res)
    admin_hash = res["rows"][0][0]
    users = [
        (RES_USER_ID,   RES_USERNAME,  "冒烟预警科研员", DEPT_D1, 105),
        (SCI_USER_ID,   SCI_USERNAME,  "冒烟预警科管",   DEPT_D1, 101),
        (DL_USER_ID,    DL_USERNAME,   "冒烟预警室主任", DEPT_D1, 104),
        (RES2_USER_ID,  RES2_USERNAME, "冒烟预警他室",   DEPT_D2, 105),
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
        ok2, r2 = db_execute(
            "INSERT INTO RUOYI.SYS_USER_ROLE (USER_ID, ROLE_ID) VALUES (?, ?)", [uid, role_id])
        if not ok2:
            return False, f"绑角色 {uname} 失败: {r2}"
    return True, "ok"


def prepare_test_data() -> Tuple[bool, str]:
    """造 P_OWN/P_OTHER 课题 + 合同节点 + 资料审批 + 遗留 BUDGET 预警（全部 DB 直插）。"""
    seq = int(time.time()) % 90000
    # ---- P_OWN：RES 主持本室课题，balance=900（≤1000 触发经费超限）----
    pname = f"{TEST_MARK}-own"
    ok, pid = insert_and_get_id(
        "INSERT INTO RUOYI.PROJECT (PROJECT_NAME, LEADER_ID, BUDGET_TOTAL, BUDGET_BALANCE, STATUS, "
        "DEPT_ID, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES (?, ?, 50000, 900, 'ACTIVE', ?, '0', 'admin', SYSDATE, ?)",
        [pname, RES_USER_ID, DEPT_D1, TEST_MARK],
        "SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE PROJECT_NAME = ? AND REMARK = ?",
        [pname, TEST_MARK])
    if not ok:
        return False, f"建 P_OWN 失败: {pid}"
    STATE["p_own"] = pid
    # ---- P_OTHER：RES2 主持他室课题，balance=900（亦触发，供数据权限反向样本）----
    pname2 = f"{TEST_MARK}-other"
    ok, pid2 = insert_and_get_id(
        "INSERT INTO RUOYI.PROJECT (PROJECT_NAME, LEADER_ID, BUDGET_TOTAL, BUDGET_BALANCE, STATUS, "
        "DEPT_ID, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES (?, ?, 30000, 900, 'ACTIVE', ?, '0', 'admin', SYSDATE, ?)",
        [pname2, RES2_USER_ID, DEPT_D2, TEST_MARK],
        "SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE PROJECT_NAME = ? AND REMARK = ?",
        [pname2, TEST_MARK])
    if not ok:
        return False, f"建 P_OTHER 失败: {pid2}"
    STATE["p_other"] = pid2
    # ---- 合同 + 节点（P_OWN）：plan_date=今天+10，PENDING → 扫描①命中 ----
    contract_no = f"SMK-AL-{seq}"
    ok, cid = insert_and_get_id(
        "INSERT INTO RUOYI.CONTRACT (CONTRACT_NO, PROJECT_ID, CONTRACT_NAME, CONTRACT_TYPE, "
        "PARTY_NAME, STATUS, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES (?, ?, ?, 'RESEARCH', 'X', 'ACTIVE', '0', 'admin', SYSDATE, ?)",
        [contract_no, pid, f"{TEST_MARK}-合同", TEST_MARK],
        "SELECT CONTRACT_ID FROM RUOYI.CONTRACT WHERE CONTRACT_NO = ? AND REMARK = ?",
        [contract_no, TEST_MARK])
    if not ok:
        return False, f"建合同失败: {cid}"
    STATE["contract_id"] = cid
    node_name = f"{TEST_MARK}-节点-{seq}"
    ok, nid = insert_and_get_id(
        "INSERT INTO RUOYI.CONTRACT_NODE (CONTRACT_ID, NODE_NAME, NODE_TYPE, PLAN_DATE, ACTUAL_DATE, "
        "STATUS, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES (?, ?, 'PAYMENT', SYSDATE + 10, NULL, 'PENDING', '0', 'admin', SYSDATE, ?)",
        [cid, node_name, TEST_MARK],
        "SELECT NODE_ID FROM RUOYI.CONTRACT_NODE WHERE NODE_NAME = ? AND REMARK = ?",
        [node_name, TEST_MARK])
    if not ok:
        return False, f"建节点失败: {nid}"
    STATE["node_id"] = nid
    # ---- 资料 + 审批（P_OWN）：plan_submit_date=昨天，approval PENDING → 扫描③命中 ----
    file_name = f"{TEST_MARK}-doc-{seq}.pdf"
    ok, did = insert_and_get_id(
        "INSERT INTO RUOYI.PROJECT_DOCUMENT (PROJECT_ID, STAGE, FILE_NAME, FILE_URL, UPLOAD_BY, "
        "UPLOAD_TIME, PLAN_SUBMIT_DATE, DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES (?, 'REVIEW', ?, '/tmp/smoke-alert.pdf', 'admin', SYSDATE, SYSDATE - 1, '0', "
        "'admin', SYSDATE, ?)",
        [pid, file_name, TEST_MARK],
        "SELECT DOC_ID FROM RUOYI.PROJECT_DOCUMENT WHERE FILE_NAME = ? AND REMARK = ?",
        [file_name, TEST_MARK])
    if not ok:
        return False, f"建资料失败: {did}"
    STATE["doc_id"] = did
    ok, aid = insert_and_get_id(
        "INSERT INTO RUOYI.APPROVAL (DOC_ID, APPLICANT_ID, APPROVER_ID, STATUS, COMMENT_TEXT, "
        "DEL_FLAG, CREATE_BY, CREATE_TIME) "
        "VALUES (?, ?, NULL, 'PENDING', NULL, '0', 'admin', SYSDATE)",
        [did, RES_USER_ID],
        "SELECT APPROVAL_ID FROM RUOYI.APPROVAL WHERE DOC_ID = ?",
        [did])
    if not ok:
        return False, f"建审批失败: {aid}"
    STATE["approval_id"] = aid
    # ---- 遗留 BUDGET 预警（case 6）：budget_split + 一条 status='UNREAD' ref_type=NULL 旧 alert ----
    ok, sid = insert_and_get_id(
        "INSERT INTO RUOYI.BUDGET_SPLIT (PROJECT_ID, CATEGORY, BUDGET_AMOUNT, DEL_FLAG, CREATE_BY, "
        "CREATE_TIME, REMARK) VALUES (?, 'LABOR', 100, '0', 'admin', SYSDATE, ?)",
        [pid, TEST_MARK],
        "SELECT SPLIT_ID FROM RUOYI.BUDGET_SPLIT WHERE PROJECT_ID = ? AND REMARK = ? AND CATEGORY = 'LABOR'",
        [pid, TEST_MARK])
    if not ok:
        return False, f"建 legacy split 失败: {sid}"
    STATE["legacy_split_id"] = sid
    legacy_title = f"{TEST_MARK}-遗留BUDGET"
    ok, lid = insert_and_get_id(
        "INSERT INTO RUOYI.ALERT (ALERT_TYPE, REF_ID, REF_TYPE, ALERT_LEVEL, TITLE, CONTENT, STATUS, "
        "DEL_FLAG, CREATE_BY, CREATE_TIME, REMARK) "
        "VALUES ('BUDGET', ?, NULL, 'WARN', ?, '阶段4遗留-模拟旧预警', 'UNREAD', '0', 'admin', SYSDATE, ?)",
        [sid, legacy_title, TEST_MARK],
        "SELECT ALERT_ID FROM RUOYI.ALERT WHERE TITLE = ? AND REMARK = ?",
        [legacy_title, TEST_MARK])
    if not ok:
        return False, f"建 legacy alert 失败: {lid}"
    STATE["legacy_alert_id"] = lid
    return True, "ok"


def cleanup_test_refs() -> Tuple[bool, str]:
    """按 TEST_MARK + 测试 ref 链物理清理（幂等；不触碰存量 alert/notification，可安全在快照前执行）。"""
    # 1. 通知：引用测试预警的（按 biz_key / 遗留 title / ref 链）
    db_execute("DELETE FROM RUOYI.NOTIFICATION WHERE ALERT_ID IN ("
               "SELECT ALERT_ID FROM RUOYI.ALERT WHERE BIZ_KEY IN (?, ?, ?) OR TITLE = ?)",
               ["CONTRACT:node:" + str(STATE.get("node_id")),
                "BUDGET:project:" + str(STATE.get("p_own")),
                "BUDGET:project:" + str(STATE.get("p_other")),
                f"{TEST_MARK}-遗留BUDGET"])
    # 2. 预警：按测试对象 ref 链（先于对象删除，捕获历史崩溃残留）+ biz_key + 遗留 title
    db_execute("DELETE FROM RUOYI.ALERT WHERE REF_ID IN "
               "(SELECT NODE_ID FROM RUOYI.CONTRACT_NODE WHERE REMARK = ?) "
               "OR REF_ID IN (SELECT DOC_ID FROM RUOYI.PROJECT_DOCUMENT WHERE REMARK = ?) "
               "OR REF_ID IN (SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?) "
               "OR (REF_TYPE IS NULL AND REF_ID IN (SELECT SPLIT_ID FROM RUOYI.BUDGET_SPLIT WHERE REMARK = ?))",
               [TEST_MARK, TEST_MARK, TEST_MARK, TEST_MARK])
    db_execute("DELETE FROM RUOYI.ALERT WHERE BIZ_KEY IN (?, ?, ?)",
               ["CONTRACT:node:" + str(STATE.get("node_id")),
                "BUDGET:project:" + str(STATE.get("p_own")),
                "BUDGET:project:" + str(STATE.get("p_other"))])
    db_execute("DELETE FROM RUOYI.ALERT WHERE TITLE = ? AND REMARK = ?",
               [f"{TEST_MARK}-遗留BUDGET", TEST_MARK])
    # 3. 审批 / 资料 / 节点 / 合同 / 分劈 / 课题
    ok, r = db_execute("DELETE FROM RUOYI.APPROVAL WHERE DOC_ID IN "
                       "(SELECT DOC_ID FROM RUOYI.PROJECT_DOCUMENT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 approval 失败: " + str(r)
    db_execute("DELETE FROM RUOYI.PROJECT_DOCUMENT WHERE REMARK = ?", [TEST_MARK])
    db_execute("DELETE FROM RUOYI.CONTRACT_NODE WHERE REMARK = ?", [TEST_MARK])
    db_execute("DELETE FROM RUOYI.CONTRACT WHERE REMARK = ?", [TEST_MARK])
    db_execute("DELETE FROM RUOYI.BUDGET_SPLIT WHERE REMARK = ?", [TEST_MARK])
    db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    # 4. 测试用户
    db_execute("DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID IN (?, ?, ?, ?)",
               [RES_USER_ID, SCI_USER_ID, DL_USER_ID, RES2_USER_ID])
    db_execute("DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?, ?)",
               [RES_USER_ID, SCI_USER_ID, DL_USER_ID, RES2_USER_ID])
    return True, "ok"


def cleanup_snapshot_new() -> Tuple[bool, str]:
    """删除快照之后新建的 alert/notification（含扫描对真实课题的副作用行）。仅在收尾调用。"""
    db_execute("DELETE FROM RUOYI.NOTIFICATION WHERE NOTIFY_ID NOT IN "
               + _in_clause(STATE.get("pre_notify_ids", [])))
    db_execute("DELETE FROM RUOYI.ALERT WHERE ALERT_ID NOT IN " + _in_clause(STATE.get("pre_alert_ids", [])))
    return True, "ok"


def cleanup_test_data() -> Tuple[bool, str]:
    """收尾清理 = 按 ref 链清理 + 快照新增清理 + 恢复 Quartz 任务。"""
    cleanup_test_refs()
    cleanup_snapshot_new()
    restore_alert_job()
    return True, "ok"


# ============================================================
#  扫描辅助（@RepeatSubmit 5000ms → 串行间隔 ≥5.5s）
# ============================================================

def scan(sess: requests.Session, tag: str) -> Tuple[Dict[str, Any], bool]:
    sleep_anti_repeat(5.5)
    r = http(sess, "POST", "/biz/alert/scan")
    b = safe_json(r)
    created = get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    return {"tag": tag, "status_code": r.status_code, "body": b, "created": created}, ok


# ============================================================
#  Case 00：V1.0.17 sys_job DB 断言 + 造数（SysJob 插入 + sys_job 表 schema 核对）
# ============================================================

def case_00_prepare(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 0.1 sys_job '预警扫描' 行断言（V1.0.17 执行产物）
    ok, row = q1(
        "SELECT JOB_ID, JOB_NAME, INVOKE_TARGET, CRON_EXPRESSION, MISFIRE_POLICY, CONCURRENT, STATUS "
        "FROM RUOYI.SYS_JOB WHERE JOB_NAME = '预警扫描'")
    # status 已被 pause_alert_job 改为 '1'（暂停），此处断言其余契约字段；
    # 原始 status='0'（启用）已在冒烟前 V1.0.17 执行时 DB 核对（见报告）。
    job_ok = (ok and row is not None
              and row[2] == "alertScanTask.scanAll()"
              and row[3] == "0 0 8 * * ?"
              and row[4] == "0" and row[5] == "1")
    out["sys_job"] = {"row": row, "ok": job_ok}
    # 0.2 造数
    ok2, msg = prepare_test_data()
    out["prepare"] = {"ok": ok2, "msg": msg}
    ok_all = job_ok and ok2
    return out, ok_all, "" if ok_all else (
        f"sys_job={job_ok} prepare={ok2}({msg})")


# ============================================================
#  Case 01：合同节点预警（扫描①）
# ============================================================

def case_01_contract(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    nid = STATE["node_id"]
    biz = f"CONTRACT:node:{nid}"
    # 1.1 首次 scan → 生成 CONTRACT alert
    s1, ok1 = scan(sess_admin, "scan1-first")
    out["scan1"] = s1
    ok_d, row_d = q1("SELECT ALERT_ID, STATUS, ROUND, FIRST_TIME, LAST_TIME FROM RUOYI.ALERT "
                     "WHERE BIZ_KEY = ? AND DEL_FLAG = '0'", [biz])
    out["db_after_scan1"] = row_d
    gen_ok = (ok_d and row_d is not None and row_d[1] == "OPEN" and row_d[2] == 1)
    if gen_ok:
        STATE["contract_alert_id"] = row_d[0]
        STATE["contract_first_time"] = row_d[3]
        STATE["contract_last_time_1"] = row_d[4]
    # 1.2 再次 scan 幂等：不新增行，last_time 更新
    s2, ok2 = scan(sess_admin, "scan2-idempotent")
    out["scan2"] = s2
    ok_d2, row_d2 = q1("SELECT ALERT_ID, STATUS, ROUND, FIRST_TIME, LAST_TIME FROM RUOYI.ALERT "
                       "WHERE BIZ_KEY = ? AND DEL_FLAG = '0'", [biz])
    out["db_after_scan2"] = row_d2
    count2 = 1 if (ok_d2 and row_d2) else 0
    last_advanced = (ok_d2 and row_d2 is not None and row_d2[4] is not None
                     and STATE.get("contract_last_time_1") is not None
                     and (row_d2[4] - STATE["contract_last_time_1"]).total_seconds() > 0)
    idem_ok = (count2 == 1 and last_advanced)
    # 1.3 节点 status→DONE 后再 scan：不新增
    db_execute("UPDATE RUOYI.CONTRACT_NODE SET STATUS = 'DONE' WHERE NODE_ID = ?", [nid])
    s3, ok3 = scan(sess_admin, "scan3-node-done")
    out["scan3"] = s3
    ok_d3, row_d3 = q1("SELECT ALERT_ID, STATUS, ROUND FROM RUOYI.ALERT "
                       "WHERE BIZ_KEY = ? AND DEL_FLAG = '0'", [biz])
    out["db_after_scan3"] = row_d3
    node_done_no_new = (ok_d3 and row_d3 is not None and row_d3[0] == STATE.get("contract_alert_id"))
    ok_all = ok1 and ok_d and gen_ok and idem_ok and ok3 and node_done_no_new
    return out, ok_all, "" if ok_all else (
        f"scan1={ok1} gen={gen_ok} idem={idem_ok}(count={count2},last_adv={last_advanced}) "
        f"node_done_no_new={node_done_no_new}")


# ============================================================
#  Case 02：经费超限预警（扫描②）
# ============================================================

def case_02_budget(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own"]
    biz = f"BUDGET:project:{p_own}"
    # 2.1 round1 OPEN（case_01 首次 scan 已生成）
    ok_d, rows = q_rows("SELECT ALERT_ID, ROUND, STATUS FROM RUOYI.ALERT "
                        "WHERE BIZ_KEY = ? AND DEL_FLAG = '0' ORDER BY ROUND", [biz])
    round1 = [r for r in rows if r[1] == 1]
    gen_ok = (ok_d and len(round1) == 1 and round1[0][2] == "OPEN")
    if gen_ok:
        STATE["budget_round1_id"] = round1[0][0]
    # 2.2 改 balance=20000 → scan 不新增（候选不再命中）
    db_execute("UPDATE RUOYI.PROJECT SET BUDGET_BALANCE = 20000 WHERE PROJECT_ID = ?", [p_own])
    s2, ok2 = scan(sess_admin, "scan4-balance-high")
    out["scan4"] = s2
    ok_c2, cnt2 = q1("SELECT COUNT(*) FROM RUOYI.ALERT WHERE BIZ_KEY = ? AND DEL_FLAG = '0'", [biz])
    cnt2 = cnt2[0] if (ok_c2 and cnt2) else None
    out["db_count_after_scan4"] = cnt2
    no_new_ok = (ok_c2 and cnt2 == 1)
    # 2.3 resolve（确认处理）→ RESOLVED
    r3 = http(sess_admin, "POST", f"/biz/alert/resolve/{STATE['budget_round1_id']}")
    b3 = safe_json(r3)
    out["resolve"] = {"status_code": r3.status_code, "body": b3}
    resolve_ok = r3.status_code == 200 and isinstance(b3, dict) and b3.get("code") == 200
    ok_d3, row3 = q1("SELECT STATUS, REMARK FROM RUOYI.ALERT WHERE ALERT_ID = ?",
                     [STATE["budget_round1_id"]])
    resolved_ok = (ok_d3 and row3 is not None and row3[0] == "RESOLVED" and "admin" in str(row3[1]))
    # 2.4 再改回 balance=900 → scan → round=2 新行（重触发）
    db_execute("UPDATE RUOYI.PROJECT SET BUDGET_BALANCE = 900 WHERE PROJECT_ID = ?", [p_own])
    s4, ok4 = scan(sess_admin, "scan5-retrigger")
    out["scan5"] = s4
    ok_d4, rows4 = q_rows("SELECT ALERT_ID, ROUND, STATUS FROM RUOYI.ALERT "
                          "WHERE BIZ_KEY = ? AND DEL_FLAG = '0' ORDER BY ROUND", [biz])
    round2 = [r for r in rows4 if r[1] == 2]
    retrigger_ok = (ok_d4 and len(rows4) == 2 and len(round2) == 1 and round2[0][2] == "OPEN")
    if retrigger_ok:
        STATE["budget_round2_id"] = round2[0][0]
    ok_all = gen_ok and no_new_ok and resolve_ok and resolved_ok and retrigger_ok
    return out, ok_all, "" if ok_all else (
        f"gen_round1={gen_ok} no_new_high={no_new_ok} resolve={resolve_ok} "
        f"resolved_db={resolved_ok} round2={retrigger_ok}")


# ============================================================
#  Case 03：资料逾期预警（扫描③）
# ============================================================

def case_03_document(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    did = STATE["doc_id"]
    biz = f"DOCUMENT:doc:{did}"
    # 3.1 首次 scan 已生成 OPEN
    ok_d, row_d = q1("SELECT ALERT_ID, STATUS FROM RUOYI.ALERT "
                     "WHERE BIZ_KEY = ? AND DEL_FLAG = '0'", [biz])
    gen_ok = (ok_d and row_d is not None and row_d[1] == "OPEN")
    if gen_ok:
        STATE["doc_alert_id"] = row_d[0]
    # 3.2 approval→APPROVED 后 scan：候选不再命中，不新增
    db_execute("UPDATE RUOYI.APPROVAL SET STATUS = 'APPROVED' WHERE DOC_ID = ?", [did])
    s2, ok2 = scan(sess_admin, "scan6-approval-approved")
    out["scan6"] = s2
    ok_c2, cnt2 = q1("SELECT COUNT(*) FROM RUOYI.ALERT WHERE BIZ_KEY = ? AND DEL_FLAG = '0'", [biz])
    cnt2 = cnt2[0] if (ok_c2 and cnt2) else None
    out["db_count_after_scan6"] = cnt2
    no_new_ok = (ok_c2 and cnt2 == 1)
    ok_all = ok_d and gen_ok and ok2 and no_new_ok
    return out, ok_all, "" if ok_all else f"gen={gen_ok} no_new_approved={no_new_ok}"


# ============================================================
#  Case 04：通知派生与读态
# ============================================================

def case_04_notification(sess_admin, sess_sci) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    alert_id = STATE["contract_alert_id"]
    # 4.1 派生断言：leader + admin + science_admin
    ok_n, rows_n = q_rows("SELECT NOTIFY_ID, RECEIVER_ID, STATUS FROM RUOYI.NOTIFICATION "
                          "WHERE ALERT_ID = ? AND DEL_FLAG = '0'", [alert_id])
    receivers = [int(r[1]) for r in rows_n]
    statuses = {int(r[1]): r[2] for r in rows_n}
    out["notifications"] = rows_n
    has_leader = RES_USER_ID in receivers
    has_admin = 1 in receivers
    has_sci = SCI_USER_ID in receivers
    derive_ok = len(rows_n) >= 2 and has_leader and (has_admin or has_sci)
    # 4.2 未读计数 before（SCI）
    r_before = http(sess_sci, "GET", "/biz/alert/notify/unread/count")
    b_before = safe_json(r_before)
    cnt_before = get_data(b_before)
    out["unread_before"] = {"status_code": r_before.status_code, "body": b_before, "cnt": cnt_before}
    # 4.3 SCI read 自己的通知 → READ
    sci_notify = next((r[0] for r in rows_n if int(r[1]) == SCI_USER_ID), None)
    STATE["sci_notify_id"] = sci_notify
    r_read = http(sess_sci, "POST", f"/biz/alert/notify/{sci_notify}/read")
    b_read = safe_json(r_read)
    out["read"] = {"status_code": r_read.status_code, "body": b_read}
    read_ok = r_read.status_code == 200 and isinstance(b_read, dict) and b_read.get("code") == 200
    ok_dr, row_r = q1("SELECT STATUS, IS_READ, READ_TIME FROM RUOYI.NOTIFICATION WHERE NOTIFY_ID = ?",
                      [sci_notify])
    read_db_ok = (ok_dr and row_r is not None and row_r[0] == "READ" and row_r[1] == 1 and row_r[2] is not None)
    # 4.4 未读计数 after → 递减 1
    r_after = http(sess_sci, "GET", "/biz/alert/notify/unread/count")
    b_after = safe_json(r_after)
    cnt_after = get_data(b_after)
    out["unread_after"] = {"status_code": r_after.status_code, "body": b_after, "cnt": cnt_after}
    count_decr = (isinstance(cnt_before, int) and isinstance(cnt_after, int)
                  and cnt_after == cnt_before - 1)
    # 4.5 重复 read 幂等成功（已 READ 再 read；@RepeatSubmit 2000ms 需间隔）
    sleep_anti_repeat(2.5)
    r_read2 = http(sess_sci, "POST", f"/biz/alert/notify/{sci_notify}/read")
    b_read2 = safe_json(r_read2)
    out["read_repeat"] = {"status_code": r_read2.status_code, "body": b_read2}
    repeat_read_ok = r_read2.status_code == 200 and isinstance(b_read2, dict) and b_read2.get("code") == 200
    # 4.6 admin confirm 自己的通知 → CONFIRMED
    admin_notify = next((r[0] for r in rows_n if int(r[1]) == 1), None)
    STATE["admin_notify_id"] = admin_notify
    r_conf = http(sess_admin, "POST", f"/biz/alert/notify/{admin_notify}/confirm")
    b_conf = safe_json(r_conf)
    out["confirm"] = {"status_code": r_conf.status_code, "body": b_conf}
    confirm_ok = r_conf.status_code == 200 and isinstance(b_conf, dict) and b_conf.get("code") == 200
    ok_dc, row_c = q1("SELECT STATUS, IS_READ, CONFIRM_TIME FROM RUOYI.NOTIFICATION WHERE NOTIFY_ID = ?",
                      [admin_notify])
    confirm_db_ok = (ok_dc and row_c is not None and row_c[0] == "CONFIRMED"
                     and row_c[1] == 1 and row_c[2] is not None)
    # 4.7 重复 confirm 幂等成功（@RepeatSubmit 2000ms 需间隔）
    sleep_anti_repeat(2.5)
    r_conf2 = http(sess_admin, "POST", f"/biz/alert/notify/{admin_notify}/confirm")
    b_conf2 = safe_json(r_conf2)
    out["confirm_repeat"] = {"status_code": r_conf2.status_code, "body": b_conf2}
    repeat_confirm_ok = (r_conf2.status_code == 200 and isinstance(b_conf2, dict)
                         and b_conf2.get("code") == 200)
    ok_all = (derive_ok and read_ok and read_db_ok and count_decr
              and repeat_read_ok and confirm_ok and confirm_db_ok and repeat_confirm_ok)
    return out, ok_all, "" if ok_all else (
        f"derive={derive_ok}(recv={receivers}) read={read_ok}/{read_db_ok} "
        f"count_decr={count_decr}({cnt_before}→{cnt_after}) repeat_read={repeat_read_ok} "
        f"confirm={confirm_ok}/{confirm_db_ok} repeat_confirm={repeat_confirm_ok}")


# ============================================================
#  Case 05：数据权限
# ============================================================

def case_05_data_permission(sess_res, sess_dl, sess_sci) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    p_own = STATE["p_own"]
    p_other = STATE["p_other"]
    # 5.1 researcher /biz/alert/list → 仅本人相关（P_OWN），不含 P_OTHER
    r_res = http(sess_res, "GET", "/biz/alert/list", params={"pageNum": 1, "pageSize": 200})
    b_res = safe_json(r_res)
    rows_res = tdi_rows(b_res) or []
    own_rows = [r for r in rows_res if r.get("projectId") is not None]
    res_own_only = all(int(r.get("projectId")) == p_own for r in own_rows)
    res_sees_own = any(int(r.get("projectId")) == p_own for r in own_rows)
    res_no_other = not any(int(r.get("projectId")) == p_other for r in own_rows)
    out["res_list"] = {"status_code": r_res.status_code, "total": len(rows_res),
                       "project_ids": sorted({int(r.get("projectId")) for r in own_rows}),
                       "own_only": res_own_only, "sees_own": res_sees_own, "no_other": res_no_other}
    # 5.2 researcher /my/list → 仅自己通知
    r_my = http(sess_res, "GET", "/biz/alert/my/list", params={"pageNum": 1, "pageSize": 200})
    b_my = safe_json(r_my)
    rows_my = tdi_rows(b_my) or []
    my_only = len(rows_my) >= 1 and all(int(r.get("receiverId")) == RES_USER_ID for r in rows_my)
    out["res_my_list"] = {"status_code": r_my.status_code, "count": len(rows_my),
                          "receivers": sorted({int(r.get("receiverId")) for r in rows_my}),
                          "my_only": my_only}
    # 5.3 dept_leader 本室（P_OWN），不含他室（P_OTHER）
    r_dl = http(sess_dl, "GET", "/biz/alert/list", params={"pageNum": 1, "pageSize": 200})
    b_dl = safe_json(r_dl)
    rows_dl = tdi_rows(b_dl) or []
    dl_sees_own = any(int(r.get("projectId")) == p_own for r in rows_dl if r.get("projectId") is not None)
    dl_no_other = not any(int(r.get("projectId")) == p_other for r in rows_dl if r.get("projectId") is not None)
    out["dl_list"] = {"status_code": r_dl.status_code, "total": len(rows_dl),
                      "sees_own": dl_sees_own, "no_other": dl_no_other,
                      "project_ids": sorted({int(r.get("projectId")) for r in rows_dl
                                             if r.get("projectId") is not None})}
    # 5.4 越权 resolve 403（researcher 无 biz:alert:resolve 权限）
    p_other_alert = STATE.get("p_other_alert_id")
    if p_other_alert is None:
        ok_a, rows_a = q_rows("SELECT ALERT_ID FROM RUOYI.ALERT WHERE BIZ_KEY = ? AND DEL_FLAG = '0' "
                              "AND STATUS = 'OPEN'", [f"BUDGET:project:{p_other}"])
        p_other_alert = rows_a[0][0] if rows_a else None
        STATE["p_other_alert_id"] = p_other_alert
    r_rv = http(sess_res, "POST", f"/biz/alert/resolve/{p_other_alert}")
    b_rv = safe_json(r_rv)
    rv_msg = str(b_rv.get("msg") or "") if isinstance(b_rv, dict) else ""
    rv_code = b_rv.get("code") if isinstance(b_rv, dict) else None
    resolve_403 = (r_rv.status_code == 403 or rv_code == 403
                   or (is_business_reject(b_rv) and ("权限" in rv_msg or "无权" in rv_msg)))
    out["res_resolve_other"] = {"status_code": r_rv.status_code, "body": b_rv,
                                "rejected": resolve_403}
    # 5.5 补充：science_admin 全所可见（含 P_OTHER 与遗留 alert）
    r_sci = http(sess_sci, "GET", "/biz/alert/list", params={"pageNum": 1, "pageSize": 200})
    b_sci = safe_json(r_sci)
    rows_sci = tdi_rows(b_sci) or []
    sci_sees_other = any(int(r.get("projectId")) == p_other for r in rows_sci if r.get("projectId") is not None)
    sci_sees_legacy = any(int(r.get("alertId")) == STATE.get("legacy_alert_id") for r in rows_sci)
    out["sci_list"] = {"status_code": r_sci.status_code, "sees_other": sci_sees_other,
                       "sees_legacy": sci_sees_legacy}
    ok_all = (res_own_only and res_sees_own and res_no_other and my_only
              and dl_sees_own and dl_no_other and resolve_403
              and sci_sees_other and sci_sees_legacy)
    return out, ok_all, "" if ok_all else (
        f"res(own_only={res_own_only} sees={res_sees_own} no_other={res_no_other}) "
        f"my_only={my_only} dl(sees={dl_sees_own} no_other={dl_no_other}) "
        f"resolve403={resolve_403} sci(other={sci_sees_other} legacy={sci_sees_legacy})")


# ============================================================
#  Case 06：遗留 BUDGET 兼容（I-1 修复验证）
# ============================================================

def case_06_legacy(sess_admin, sess_sci) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    legacy_id = STATE["legacy_alert_id"]
    # 6.1 /biz/alert/list 能展示（SCI 全所视角）
    r = http(sess_sci, "GET", "/biz/alert/list", params={"pageNum": 1, "pageSize": 200,
                                                         "alertType": "BUDGET"})
    b = safe_json(r)
    rows = tdi_rows(b) or []
    hit = next((x for x in rows if int(x.get("alertId")) == legacy_id), None)
    visible = hit is not None
    out["list_show"] = {"status_code": r.status_code, "hit": hit}
    # 6.2 /resolve 能消除（I-1：status=UNREAD/ref_type=NULL 旧预警可 RESOLVED）
    r2 = http(sess_admin, "POST", f"/biz/alert/resolve/{legacy_id}")
    b2 = safe_json(r2)
    out["resolve"] = {"status_code": r2.status_code, "body": b2}
    resolve_ok = r2.status_code == 200 and isinstance(b2, dict) and b2.get("code") == 200
    ok_d, row_d = q1("SELECT STATUS, REMARK FROM RUOYI.ALERT WHERE ALERT_ID = ?", [legacy_id])
    resolved_ok = (ok_d and row_d is not None and row_d[0] == "RESOLVED" and "admin" in str(row_d[1]))
    ok_all = visible and resolve_ok and resolved_ok
    return out, ok_all, "" if ok_all else (
        f"visible={visible} resolve={resolve_ok} resolved_db={resolved_ok}")


# ============================================================
#  Case 07：对话精灵降级
# ============================================================

def case_07_chat_degrade(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 7.1 /biz/chat/ask → configured=false + 友好文案，无堆栈
    r = http(sess_admin, "POST", "/biz/chat/ask", json_body={"message": "查询一下我的课题"})
    b = safe_json(r)
    data = get_data(b) if isinstance(b, dict) else None
    reply = data.get("reply") if isinstance(data, dict) else None
    configured = data.get("configured") if isinstance(data, dict) else None
    out["ask"] = {"status_code": r.status_code, "body": b, "reply": reply, "configured": configured}
    ask_ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
              and configured is False and isinstance(reply, str)
              and "未配置 LLM" in reply and "Exception" not in str(b)[:2000])
    # 7.2 /biz/chat/session → 200
    r2 = http(sess_admin, "GET", "/biz/chat/session")
    b2 = safe_json(r2)
    out["session"] = {"status_code": r2.status_code, "body": b2}
    session_ok = r2.status_code == 200 and isinstance(b2, dict) and b2.get("code") == 200
    ok_all = ask_ok and session_ok
    return out, ok_all, "" if ok_all else f"ask={ask_ok}(configured={configured},reply={reply}) session={session_ok}"


# ============================================================
#  Case 08：确认卡片链路
# ============================================================

def case_08_confirm(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 8.1 不存在 confirmId → 友好错误（不 500 堆栈）
    r = http(sess_admin, "POST", "/biz/chat/confirm",
             json_body={"confirmId": "smoke-nonexistent-card", "approved": True})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    data = get_data(b) if isinstance(b, dict) else None
    out["confirm_missing"] = {"status_code": r.status_code, "body": b}
    # 契约：不存在 confirmId → code 200 + data.executed=false + 友好 message（非 500）
    missing_ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
                  and isinstance(data, dict) and data.get("executed") is False
                  and ("确认卡片" in str(data) or "过期" in str(data) or "无效" in str(data)))
    # 8.2 空 confirmId → 参数错误
    r2 = http(sess_admin, "POST", "/biz/chat/confirm", json_body={"approved": False})
    b2 = safe_json(r2)
    msg2 = str(b2.get("msg") or "") if isinstance(b2, dict) else ""
    out["confirm_blank"] = {"status_code": r2.status_code, "body": b2}
    blank_ok = (r2.status_code == 200 and isinstance(b2, dict) and b2.get("code") != 200
                and "confirmId" in msg2)
    ok_all = missing_ok and blank_ok
    return out, ok_all, "" if ok_all else f"missing={missing_ok} blank={blank_ok}"


# ============================================================
#  Case 09：回归 — smoke_rd.py 全量重跑（20/20 预期）
# ============================================================

def case_09_regression() -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    os.environ.setdefault("DM_PASSWORD", "Ruoyi12345")
    try:
        import smoke_rd as SR  # noqa: E402
        ret = SR.main()
        out["smoke_rd_rc"] = ret
        ok = ret == 0
    except SystemExit as e:
        out["smoke_rd_rc"] = e.code
        ok = e.code == 0
    except Exception as e:  # noqa: BLE001
        out["smoke_rd_rc"] = repr(e)
        ok = False
    return out, ok, "" if ok else f"smoke_rd rc={out.get('smoke_rd_rc')}"


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

    # 清残留（幂等，仅按 ref 链，不碰存量 alert/notification）+ 快照 + 暂停 Quartz 预警任务
    cleanup_test_refs()
    ok_snap, snap_msg = snapshot_alert_notify()
    record("01_snapshot", ok_snap, {"method": "db"}, {"msg": snap_msg})
    if not ok_snap:
        cleanup_test_data()
        dump_results()
        return 1
    ok_pause, pause_msg = pause_alert_job()
    record("02_pause_job", ok_pause, {"method": "db"}, {"msg": pause_msg})

    # admin 主会话
    sess = make_session("task9-alert-smoke/1.0")
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
        cleanup_test_data()
        dump_results()
        return 1

    def _login_user(uname: str) -> Tuple[Optional[requests.Session], Dict[str, Any]]:
        s = make_session("task9-alert-smoke/1.0")
        t, resp = login(s, uname, ADMIN_PASS)
        if t:
            s.headers.update({"Authorization": "Bearer " + t})
        return (s if t else None), resp

    sess_res, resp_res = _login_user(RES_USERNAME)
    record("11_login_researcher", sess_res is not None,
           {"username": RES_USERNAME}, {"status_code": resp_res.get("status_code")},
           "" if sess_res else str(resp_res)[:300])
    sess_sci, resp_sci = _login_user(SCI_USERNAME)
    record("12_login_sci_admin", sess_sci is not None,
           {"username": SCI_USERNAME}, {"status_code": resp_sci.get("status_code")},
           "" if sess_sci else str(resp_sci)[:300])
    sess_dl, resp_dl = _login_user(DL_USERNAME)
    record("13_login_dept_leader", sess_dl is not None,
           {"username": DL_USERNAME}, {"status_code": resp_dl.get("status_code")},
           "" if sess_dl else str(resp_dl)[:300])
    if not all([sess_res, sess_sci, sess_dl]):
        cleanup_test_data()
        dump_results()
        return 1

    cases = [
        ("00_prepare_v1017",   lambda: case_00_prepare(sess)),
        ("01_contract_scan",   lambda: case_01_contract(sess)),
        ("02_budget_scan",     lambda: case_02_budget(sess)),
        ("03_document_scan",   lambda: case_03_document(sess)),
        ("04_notification",    lambda: case_04_notification(sess, sess_sci)),
        ("05_data_permission", lambda: case_05_data_permission(sess_res, sess_dl, sess_sci)),
        ("06_legacy_budget",   lambda: case_06_legacy(sess, sess_sci)),
        ("07_chat_degrade",    lambda: case_07_chat_degrade(sess)),
        ("08_confirm_card",    lambda: case_08_confirm(sess)),
        ("09_regression_rd",   case_09_regression),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/alert/*"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # 收尾
    cleanup_ok, cleanup_msg = cleanup_test_data()
    record("99_cleanup", cleanup_ok, {"method": "db"}, {"msg": cleanup_msg})

    # DB 零残留复查：alert / notification 回到快照数量；测试业务/用户清零
    ok_c, r_c = q1("SELECT COUNT(*) FROM RUOYI.ALERT")
    alert_cnt = r_c[0] if r_c else None
    ok_n, r_n = q1("SELECT COUNT(*) FROM RUOYI.NOTIFICATION")
    notify_cnt = r_n[0] if r_n else None
    ok_p, r_p = q1("SELECT COUNT(*) FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    proj_left = r_p[0] if r_p else None
    ok_u, r_u = q1("SELECT COUNT(*) FROM RUOYI.SYS_USER WHERE REMARK = ?", [TEST_MARK])
    user_left = r_u[0] if r_u else None
    ok_d, r_d = q1("SELECT COUNT(*) FROM RUOYI.PROJECT_DOCUMENT WHERE REMARK = ?", [TEST_MARK])
    doc_left = r_d[0] if r_d else None
    zero_ok = (alert_cnt == STATE.get("pre_alert_cnt")
               and notify_cnt == STATE.get("pre_notify_cnt")
               and proj_left == 0 and user_left == 0 and doc_left == 0)
    record("98_db_zero_leftover", zero_ok, {"method": "db"},
           {"alert_cnt": alert_cnt, "pre_alert_cnt": STATE.get("pre_alert_cnt"),
            "notify_cnt": notify_cnt, "pre_notify_cnt": STATE.get("pre_notify_cnt"),
            "project_left": proj_left, "user_left": user_left, "doc_left": doc_left})

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
