#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 5 — 阶段5 资料与审批接口冒烟（任务卡 §七 9 项 + 前端契约 C1-C4 实测）
- 覆盖：上传(ARCHIVED 拒)/发起审批/通过/驳回/重报/删除(D7 级联)/审批历史/数据权限(4 角色)/回归
- 复用 smoke_contract.py 登录框架（RSA 登录 / dmPython DB 直查 / 断言风格）
- 结果写 scripts/smoke/result_document.jsonl
- 只测不改业务代码；发现的 Bug 记入报告，返回给控制方裁决
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
from datetime import date
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
RESULT_PATH = os.path.join(SCRIPT_DIR, "result_document.jsonl")

# ============== 测试用户（照 smoke_project.py 的 ID 约定，各自脚本互不冲突） ==============
RES_USER_ID = 90001      # researcher 角色 105, data_scope=5, dept 100
SCI_USER_ID = 90010      # science_admin 角色 101, data_scope=1, dept 100
DL_USER_ID  = 90004      # dept_leader 角色 104, data_scope=3, dept 101
LEADER_B_USER_ID = 90003 # 普通成员（无角色）, dept 100 —— 他室课题主持人
DEPT_D1 = 101            # dept_leader 所属部门（本室）
DEPT_D2 = 100            # 跨部门（越权目标）

RES_USERNAME = "test_researcher"
SCI_USERNAME = "test_sci_admin"
DL_USERNAME  = "test_dept_leader"
LEADER_B_USERNAME = "test_leader_b"

TEST_MARK = "smoke-task5-doc"

RESULTS: List[Dict[str, Any]] = []
STATE: Dict[str, Any] = {}  # 跨用例传递


# ============================================================
#  通用工具（照 smoke_contract.py）
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
    """document 端点带 @RepeatSubmit(interval=2000)，串行调用需停顿。"""
    time.sleep(sec)


# ============================================================
#  测试数据准备 / 清理
# ============================================================

def setup_test_users() -> Tuple[bool, str]:
    """建 test_researcher(105) / test_sci_admin(101) / test_dept_leader(104,dept101) / test_leader_b。"""
    ok, res = db_query("SELECT PASSWORD FROM RUOYI.SYS_USER WHERE USER_NAME='admin'")
    if not ok or not res["rows"]:
        return False, "admin hash 读取失败: " + str(res)
    admin_hash = res["rows"][0][0]
    users = [
        (RES_USER_ID, RES_USERNAME, "冒烟科研人员", 100, 105),
        (SCI_USER_ID, SCI_USERNAME, "冒烟科管", 100, 101),
        (DL_USER_ID,  DL_USERNAME,  "冒烟室主任", DEPT_D1, 104),
        (LEADER_B_USER_ID, LEADER_B_USERNAME, "冒烟无关主持人", 100, None),
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
    """按 TEST_MARK 物理清理：approval_history → approval → project_document → member → project → 用户。"""
    # 1. approval_history（按本批课题下的 approval_id 级联）
    ok, r = db_execute(
        "DELETE FROM RUOYI.APPROVAL_HISTORY WHERE APPROVAL_ID IN "
        "(SELECT APPROVAL_ID FROM RUOYI.APPROVAL WHERE DOC_ID IN "
        "(SELECT DOC_ID FROM RUOYI.PROJECT_DOCUMENT WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)))", [TEST_MARK])
    if not ok:
        return False, "清 approval_history 失败: " + str(r)
    # 2. approval
    ok, r = db_execute(
        "DELETE FROM RUOYI.APPROVAL WHERE DOC_ID IN "
        "(SELECT DOC_ID FROM RUOYI.PROJECT_DOCUMENT WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?))", [TEST_MARK])
    if not ok:
        return False, "清 approval 失败: " + str(r)
    # 3. project_document
    ok, r = db_execute(
        "DELETE FROM RUOYI.PROJECT_DOCUMENT WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清 project_document 失败: " + str(r)
    # 4. project_member / project
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
        [RES_USER_ID, SCI_USER_ID, DL_USER_ID, LEADER_B_USER_ID])
    if not ok:
        return False, "清 user_role 失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?, ?)",
        [RES_USER_ID, SCI_USER_ID, DL_USER_ID, LEADER_B_USER_ID])
    if not ok:
        return False, "清 user 失败: " + str(r)
    return True, "ok"


# ============================================================
#  辅助：建课题 / 上传资料 / 取 docId / 取 approvalId
# ============================================================

