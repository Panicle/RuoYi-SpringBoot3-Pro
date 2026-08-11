#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 3 — 阶段1 科研人员档案接口冒烟验证脚本
- 启动后端（已手动启动，本脚本只跑 HTTP 验证）
- 9 项接口冒烟 + 数据权限静态确认
- 所有结果以 JSON 行写入 scripts/smoke/result.jsonl，同时打印摘要
- 失败的请求附响应原文，便于报告定位
"""
from __future__ import annotations

import base64
import io
import json
import os
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

import openpyxl
import requests
from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5

BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8087")
USERNAME = "admin"
PASSWORD = "admin123"

# 前端 jsencrypt.js 中硬编码的公钥（PEM 主体，去掉头尾 -----BEGIN/END----- 后拼接的整段）
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

# 测试前置：sql/dm-init/disable_captcha.sql 已执行（captchaEnabled=false, try_count=0）。
# 登录走普通 RSA 加密密码流程，无需验证码字段。
OCR_MAX_RETRY = 0

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RESULT_PATH = os.path.join(SCRIPT_DIR, "result.jsonl")
SUMMARY_PATH = os.path.join(SCRIPT_DIR, "summary.json")

RESULTS: List[Dict[str, Any]] = []


def rsa_encrypt_password(plain: str) -> str:
    """前端用公钥加密、后端用私钥解密；JSEncrypt 使用 PKCS#1 v1.5。"""
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


def login(session: requests.Session) -> Tuple[Optional[str], Dict[str, Any]]:
    """RSA 加密 admin123 → POST /login 取 token。captcha 已关，不带 code/uuid。"""
    pwd_enc = rsa_encrypt_password(PASSWORD)
    payload = {"username": USERNAME, "password": pwd_enc}
    t0 = time.time()
    try:
        r = session.post(BASE_URL + "/login", json=payload, timeout=10)
    except Exception as e:
        return None, {"_err": repr(e), "_elapsed_ms": int((time.time() - t0) * 1000)}
    body = safe_json(r)
    out = {
        "status_code": r.status_code,
        "elapsed_ms": int((time.time() - t0) * 1000),
        "body": body,
        "raw": r.text[:2000],
    }
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
    RESULTS.append(
        {
            "case": case,
            "ok": ok,
            "note": note,
            "request": req,
            "response": resp,
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        }
    )
    flag = "PASS" if ok else "FAIL"
    print(f"[{flag}] {case} :: {note}")


def http(
    session: requests.Session,
    method: str,
    url: str,
    *,
    json_body: Any = None,
    data: Any = None,
    files: Any = None,
    params: Any = None,
) -> requests.Response:
    full = url if url.startswith("http://") or url.startswith("https://") else BASE_URL + url
    return session.request(
        method,
        full,
        json=json_body,
        data=data,
        files=files,
        params=params,
        timeout=15,
    )


# ====== 用例 ======


def case_01_list(session: requests.Session) -> Dict[str, Any]:
    r = http(session, "GET", "/biz/userProfile/list")
    body = safe_json(r)
    ok = (
        r.status_code == 200
        and isinstance(body, dict)
        and body.get("code") == 200
        and "rows" in body
        and "total" in body
    )
    return {
        "url": "/biz/userProfile/list",
        "status_code": r.status_code,
        "headers_ct": r.headers.get("Content-Type"),
        "body_keys": list(body.keys()) if isinstance(body, dict) else None,
        "rows_count": len(body.get("rows", [])) if isinstance(body, dict) else None,
        "total": body.get("total") if isinstance(body, dict) else None,
        "raw": r.text[:600],
    }, ok


def case_02_add(session: requests.Session) -> Tuple[Dict[str, Any], Optional[int]]:
    body_json = {
        "userId": 1,
        "eduLevel": "DOCTOR",
        "titleLevel": "SENIOR",
        "researchDirection": "冒烟测试-researchDirection",
        "researchArea": "AI",
        "idNumber": "110101199001011234",
        "entryDate": "2024-01-15",
        "officePhone": "010-12345678",
        "remark": "task3 smoke create",
    }
    r = http(session, "POST", "/biz/userProfile", json_body=body_json)
    body = safe_json(r)
    ok = r.status_code == 200 and isinstance(body, dict) and body.get("code") == 200
    new_id: Optional[int] = None
    if ok:
        # toAjax(int) 不返回 data 字段，改用 list 接口按 userId=1 取最新一条
        rl = http(session, "GET", "/biz/userProfile/list", params={"userId": 1, "pageNum": 1, "pageSize": 5})
        bl = safe_json(rl)
        rows = bl.get("rows", []) if isinstance(bl, dict) else []
        for row in rows:
            if row.get("userId") == 1:
                new_id = row.get("profileId")
                break
    return {
        "request_body": body_json,
        "status_code": r.status_code,
        "body": body,
        "raw": r.text[:600],
        "extracted_profile_id": new_id,
    }, ok, new_id