def add_project(sess, name: str, leader_id: int, dept_id: int) -> Tuple[Optional[int], Dict[str, Any]]:
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {
        "projectName": name,
        "projectType": "NATIONAL",
        "leaderId": leader_id,
        "projectNo": f"KY-DOC-{(STATE['no_seq'] % 900) + 100:03d}",
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


def upload_document(sess, project_id: int, stage: str, file_name: str,
                    file_url: str = "/upload/smoke/doc.pdf") -> Tuple[Optional[int], Dict[str, Any]]:
    body = {
        "projectId": project_id,
        "stage": stage,
        "fileName": file_name,
        "fileUrl": file_url,
        "planSubmitDate": "2026-09-30",
    }
    sleep_anti_repeat()
    r = http(sess, "POST", "/biz/document", json_body=body)
    b = safe_json(r)
    data = get_data(b)
    doc_id = data.get("docId") if isinstance(data, dict) else None
    return doc_id, {"body": body, "status_code": r.status_code, "resp": b, "docId": doc_id}


def get_doc_detail(sess, doc_id: int) -> Tuple[Optional[Dict], Dict[str, Any]]:
    r = http(sess, "GET", f"/biz/document/{doc_id}")
    b = safe_json(r)
    data = get_data(b)
    return (data if isinstance(data, dict) else None), {"status_code": r.status_code, "resp": b}


def db_approval_by_doc(doc_id: int) -> Tuple[bool, Any]:
    """按 doc_id 查当前有效审批行。"""
    return db_query(
        "SELECT APPROVAL_ID, APPLICANT_ID, APPROVER_ID, STATUS, ROUND, REJECT_REASON, DEL_FLAG "
        "FROM RUOYI.APPROVAL WHERE DOC_ID = ? AND DEL_FLAG = '0'", [doc_id])


def db_history_by_approval(approval_id: int) -> Tuple[bool, Any]:
    return db_query(
        "SELECT ACTION, OPERATOR_ID, COMMENT_TEXT, ROUND, DEL_FLAG "
        "FROM RUOYI.APPROVAL_HISTORY WHERE APPROVAL_ID = ? AND DEL_FLAG = '0' "
        "ORDER BY ROUND ASC, OPERATE_TIME ASC, HISTORY_ID ASC", [approval_id])


# ============================================================
#  Case 00：造课题（主课题/归档课题/他室课题）
# ============================================================

def case_00_prepare_projects(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # P_MAIN：researcher 主持 + 挂 dept 101（本室）——主流程课题
    pid_main, info = add_project(sess_admin, f"{TEST_MARK}-主课题", leader_id=RES_USER_ID, dept_id=DEPT_D1)
    if pid_main is None:
        return {"main": info}, False, "建主课题失败"
    STATE["project_main_id"] = pid_main
    out["main"] = info
    # P_ARCH：researcher 主持 + dept 101，建后直接置 ARCHIVED（绕过状态机，测归档拒传）
    pid_arch, info2 = add_project(sess_admin, f"{TEST_MARK}-归档课题", leader_id=RES_USER_ID, dept_id=DEPT_D1)
    if pid_arch is None:
        return {"arch": info2}, False, "建归档课题失败"
    STATE["project_arch_id"] = pid_arch
    ok, r = db_execute("UPDATE RUOYI.PROJECT SET STATUS = 'ARCHIVED' WHERE PROJECT_ID = ?", [pid_arch])
    if not ok:
        return {"arch": info2, "archived_update": r}, False, "置 ARCHIVED 失败: " + str(r)
    out["arch"] = info2
    # P_OTHER：无关主持人 + dept 100（他室）——越权目标
    pid_other, info3 = add_project(sess_admin, f"{TEST_MARK}-他室课题", leader_id=LEADER_B_USER_ID, dept_id=DEPT_D2)
    if pid_other is None:
        return {"other": info3}, False, "建他室课题失败"
    STATE["project_other_id"] = pid_other
    out["other"] = info3
    ok = pid_main is not None and pid_arch is not None and pid_other is not None
    return out, ok, "" if ok else "建课题未全部成功"


# ============================================================
#  Case 01：上传资料（DRAFT 成功；ARCHIVED 拒；researcher 越权课题拒）
# ============================================================

def case_01_upload(sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 1.1 researcher 传主课题资料 Doc_A（stage=INITIATION）→ 成功
    doc_a, info_a = upload_document(sess_res, STATE["project_main_id"], "INITIATION",
                                    f"{TEST_MARK}-A可行性报告")
    out["doc_a"] = info_a
    if doc_a is None:
        return out, False, "Doc_A 上传失败: " + str(info_a["resp"])[:200]
    STATE["doc_a_id"] = doc_a
    # DB 验证：del_flag='0'、stage、upload_by
    ok, res = db_query(
        "SELECT FILE_NAME, STAGE, DEL_FLAG, UPLOAD_BY FROM RUOYI.PROJECT_DOCUMENT WHERE DOC_ID = ?", [doc_a])
    rows = res["rows"] if ok else []
    db_file = rows[0][0] if rows else None
    db_stage = rows[0][1] if rows else None
    db_del = rows[0][2] if rows else None
    db_uploader = rows[0][3] if rows else None
    db_ok = (db_file == f"{TEST_MARK}-A可行性报告" and db_stage == "INITIATION"
             and db_del == "0" and db_uploader == RES_USERNAME)
    out["doc_a_db"] = {"file_name": db_file, "stage": db_stage, "del_flag": db_del,
                       "upload_by": db_uploader, "ok": db_ok}
    # 1.2 researcher 传归档课题 → 拒（已归档课题不可上传资料）
    doc_arch, info_arch = upload_document(sess_res, STATE["project_arch_id"], "INITIATION",
                                          f"{TEST_MARK}-归档上传")
    b_arch = info_arch["resp"]
    msg_arch = str(b_arch.get("msg") or "") if isinstance(b_arch, dict) else ""
    arch_reject = (doc_arch is None and isinstance(b_arch, dict) and b_arch.get("code") != 200
                   and "已归档课题不可上传资料" in msg_arch)
    out["doc_arch"] = {"resp": b_arch, "msg": msg_arch, "rejected": arch_reject}
    # 1.3 researcher 传他室课题（leader=他人）→ 拒（无权访问）
    doc_other, info_other = upload_document(sess_res, STATE["project_other_id"], "INITIATION",
                                            f"{TEST_MARK}-越权上传")
    b_other = info_other["resp"]
    msg_other = str(b_other.get("msg") or "") if isinstance(b_other, dict) else ""
    other_reject = (doc_other is None and isinstance(b_other, dict) and b_other.get("code") != 200
                    and "无权访问" in msg_other)
    out["doc_other_upload"] = {"resp": b_other, "msg": msg_other, "rejected": other_reject}

    ok = db_ok and arch_reject and other_reject
    return out, ok, "" if ok else f"db_ok={db_ok} arch_reject={arch_reject} other_reject={other_reject}"


# ============================================================
#  Case 02：发起审批（round=1 PENDING + history SUBMIT；重复发起拒）
# ============================================================

def case_02_submit(sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    doc_a = STATE["doc_a_id"]
    # 2.1 submit Doc_A
    sleep_anti_repeat()
    r = http(sess_res, "POST", f"/biz/document/submit/{doc_a}")
    b = safe_json(r)
    submit_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["submit"] = {"status_code": r.status_code, "resp": b}
    if not submit_ok:
        return out, False, "submit 失败: " + str(b)[:200]
    # 2.2 DB：approval round=1 PENDING applicant=researcher + history SUBMIT
    ok, res = db_approval_by_doc(doc_a)
    rows = res["rows"] if ok else []
    if not rows:
        return out, False, "DB 未查到 approval 行"
    ap_id, applicant, approver, status, round_, reject_reason, del_flag = rows[0]
    STATE["doc_a_approval_id"] = ap_id
    db_ap_ok = (status == "PENDING" and round_ == 1 and applicant == RES_USER_ID
                and approver is None and del_flag == "0")
    out["approval_db"] = {"approval_id": ap_id, "applicant_id": applicant, "approver_id": approver,
                          "status": status, "round": round_, "reject_reason": reject_reason,
                          "del_flag": del_flag, "ok": db_ap_ok}
    # submitter_id 回填
    ok2, res2 = db_query("SELECT SUBMITTER_ID FROM RUOYI.PROJECT_DOCUMENT WHERE DOC_ID = ?", [doc_a])
    sub = res2["rows"][0][0] if ok2 and res2["rows"] else None
    submitter_ok = sub == RES_USER_ID
    out["submitter_db"] = {"submitter_id": sub, "ok": submitter_ok}
    # history SUBMIT
    ok3, res3 = db_history_by_approval(ap_id)
    hrows = res3["rows"] if ok3 else []
    hist_submit_ok = (len(hrows) == 1 and hrows[0][0] == "SUBMIT" and hrows[0][1] == RES_USER_ID
                      and hrows[0][3] == 1 and hrows[0][4] == "0")
    out["history_db"] = {"rows": hrows, "ok": hist_submit_ok}
    # 2.3 重复发起 → 拒（已发起审批）
    sleep_anti_repeat()
    r = http(sess_res, "POST", f"/biz/document/submit/{doc_a}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    dup_reject = (isinstance(b, dict) and b.get("code") != 200 and "已发起审批" in msg)
    out["dup_submit"] = {"status_code": r.status_code, "resp": b, "msg": msg, "rejected": dup_reject}
    # 2.4 PENDING 状态重报 → 拒（仅驳回的资料可重报）
    sleep_anti_repeat()
    r = http(sess_res, "POST", f"/biz/document/resubmit/{doc_a}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    pending_resubmit_reject = (isinstance(b, dict) and b.get("code") != 200 and "仅驳回的资料可重报" in msg)
    out["resubmit_pending"] = {"status_code": r.status_code, "resp": b, "msg": msg,
                               "rejected": pending_resubmit_reject}

    ok = submit_ok and db_ap_ok and submitter_ok and hist_submit_ok and dup_reject and pending_resubmit_reject
    return out, ok, "" if ok else (
        f"submit={submit_ok} ap={db_ap_ok} submitter={submitter_ok} hist={hist_submit_ok} "
        f"dup_reject={dup_reject} pending_resubmit={pending_resubmit_reject}")


# ============================================================
#  Case 04：驳回（缺 reason 拒；填 reason → REJECTED + history REJECT）
# ============================================================

def case_04_reject(sess_dl) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    ap_id = STATE["doc_a_approval_id"]
    # 4.1 缺 rejectReason → 拒（驳回原因不能为空）
    sleep_anti_repeat()
    r = http(sess_dl, "PUT", f"/biz/approval/audit/{ap_id}",
             json_body={"action": "REJECT", "comment": "意见-无原因"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    no_reason_reject = (isinstance(b, dict) and b.get("code") != 200 and "驳回原因不能为空" in msg)
    out["reject_no_reason"] = {"status_code": r.status_code, "resp": b, "msg": msg,
                               "rejected": no_reason_reject}
    # 4.2 填 reason 驳回 → REJECTED
    sleep_anti_repeat()
    r = http(sess_dl, "PUT", f"/biz/approval/audit/{ap_id}",
             json_body={"action": "REJECT", "comment": "意见-不通过", "rejectReason": "材料不完整"})
    b = safe_json(r)
    reject_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["reject"] = {"status_code": r.status_code, "resp": b}
    # 4.3 DB：status=REJECTED + reject_reason + history REJECT
    ok, res = db_approval_by_doc(STATE["doc_a_id"])
    rows = res["rows"] if ok else []
    if not rows:
        return out, False, "DB 未查到 approval 行"
    ap2, applicant, approver, status, round_, reject_reason, del_flag = rows[0]
    db_reject_ok = (status == "REJECTED" and round_ == 1 and reject_reason == "材料不完整"
                    and approver == DL_USER_ID and del_flag == "0")
    out["approval_db"] = {"approval_id": ap2, "status": status, "round": round_,
                          "reject_reason": reject_reason, "approver_id": approver,
                          "del_flag": del_flag, "ok": db_reject_ok}
    ok3, res3 = db_history_by_approval(ap_id)
    hrows = res3["rows"] if ok3 else []
    out["history_db"] = {"rows": hrows}
    # 期望 2 条：SUBMIT + REJECT，REJECT 的 operator=dl、comment 正确
    actions = [h[0] for h in hrows]
    reject_hist = [h for h in hrows if h[0] == "REJECT"]
    hist_ok = (len(hrows) == 2 and actions == ["SUBMIT", "REJECT"] and len(reject_hist) == 1
               and reject_hist[0][1] == DL_USER_ID and reject_hist[0][2] == "意见-不通过"
               and reject_hist[0][3] == 1 and reject_hist[0][4] == "0")

    ok = no_reason_reject and reject_ok and db_reject_ok and hist_ok
    return out, ok, "" if ok else (
        f"no_reason_reject={no_reason_reject} reject={reject_ok} db={db_reject_ok} hist={hist_ok}")


# ============================================================
#  Case 05：重报（round=2 PENDING + reject_reason 清空 + history RESUBMIT）
# ============================================================

def case_05_resubmit(sess_res) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    doc_a = STATE["doc_a_id"]
    ap_id = STATE["doc_a_approval_id"]
    # 5.1 resubmit
    sleep_anti_repeat()
    r = http(sess_res, "POST", f"/biz/document/resubmit/{doc_a}")
    b = safe_json(r)
    resubmit_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["resubmit"] = {"status_code": r.status_code, "resp": b}
    if not resubmit_ok:
        return out, False, "resubmit 失败: " + str(b)[:200]
    # 5.2 DB：round=2 PENDING + reject_reason 清空 + applicant=researcher
    ok, res = db_approval_by_doc(doc_a)
    rows = res["rows"] if ok else []
    if not rows:
        return out, False, "DB 未查到 approval 行"
    ap2, applicant, approver, status, round_, reject_reason, del_flag = rows[0]
    db_ok = (status == "PENDING" and round_ == 2 and reject_reason is None
             and applicant == RES_USER_ID and del_flag == "0")
    out["approval_db"] = {"approval_id": ap2, "status": status, "round": round_,
                          "reject_reason": reject_reason, "applicant_id": applicant,
                          "del_flag": del_flag, "ok": db_ok}
    # 5.3 history：SUBMIT/REJECT/RESUBMIT（round 1/1/2）
    ok3, res3 = db_history_by_approval(ap_id)
    hrows = res3["rows"] if ok3 else []
    out["history_db"] = {"rows": hrows}
    actions = [h[0] for h in hrows]
    resubmit_hist = [h for h in hrows if h[0] == "RESUBMIT"]
    hist_ok = (actions == ["SUBMIT", "REJECT", "RESUBMIT"] and len(resubmit_hist) == 1
               and resubmit_hist[0][1] == RES_USER_ID and resubmit_hist[0][3] == 2
               and resubmit_hist[0][4] == "0")
    # 5.4 同一条 approval（未新建）
    same_approval_ok = (ap2 == ap_id)

    ok = resubmit_ok and db_ok and hist_ok and same_approval_ok
    return out, ok, "" if ok else (
        f"resubmit={resubmit_ok} db={db_ok} hist={hist_ok} same={same_approval_ok}")


# ============================================================
#  Case 03：审批通过（APPROVED + history APPROVE；通过后不能再审；C4 契约）
# ============================================================

def case_03_approve(sess_dl) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    doc_a = STATE["doc_a_id"]
    ap_id = STATE["doc_a_approval_id"]
    # 3.0 dept_leader 取详情拿 approvalId（列表不含，前端 workaround）
    data, info = get_doc_detail(sess_dl, doc_a)
    detail_ok = data is not None
    if data is not None:
        approval = data.get("approval")
        ap_from_detail = approval.get("approvalId") if isinstance(approval, dict) else None
        out["detail"] = {"status_code": info["status_code"], "resp": info["resp"],
                         "approval_id_from_detail": ap_from_detail,
                         "approval_status": data.get("approvalStatus"),
                         "approval_round": data.get("approvalRound")}
        id_match = ap_from_detail == ap_id
        out["detail"]["id_match"] = id_match
    else:
        out["detail"] = {"status_code": info["status_code"], "resp": info["resp"]}
        id_match = False
    # 3.1 APPROVE（body 显式 rejectReason=null + comment —— 前端 C4 契约）
    sleep_anti_repeat()
    r = http(sess_dl, "PUT", f"/biz/approval/audit/{ap_id}",
             json_body={"action": "APPROVE", "comment": "意见-同意", "rejectReason": None})
    b = safe_json(r)
    approve_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["approve"] = {"status_code": r.status_code, "resp": b}
    if not approve_ok:
        return out, False, "approve 失败: " + str(b)[:200]
    # 3.2 DB：status=APPROVED round=2 + history APPROVE（round=2）
    ok, res = db_approval_by_doc(doc_a)
    rows = res["rows"] if ok else []
    if not rows:
        return out, False, "DB 未查到 approval 行"
    ap2, applicant, approver, status, round_, reject_reason, del_flag = rows[0]
    db_ok = (status == "APPROVED" and round_ == 2 and approver == DL_USER_ID
             and reject_reason is None and del_flag == "0")
    out["approval_db"] = {"approval_id": ap2, "status": status, "round": round_,
                          "approver_id": approver, "reject_reason": reject_reason,
                          "del_flag": del_flag, "ok": db_ok}
    ok3, res3 = db_history_by_approval(ap_id)
    hrows = res3["rows"] if ok3 else []
    out["history_db"] = {"rows": hrows}
    actions = [h[0] for h in hrows]
    approve_hist = [h for h in hrows if h[0] == "APPROVE"]
    hist_ok = (actions == ["SUBMIT", "REJECT", "RESUBMIT", "APPROVE"] and len(approve_hist) == 1
               and approve_hist[0][1] == DL_USER_ID and approve_hist[0][2] == "意见-同意"
               and approve_hist[0][3] == 2 and approve_hist[0][4] == "0")
    # 3.3 通过后再审 → 拒（当前状态不可审批）
    sleep_anti_repeat()
    r = http(sess_dl, "PUT", f"/biz/approval/audit/{ap_id}",
             json_body={"action": "APPROVE", "comment": "再审"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    reaudit_reject = (isinstance(b, dict) and b.get("code") != 200 and "当前状态不可审批" in msg)
    out["reaudit"] = {"status_code": r.status_code, "resp": b, "msg": msg, "rejected": reaudit_reject}

    ok = detail_ok and id_match and approve_ok and db_ok and hist_ok and reaudit_reject
    return out, ok, "" if ok else (
        f"detail={detail_ok} id_match={id_match} approve={approve_ok} db={db_ok} "
        f"hist={hist_ok} reaudit_reject={reaudit_reject}")


# ============================================================
#  Case 07：审批历史（SUBMIT→REJECT→RESUBMIT→APPROVE 按 round 有序，operator/opinion 正确）
# ============================================================

def case_07_history(sess_dl) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    ap_id = STATE["doc_a_approval_id"]
    r = http(sess_dl, "GET", f"/biz/approval/history/{ap_id}")
    b = safe_json(r)
    arr = get_data(b)
    out["resp"] = {"status_code": r.status_code, "resp": b,
                   "data_is_array": isinstance(arr, list)}
    if not isinstance(arr, list) or len(arr) != 4:
        return out, False, f"history 非 4 条: {str(b)[:300]}"
    seq = [x.get("action") for x in arr]
    rounds = [x.get("round") for x in arr]
    # 期望 round 递增有序：SUBMIT(1)→REJECT(1)→RESUBMIT(2)→APPROVE(2)
    round_ok = rounds == [1, 1, 2, 2] and seq == ["SUBMIT", "REJECT", "RESUBMIT", "APPROVE"]
    # operatorName / commentText 正确
    res_ok = arr[0].get("operatorName") == "冒烟科研人员" and arr[0].get("operatorId") == RES_USER_ID
    dl_ok = (arr[1].get("operatorName") == "冒烟室主任" and arr[1].get("commentText") == "意见-不通过"
             and arr[1].get("operatorId") == DL_USER_ID)
    approve_ok = (arr[3].get("operatorName") == "冒烟室主任" and arr[3].get("commentText") == "意见-同意")
    action_label_ok = (arr[0].get("actionLabel") == "提交" and arr[1].get("actionLabel") == "驳回"
                       and arr[2].get("actionLabel") == "重报" and arr[3].get("actionLabel") == "通过")
    out["timeline"] = {
        "actions": seq, "rounds": rounds, "round_ok": round_ok,
        "res_ok": res_ok, "dl_ok": dl_ok, "approve_ok": approve_ok,
        "action_label_ok": action_label_ok,
        "rows": [{"action": x.get("action"), "round": x.get("round"),
                  "operatorName": x.get("operatorName"), "commentText": x.get("commentText"),
                  "actionLabel": x.get("actionLabel")} for x in arr],
    }
    # actionLabel 是否随响应返回（若字段缺失也算前端问题，记录）
    out["has_action_label"] = all("actionLabel" in x for x in arr)

    ok = round_ok and res_ok and dl_ok and approve_ok and action_label_ok
    return out, ok, "" if ok else (
        f"round_ok={round_ok} res_ok={res_ok} dl_ok={dl_ok} approve_ok={approve_ok} label={action_label_ok}")


# ============================================================
#  Case 06：删除（PENDING/APPROVED 拒删；REJECTED/无审批可删 + 级联软删验证）
# ============================================================

def case_06_delete_rules(sess_admin, sess_res, sess_dl) -> Tuple[Dict[str, Any], bool]:
    """删除规则（D7）。删除操作用 admin（researcher/dept_leader 无 biz:document:remove，任务卡 §2.4）。"""
    out: Dict[str, Any] = {}
    # 6.0 造 Doc_B（PENDING）+ Doc_C（无审批）+ Doc_PEND（PENDING 用于 C2 对照）
    doc_b, ib = upload_document(sess_res, STATE["project_main_id"], "MIDTERM", f"{TEST_MARK}-B中期报告")
    if doc_b is None:
        return {"doc_b": ib}, False, "Doc_B 上传失败"
    STATE["doc_b_id"] = doc_b
    sleep_anti_repeat()
    rb = http(sess_res, "POST", f"/biz/document/submit/{doc_b}")
    out["doc_b_submit"] = {"status_code": rb.status_code, "resp": safe_json(rb)}
    # Doc_PEND：PENDING 保留到 C2
    doc_pend, ip = upload_document(sess_res, STATE["project_main_id"], "CLOSING", f"{TEST_MARK}-PEND结题报告")
    if doc_pend is None:
        return {"doc_pend": ip}, False, "Doc_PEND 上传失败"
    STATE["doc_pend_id"] = doc_pend
    sleep_anti_repeat()
    rp = http(sess_res, "POST", f"/biz/document/submit/{doc_pend}")
    out["doc_pend_submit"] = {"status_code": rp.status_code, "resp": safe_json(rp)}

    # 6.1 PENDING 资料拒删（Doc_B，admin 删）
    sleep_anti_repeat()
    r = http(sess_admin, "DELETE", f"/biz/document/{doc_b}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    pending_del_reject = (isinstance(b, dict) and b.get("code") != 200 and "存在审批中或已通过的审批" in msg)
    out["del_pending"] = {"status_code": r.status_code, "resp": b, "msg": msg,
                          "rejected": pending_del_reject}
    # 6.2 APPROVED 资料拒删（Doc_A，admin 删）
    sleep_anti_repeat()
    r = http(sess_admin, "DELETE", f"/biz/document/{STATE['doc_a_id']}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    approved_del_reject = (isinstance(b, dict) and b.get("code") != 200 and "存在审批中或已通过的审批" in msg)
    out["del_approved"] = {"status_code": r.status_code, "resp": b, "msg": msg,
                           "rejected": approved_del_reject}
    # 6.3 驳回 Doc_B → REJECTED
    ok, res = db_approval_by_doc(doc_b)
    rows = res["rows"] if ok else []
    ap_b = rows[0][0] if rows else None
    if ap_b is None:
        return out, False, "Doc_B 无 approval 行"
    sleep_anti_repeat()
    r = http(sess_dl, "PUT", f"/biz/approval/audit/{ap_b}",
             json_body={"action": "REJECT", "comment": "中期不达标", "rejectReason": "数据不完整"})
    b = safe_json(r)
    reject_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["doc_b_reject"] = {"status_code": r.status_code, "resp": b}
    # 6.4 REJECTED 资料可删（Doc_B，admin 删）→ 级联软删
    sleep_anti_repeat()
    r = http(sess_admin, "DELETE", f"/biz/document/{doc_b}")
    b = safe_json(r)
    del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["del_rejected"] = {"status_code": r.status_code, "resp": b}
    # DB：doc + approval + history 三级 del_flag='2'
    ok1, r1 = db_query("SELECT DEL_FLAG FROM RUOYI.PROJECT_DOCUMENT WHERE DOC_ID = ?", [doc_b])
    doc_del = r1["rows"][0][0] if ok1 and r1["rows"] else None
    ok2, r2 = db_query("SELECT DEL_FLAG FROM RUOYI.APPROVAL WHERE DOC_ID = ?", [doc_b])
    ap_del = r2["rows"][0][0] if ok2 and r2["rows"] else None
    ok3, r3 = db_query(
        "SELECT DEL_FLAG FROM RUOYI.APPROVAL_HISTORY WHERE APPROVAL_ID = ?", [ap_b])
    hist_dels = [r[0] for r in (r3["rows"] if ok3 else [])]
    cascade_ok = (doc_del == "2" and ap_del == "2" and hist_dels
                  and all(x == "2" for x in hist_dels))
    out["cascade_db"] = {"doc_del_flag": doc_del, "approval_del_flag": ap_del,
                         "history_del_flags": hist_dels, "ok": cascade_ok}
    # 6.5 无审批资料可删（Doc_C，admin 删）
    doc_c, ic = upload_document(sess_res, STATE["project_main_id"], "INITIATION", f"{TEST_MARK}-C无审批")
    if doc_c is None:
        return {"doc_c": ic}, False, "Doc_C 上传失败"
    STATE["doc_c_id"] = doc_c
    sleep_anti_repeat()
    r = http(sess_admin, "DELETE", f"/biz/document/{doc_c}")
    b = safe_json(r)
    noap_del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok4, r4 = db_query("SELECT DEL_FLAG FROM RUOYI.PROJECT_DOCUMENT WHERE DOC_ID = ?", [doc_c])
    noap_del_flag = r4["rows"][0][0] if ok4 and r4["rows"] else None
    out["del_no_approval"] = {"status_code": r.status_code, "resp": b,
                              "db_del_flag": noap_del_flag}
    noap_ok = noap_del_ok and noap_del_flag == "2"

    ok = pending_del_reject and approved_del_reject and reject_ok and del_ok \
        and cascade_ok and noap_ok
    return out, ok, "" if ok else (
        f"pending={pending_del_reject} approved={approved_del_reject} reject={reject_ok} "
        f"del={del_ok} cascade={cascade_ok} noap={noap_ok}")


# ============================================================
#  Case 08：数据权限（researcher 本人相关/不可审批；dept_leader 本室/他室；science_admin 全所）
# ============================================================

def case_08_data_permission(sess_res, sess_dl, sess_sci, sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # 8.0 admin 造 Doc_OTHER（他室课题 P_OTHER）并提交 → PENDING
    doc_other, io = upload_document(sess_admin, STATE["project_other_id"], "INITIATION",
                                    f"{TEST_MARK}-他室资料")
    if doc_other is None:
        return {"doc_other_upload": io}, False, "Doc_OTHER 上传失败"
    STATE["doc_other_id"] = doc_other
    sleep_anti_repeat()
    r = http(sess_admin, "POST", f"/biz/document/submit/{doc_other}")
    out["doc_other_submit"] = {"status_code": r.status_code, "resp": safe_json(r)}
    ok, res = db_approval_by_doc(doc_other)
    rows = res["rows"] if ok else []
    if not rows:
        return out, False, "Doc_OTHER 无 approval 行"
    ap_other = rows[0][0]
    STATE["doc_other_approval_id"] = ap_other

    # 8.1 researcher 列表：见 Doc_A（本人课题）不见 Doc_OTHER（他室）
    r = http(sess_res, "GET", "/biz/document/list", params={"pageNum": 1, "pageSize": 100})
    b = safe_json(r)
    rows_res = b.get("rows", []) if isinstance(b, dict) else []
    res_ids = [x.get("docId") for x in rows_res]
    seen_own = STATE["doc_a_id"] in res_ids
    seen_other = doc_other in res_ids
    res_list_ok = seen_own and not seen_other
    out["res_list"] = {"status_code": r.status_code, "doc_ids": res_ids,
                       "seen_own_doc_a": seen_own, "seen_other_doc": seen_other, "ok": res_list_ok}
    # 8.2 researcher 详情他室 → 无权访问
    data, info = get_doc_detail(sess_res, doc_other)
    b = info["resp"]
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    res_detail_forbid = (data is None and isinstance(b, dict) and b.get("code") != 200
                         and "无权访问" in msg)
    out["res_detail_other"] = {"status_code": info["status_code"], "resp": b, "msg": msg,
                               "forbidden": res_detail_forbid}
    # 8.3 researcher 审批 → 403（无 biz:approval:audit）
    sleep_anti_repeat()
    r = http(sess_res, "PUT", f"/biz/approval/audit/{ap_other}",
             json_body={"action": "APPROVE", "comment": "res 尝试"})
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    res_audit_403 = (r.status_code in (200, 403) and c == 403)
    out["res_audit"] = {"status_code": r.status_code, "code": c, "resp": b, "forbidden": res_audit_403}
    # 8.4 researcher 删除他室 → 403（无 biz:document:remove）
    sleep_anti_repeat()
    r = http(sess_res, "DELETE", f"/biz/document/{doc_other}")
    b = safe_json(r)
    c = b.get("code") if isinstance(b, dict) else None
    res_del_403 = (r.status_code in (200, 403) and c == 403)
    out["res_delete"] = {"status_code": r.status_code, "code": c, "resp": b, "forbidden": res_del_403}
    # 8.5 dept_leader 审批他室（dept 100，不在本室 101 范围内）→ 无权访问
    sleep_anti_repeat()
    r = http(sess_dl, "PUT", f"/biz/approval/audit/{ap_other}",
             json_body={"action": "APPROVE", "comment": "dl 越权尝试"})
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    dl_cross_forbid = (isinstance(b, dict) and b.get("code") != 200 and "无权访问" in msg)
    out["dl_audit_other"] = {"status_code": r.status_code, "resp": b, "msg": msg,
                             "forbidden": dl_cross_forbid}
    # 8.6 dept_leader 审批本室 Doc_A 已 APPROVED —— 用 detail 确认本室可见
    data_a, info_a = get_doc_detail(sess_dl, STATE["doc_a_id"])
    dl_own_ok = data_a is not None
    out["dl_detail_own"] = {"status_code": info_a["status_code"], "ok": dl_own_ok,
                            "approval_status": data_a.get("approvalStatus") if data_a else None}
    # 8.7 science_admin 列表全所：见 Doc_A(dept101) + Doc_OTHER(dept100)
    r = http(sess_sci, "GET", "/biz/document/list", params={"pageNum": 1, "pageSize": 100})
    b = safe_json(r)
    rows_sci = b.get("rows", []) if isinstance(b, dict) else []
    sci_ids = [x.get("docId") for x in rows_sci]
    sci_see_a = STATE["doc_a_id"] in sci_ids
    sci_see_other = doc_other in sci_ids
    sci_all_ok = sci_see_a and sci_see_other
    out["sci_list"] = {"status_code": r.status_code, "doc_ids": sci_ids,
                       "see_doc_a": sci_see_a, "see_doc_other": sci_see_other, "ok": sci_all_ok}
    # 8.8 science_admin 跨部门审批 Doc_OTHER → 成功（data_scope=1 全所）
    sleep_anti_repeat()
    r = http(sess_sci, "PUT", f"/biz/approval/audit/{ap_other}",
             json_body={"action": "APPROVE", "comment": "科管全所审批"})
    b = safe_json(r)
    sci_audit_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["sci_audit_other"] = {"status_code": r.status_code, "resp": b}
    ok2, res2 = db_approval_by_doc(doc_other)
    rows2 = res2["rows"] if ok2 else []
    sci_db_ok = rows2 and rows2[0][3] == "APPROVED" and rows2[0][4] == 1
    out["sci_audit_db"] = {"status": rows2[0][3] if rows2 else None,
                           "round": rows2[0][4] if rows2 else None, "ok": sci_db_ok}

    ok = (res_list_ok and res_detail_forbid and res_audit_403 and res_del_403
          and dl_cross_forbid and dl_own_ok and sci_all_ok and sci_audit_ok and sci_db_ok)
    return out, ok, "" if ok else (
        f"res_list={res_list_ok} res_detail={res_detail_forbid} res_audit403={res_audit_403} "
        f"res_del403={res_del_403} dl_cross={dl_cross_forbid} dl_own={dl_own_ok} "
        f"sci_all={sci_all_ok} sci_audit={sci_audit_ok} sci_db={sci_db_ok}")


# ============================================================
#  C1/C2：列表契约实测（admin 视角，全量数据）
# ============================================================

def case_c1_c2_list_contract(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # C1：列表行字段
    r = http(sess_admin, "GET", "/biz/document/list", params={"pageNum": 1, "pageSize": 100})
    b = safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    out["list"] = {"status_code": r.status_code, "total": b.get("total"), "row_count": len(rows)}
    doc_a_row = next((x for x in rows if x.get("docId") == STATE["doc_a_id"]), None)
    if doc_a_row is None:
        return out, False, "列表未含 Doc_A"
    keys = sorted(doc_a_row.keys())
    out["doc_a_row_keys"] = keys
    has_approval_status = "approvalStatus" in doc_a_row
    has_approval_round = "approvalRound" in doc_a_row
    has_reject_reason = "rejectReason" in doc_a_row
    # 前端假设：列表不含 approvalId / submitterName
    no_approval_id = "approvalId" not in doc_a_row
    no_submitter_name = "submitterName" not in doc_a_row
    out["c1"] = {"has_approvalStatus": has_approval_status,
                 "has_approvalRound": has_approval_round,
                 "has_rejectReason": has_reject_reason,
                 "no_approvalId": no_approval_id,
                 "no_submitterName": no_submitter_name,
                 "values": {"approvalStatus": doc_a_row.get("approvalStatus"),
                            "approvalRound": doc_a_row.get("approvalRound"),
                            "rejectReason": doc_a_row.get("rejectReason")}}
    c1_ok = has_approval_status and has_approval_round and has_reject_reason
    # C2：筛选 approvalStatus=APPROVED 只返回已通过
    r2 = http(sess_admin, "GET", "/biz/document/list",
              params={"approvalStatus": "APPROVED", "pageNum": 1, "pageSize": 100})
    b2 = safe_json(r2)
    rows2 = b2.get("rows", []) if isinstance(b2, dict) else []
    out["c2"] = {"status_code": r2.status_code, "rows": [{"docId": x.get("docId"),
                                                          "approvalStatus": x.get("approvalStatus"),
                                                          "approvalRound": x.get("approvalRound")}
                                                         for x in rows2]}
    all_approved = all(x.get("approvalStatus") == "APPROVED" for x in rows2)
    has_doc_a = any(x.get("docId") == STATE["doc_a_id"] for x in rows2)
    no_pending = not any(x.get("docId") == STATE["doc_pend_id"] for x in rows2)
    c2_ok = all_approved and has_doc_a and no_pending
    out["c2"]["all_approved"] = all_approved
    out["c2"]["has_doc_a"] = has_doc_a
    out["c2"]["excludes_pending"] = no_pending

    ok = c1_ok and c2_ok
    return out, ok, "" if ok else f"c1={c1_ok} c2={c2_ok}"


# ============================================================
#  Case 09：回归（smoke_project/contract/expense 关键端点子集）
# ============================================================

def case_09_regression(sess_admin) -> Tuple[Dict[str, Any], bool]:
    out: Dict[str, Any] = {}
    # project list
    r = http(sess_admin, "GET", "/biz/project/list", params={"pageNum": 1, "pageSize": 5})
    b = safe_json(r)
    proj_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["project_list"] = {"status_code": r.status_code, "total": b.get("total")}
    # contract list
    r = http(sess_admin, "GET", "/biz/contract/list", params={"pageNum": 1, "pageSize": 5})
    b = safe_json(r)
    contract_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["contract_list"] = {"status_code": r.status_code, "total": b.get("total")}
    # expense list
    r = http(sess_admin, "GET", "/biz/expense/list", params={"pageNum": 1, "pageSize": 5})
    b = safe_json(r)
    expense_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["expense_list"] = {"status_code": r.status_code, "total": b.get("total")}
    # unit list
    r = http(sess_admin, "GET", "/biz/unit/list", params={"pageNum": 1, "pageSize": 5})
    b = safe_json(r)
    unit_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["unit_list"] = {"status_code": r.status_code, "total": b.get("total")}

    ok = proj_ok and contract_ok and expense_ok and unit_ok
    return out, ok, "" if ok else (
        f"project={proj_ok} contract={contract_ok} expense={expense_ok} unit={unit_ok}")


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
    sess = make_session("task5-doc-smoke/1.0")
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
    sess_res = make_session("task5-doc-smoke/1.0")
    t, resp = login(sess_res, RES_USERNAME, ADMIN_PASS)
    sess_res.headers.update({"Authorization": "Bearer " + t}) if t else None
    record("11_login_researcher", bool(t), {"username": RES_USERNAME},
           {"status_code": resp.get("status_code")}, "" if t else str(resp)[:300])
    sess_dl = make_session("task5-doc-smoke/1.0")
    t, resp = login(sess_dl, DL_USERNAME, ADMIN_PASS)
    sess_dl.headers.update({"Authorization": "Bearer " + t}) if t else None
    record("12_login_dept_leader", bool(t), {"username": DL_USERNAME},
           {"status_code": resp.get("status_code")}, "" if t else str(resp)[:300])
    sess_sci = make_session("task5-doc-smoke/1.0")
    t, resp = login(sess_sci, SCI_USERNAME, ADMIN_PASS)
    sess_sci.headers.update({"Authorization": "Bearer " + t}) if t else None
    record("13_login_sci_admin", bool(t), {"username": SCI_USERNAME},
           {"status_code": resp.get("status_code")}, "" if t else str(resp)[:300])
    if not (sess_res.headers.get("Authorization") and sess_dl.headers.get("Authorization")
            and sess_sci.headers.get("Authorization")):
        cleanup_test_data()
        dump_results()
        return 1

    cases = [
        ("00_prepare_projects", lambda: case_00_prepare_projects(sess)),
        ("01_upload_success_archived_reject", lambda: case_01_upload(sess_res)),
        ("02_submit_flow", lambda: case_02_submit(sess_res)),
        ("04_reject_flow", lambda: case_04_reject(sess_dl)),
        ("05_resubmit_flow", lambda: case_05_resubmit(sess_res)),
        ("03_approve_flow", lambda: case_03_approve(sess_dl)),
        ("07_history_timeline", lambda: case_07_history(sess_dl)),
        ("06_delete_rules", lambda: case_06_delete_rules(sess, sess_res, sess_dl)),
        ("08_data_permission", lambda: case_08_data_permission(sess_res, sess_dl, sess_sci, sess)),
        ("C1_C2_list_contract", lambda: case_c1_c2_list_contract(sess)),
        ("09_regression", lambda: case_09_regression(sess)),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/document|/biz/approval"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # 收尾
    cleanup_ok, cleanup_msg = cleanup_test_data()
    record("99_cleanup", cleanup_ok, {"method": "db"}, {"msg": cleanup_msg})

    # DB 零残留复查
    ok_zero, res_zero = db_query(
        "SELECT COUNT(*) FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    proj_left = res_zero["rows"][0][0] if ok_zero and res_zero["rows"] else None
    ok_zero2, res_zero2 = db_query(
        "SELECT COUNT(*) FROM RUOYI.PROJECT_DOCUMENT WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    doc_left = res_zero2["rows"][0][0] if ok_zero2 and res_zero2["rows"] else None
    ok_zero3, res_zero3 = db_query(
        "SELECT COUNT(*) FROM RUOYI.SYS_USER WHERE REMARK = ?", [TEST_MARK])
    user_left = res_zero3["rows"][0][0] if ok_zero3 and res_zero3["rows"] else None
    zero_ok = (proj_left == 0 and doc_left == 0 and user_left == 0)
    record("98_db_zero_leftover", zero_ok, {"method": "db"},
           {"project_left": proj_left, "doc_left": doc_left, "user_left": user_left})

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