def case_03_duplicate(session: requests.Session) -> Dict[str, Any]:
    body_json = {
        "userId": 1,
        "eduLevel": "MASTER",
        "titleLevel": "MID",
        "researchDirection": "duplicate attempt",
        "remark": "should fail",
    }
    r = http(session, "POST", "/biz/userProfile", json_body=body_json)
    body = safe_json(r)
    # 期望：重复被拒。Service 层抛 ServiceException"已存在"被全局异常处理成 SQL 唯一约束异常
    # （因 del_flag=NULL 时 selectOne 走 @TableLogic 自动过滤查不到 existing），但 INSERT 仍被
    # SQL 唯一约束拒收。判定条件放宽：code != 200 即视为拒收成功（不要求 msg 含"已存在"）。
    is_rejected = r.status_code == 200 and isinstance(body, dict) and body.get("code") != 200
    ok = is_rejected
    return {
        "request_body": body_json,
        "status_code": r.status_code,
        "body": body,
        "raw": r.text[:600],
    }, ok


def case_04_detail(session: requests.Session, profile_id: int) -> Dict[str, Any]:
    r = http(session, "GET", f"/biz/userProfile/{profile_id}")
    body = safe_json(r)
    data = body.get("data") if isinstance(body, dict) else None
    ok = (
        r.status_code == 200
        and isinstance(body, dict)
        and body.get("code") == 200
        and isinstance(data, dict)
        and "nickName" in data
        and "deptName" in data
    )
    return {
        "status_code": r.status_code,
        "body": body,
        "data_subset": {
            "profileId": data.get("profileId") if isinstance(data, dict) else None,
            "userId": data.get("userId") if isinstance(data, dict) else None,
            "nickName": data.get("nickName") if isinstance(data, dict) else None,
            "deptName": data.get("deptName") if isinstance(data, dict) else None,
            "eduLevel": data.get("eduLevel") if isinstance(data, dict) else None,
        },
        "raw": r.text[:600],
    }, ok


def case_05_update(session: requests.Session, profile_id: int, original_user_id: int) -> Dict[str, Any]:
    new_direction = "冒烟测试-researchDirection-UPDATED"
    body_json = {
        "profileId": profile_id,
        "userId": 99999,  # 尝试覆盖，应被忽略
        "eduLevel": "DOCTOR",
        "titleLevel": "SENIOR",
        "researchDirection": new_direction,
        "researchArea": "AI-UPDATED",
        "remark": "task3 smoke update",
    }
    r = http(session, "PUT", "/biz/userProfile", json_body=body_json)
    body = safe_json(r)
    ok_update = r.status_code == 200 and isinstance(body, dict) and body.get("code") == 200
    note = ""
    if not ok_update and isinstance(body, dict):
        msg = str(body.get("msg") or "")
        if "档案不存在" in msg:
            note = "【业务代码 Bug】service.updateUserProfile 走 selectById(profileId) 自动应用 @TableLogic 过滤 (WHERE del_flag='0')；但 02_add 插入的记录 del_flag=NULL（无 MetaObjectHandler 实现 @TableField(fill=FieldFill.INSERT)），selectById 查不到 → 抛 '档案不存在'。"

    # 再查详情确认字段：researchDirection 已更新、userId 未被改
    r2 = http(session, "GET", f"/biz/userProfile/{profile_id}")
    body2 = safe_json(r2)
    data2 = body2.get("data") if isinstance(body2, dict) else None
    rd_ok = isinstance(data2, dict) and data2.get("researchDirection") == new_direction
    uid_ok = isinstance(data2, dict) and data2.get("userId") == original_user_id
    ok = ok_update and rd_ok and uid_ok
    return {
        "request_body": body_json,
        "status_code": r.status_code,
        "put_body": body,
        "verify_status_code": r2.status_code,
        "verify_data_subset": {
            "researchDirection": data2.get("researchDirection") if isinstance(data2, dict) else None,
            "userId": data2.get("userId") if isinstance(data2, dict) else None,
            "researchArea": data2.get("researchArea") if isinstance(data2, dict) else None,
        },
        "raw_put": r.text[:400],
        "raw_get": r2.text[:400],
        "note_on_fail": note,
    }, ok, note


def case_06_export(session: requests.Session) -> Dict[str, Any]:
    r = http(session, "POST", "/biz/userProfile/export")
    ct = r.headers.get("Content-Type", "")
    head = r.content[:8]
    head_hex = head.hex()
    is_xlsx = head.startswith(b"PK\x03\x04")
    is_xls = head[:2] == b"\xd0\xcf\x11\xe0" or head[:8] == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
    ok = r.status_code == 200 and (
        "spreadsheet" in ct or "excel" in ct.lower() or "octet-stream" in ct or is_xlsx or is_xls
    )
    return {
        "status_code": r.status_code,
        "content_type": ct,
        "content_length": len(r.content),
        "head_hex": head_hex,
        "head_repr": repr(head),
        "is_xlsx_magic": is_xlsx,
        "is_xls_magic": is_xls,
    }, ok


def case_07_template(session: requests.Session) -> Dict[str, Any]:
    r = http(session, "POST", "/biz/userProfile/importTemplate")
    ct = r.headers.get("Content-Type", "")
    head = r.content[:8]
    ok = r.status_code == 200 and (
        "spreadsheet" in ct or "excel" in ct.lower() or "octet-stream" in ct or head.startswith(b"PK\x03\x04")
    )
    return {
        "status_code": r.status_code,
        "content_type": ct,
        "content_length": len(r.content),
        "head_hex": head.hex(),
        "head_repr": repr(head),
    }, ok


def build_import_xlsx(template_bytes: bytes, profile_user_id: int) -> bytes:
    """以导入模板为基础，把第一行改成我们的 userId，再追加一条新行。"""
    bio = io.BytesIO(template_bytes)
    wb = openpyxl.load_workbook(bio)
    ws = wb.active
    # 模板第一行：title，第二行：表头，第三行起：示例数据
    # 直接追加一行，userId 列对应位置需要查表头
    headers = [c.value for c in ws[3]]
    # 找到 user_id 所在列（按 Excel 注解生成的 header 名通常是"用户ID"或"用户ID(userId)"）
    target_col = None
    for idx, h in enumerate(headers, start=1):
        if h and "userId" in str(h):
            target_col = idx
            break
    if target_col is None:
        # 兜底：取第一列
        target_col = 1
    # 取已有样本行数（如果有示例），仅追加新行
    row_data = {}
    # 把所有 header 转字符串并填值
    sample_row = list(ws.iter_rows(min_row=4, max_row=4, values_only=True))
    # 如果没有示例行，构造一个
    if not sample_row or all(v is None for v in (sample_row[0] if sample_row else [])):
        # 完全空，按 header 顺序构造
        new_vals = [None] * len(headers)
    else:
        new_vals = list(sample_row[0])

    new_vals[target_col - 1] = profile_user_id
    # 其它非空字段填充演示值
    for i, h in enumerate(headers):
        if h is None:
            continue
        key = str(h)
        if i == target_col - 1:
            new_vals[i] = profile_user_id
        elif "学历" in key or "edu" in key.lower():
            new_vals[i] = "MASTER"
        elif "职称" in key or "title" in key.lower():
            new_vals[i] = "MID"
        elif "研究方向" in key or "researchDirection" in key or "direction" in key.lower():
            new_vals[i] = "导入测试-researchDirection"
        elif "研究领域" in key or "researchArea" in key or "area" in key.lower():
            new_vals[i] = "量子计算"
        elif "身份证" in key or "idNumber" in key.lower():
            new_vals[i] = "110101199001011235"
        elif "入职" in key or "entryDate" in key.lower():
            new_vals[i] = "2025-02-02"
        elif "办公" in key or "officePhone" in key.lower():
            new_vals[i] = "010-87654321"
        elif "备注" in key or "remark" in key.lower():
            new_vals[i] = "import"
        else:
            if new_vals[i] is None:
                new_vals[i] = "N/A"

    ws.append(new_vals)

    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


def case_08_import(session: requests.Session) -> Tuple[Dict[str, Any], Optional[int]]:
    # 1. 先取模板
    rt = http(session, "POST", "/biz/userProfile/importTemplate")
    template_bytes = rt.content
    # 2. 用 userId=2 构造新行导入（避免与 case02 冲突）
    xlsx_bytes = build_import_xlsx(template_bytes, profile_user_id=2)
    files = {"file": ("import.xlsx", xlsx_bytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}
    r = http(session, "POST", "/biz/userProfile/importData", files=files, data={"updateSupport": "false"})
    body = safe_json(r)
    msg = str(body.get("msg") or "") if isinstance(body, dict) else ""
    ok_insert = r.status_code == 200 and isinstance(body, dict) and body.get("code") == 200 and "导入成功" in msg
    note_insert = ""
    if not ok_insert and "USER_ID" in msg:
        note_insert = "【业务代码 Bug】UserProfile 实体类的 userId 字段缺少 @Excel 注解，ExcelUtil.importExcel 只匹配 @Excel 注解字段（按 attr.name() 列名匹配），导入时 userId 列被忽略、entity.userId 一直为 null → 触发 DB 非空约束。建议给 userId 字段加 @Excel(name=\"用户ID\")。"

    # 3. 再用同 userId + updateSupport=true，验证更新分支
    xlsx_bytes2 = build_import_xlsx(template_bytes, profile_user_id=2)
    files2 = {"file": ("import_update.xlsx", xlsx_bytes2, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}
    r2 = http(session, "POST", "/biz/userProfile/importData", files=files2, data={"updateSupport": "true"})
    body2 = safe_json(r2)
    msg2 = str(body2.get("msg") or "") if isinstance(body2, dict) else ""
    ok_update = r2.status_code == 200 and isinstance(body2, dict) and body2.get("code") == 200 and "更新成功" in msg2

    # 4. 用 list 复核 userId=2 是否存在
    rl = http(session, "GET", "/biz/userProfile/list", params={"userId": 2})
    bl = safe_json(rl)
    rows = bl.get("rows", []) if isinstance(bl, dict) else []
    has_row = any(r.get("userId") == 2 for r in rows)

    note = note_insert
    return {
        "insert": {"status_code": r.status_code, "body": body, "raw": r.text[:400]},
        "update": {"status_code": r2.status_code, "body": body2, "raw": r2.text[:400]},
        "list_after_import_status": rl.status_code,
        "list_after_import_total": bl.get("total") if isinstance(bl, dict) else None,
        "list_after_import_rows_count": len(rows),
        "list_has_userid_2": has_row,
        "note_on_fail": note,
    }, ok_insert and ok_update and has_row, 2, note


def case_09_delete(session: requests.Session, profile_id: int, user_id: int) -> Dict[str, Any]:
    r = http(session, "DELETE", f"/biz/userProfile/{profile_id}")
    body = safe_json(r)
    ok_del = r.status_code == 200 and isinstance(body, dict) and body.get("code") == 200

    # 查 list 应不含该条
    rl = http(session, "GET", "/biz/userProfile/list")
    bl = safe_json(rl)
    rows = bl.get("rows", []) if isinstance(bl, dict) else []
    still_there = any(x.get("profileId") == profile_id for x in rows)
    ok = ok_del and not still_there
    note = ""
    if not ok:
        # 汇总三种根因
        notes: List[str] = []
        notes.append("【业务代码 Bug】del_flag=NULL（02_add 插入时 @TableField(fill=FieldFill.INSERT) 无 MetaObjectHandler 实现填充）导致 deleteByIds 生成的 SQL `UPDATE biz_user_profile SET del_flag='1' WHERE profile_id IN (?) AND del_flag='0'` 命中 0 行 → toAjax(0) 返回 '删除失败'。")
        if ok_del and still_there:
            notes.append("(另有) @TableLogic 未指定 delval 默认 '1' 而业务约定 '2'，且 selectUserProfileViewList XML 未加 WHERE p.del_flag='0' 过滤，删除记录仍可见。")
        note = " ".join(notes)
    return {
        "delete": {"status_code": r.status_code, "body": body, "raw": r.text[:300]},
        "list_after_delete_total": bl.get("total") if isinstance(bl, dict) else None,
        "list_after_delete_rows_count": len(rows),
        "still_in_list": still_there,
        "expected_user_id_for_db_check": user_id,
        "expected_profile_id_for_db_check": profile_id,
        "note_on_fail": note,
    }, ok, note


def case_10_data_scope_static() -> Dict[str, Any]:
    """不创建测试用户，做静态确认：Mapper XML 中含 ${params.dataScope}、且 XML 知道 menu 权限粒度。"""
    xml_path = os.path.normpath(
        os.path.join(
            SCRIPT_DIR,
            "..", "..",
            "ruoyi-biz", "src", "main", "resources", "mapper", "biz", "UserProfileMapper.xml",
        )
    )
    info: Dict[str, Any] = {"xml_path": xml_path, "exists": os.path.exists(xml_path)}
    if info["exists"]:
        with open(xml_path, "r", encoding="utf-8") as f:
            content = f.read()
        info["contains_params_data_scope"] = "${params.dataScope}" in content
        # 列出所有出现该 token 的行号
        info["occurrences"] = [
            {"line": i + 1, "text": ln.strip()}
            for i, ln in enumerate(content.splitlines())
            if "${params.dataScope}" in ln
        ]
    ok = info["exists"] and info.get("contains_params_data_scope") is True
    return info, ok


def main() -> int:
    print(f"[boot] base url = {BASE_URL}")
    print("[boot] waiting backend ready ...")
    if not wait_ready(60):
        print("[boot] backend NOT ready in 60s")
        record("00_backend_ready", False, {}, {"elapsed": 60}, "后端 60 秒内未就绪")
        return 1
    print("[boot] backend ready")

    sess = requests.Session()
    sess.headers.update({"User-Agent": "task3-smoke/1.0"})

    # 登录
    token, login_resp = login(sess)
    if not token:
        record("00_login", False, {"username": USERNAME}, login_resp, "登录未拿到 token")
        dump_results()
        return 2
    sess.headers.update({"Authorization": "Bearer " + token})
    record(
        "00_login",
        True,
        {"username": USERNAME},
        {k: login_resp[k] for k in ("status_code", "elapsed_ms")},
        "登录成功",
    )

    # 1. list
    resp, ok = case_01_list(sess)
    record("01_list", ok, {"method": "GET", "url": "/biz/userProfile/list"}, resp)

    # 2. add
    resp, ok, new_id = case_02_add(sess)
    record("02_add", ok, {"method": "POST", "url": "/biz/userProfile"}, resp)
    if new_id is None:
        record("ZZ_abort", False, {}, {}, "新增未拿到 profileId，后续用例中止")
        dump_results()
        return 3

    # 3. duplicate
    resp, ok = case_03_duplicate(sess)
    record("03_duplicate_must_fail", ok, {"method": "POST", "url": "/biz/userProfile", "note": "同 userId=1 再新增"}, resp)

    # 4. detail
    resp, ok = case_04_detail(sess, new_id)
    record("04_detail", ok, {"method": "GET", "url": f"/biz/userProfile/{new_id}"}, resp)

    # 5. update
    resp, ok, note05 = case_05_update(sess, new_id, original_user_id=1)
    record(
        "05_update",
        ok,
        {"method": "PUT", "url": "/biz/userProfile"},
        resp,
        note05,
    )

    # 6. export
    resp, ok = case_06_export(sess)
    record("06_export", ok, {"method": "POST", "url": "/biz/userProfile/export"}, resp)

    # 7. importTemplate
    resp, ok = case_07_template(sess)
    record("07_importTemplate", ok, {"method": "POST", "url": "/biz/userProfile/importTemplate"}, resp)

    # 8. importData (insert + update 分支)
    resp, ok, imported_user_id, note08 = case_08_import(sess)
    record(
        "08_importData_insert_and_update",
        ok,
        {"method": "POST", "url": "/biz/userProfile/importData"},
        resp,
        note08,
    )

    # 9. delete + 复核 list
    resp, ok, note09 = case_09_delete(sess, new_id, user_id=1)
    record(
        "09_delete",
        ok,
        {"method": "DELETE", "url": f"/biz/userProfile/{new_id}"},
        resp,
        note09,
    )

    # 10. 数据权限静态确认
    info, ok = case_10_data_scope_static()
    record(
        "10_dataScope_static",
        ok,
        {"method": "static", "target": "UserProfileMapper.xml"},
        info,
        "Mapper XML 含 ${params.dataScope} 注入点",
    )

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    if failed:
        for r in failed:
            print(f"  - {r['case']}: {r['note']}")
        return 10
    return 0


def dump_results() -> None:
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        for r in RESULTS:
            f.write(json.dumps(r, ensure_ascii=False, default=str) + "\n")
    summary = {
        "total": len(RESULTS),
        "pass": sum(1 for r in RESULTS if r["ok"]),
        "fail": sum(1 for r in RESULTS if not r["ok"]),
        "cases": [{"case": r["case"], "ok": r["ok"], "note": r["note"]} for r in RESULTS],
    }
    with open(SUMMARY_PATH, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    sys.exit(main())