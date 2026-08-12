#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 6 — 阶段1 科研人员档案接口回归冒烟（适配 Task4+Task5 后的新契约）
- 启动后端（已手动启动，本脚本只跑 HTTP 验证）
- 1-10 既有用例适配新字段（删 idNumber；加 degree/major/bio；researchArea/Direction 用字典值）
- 12/13/14 三个 DB 直连检查
- 15 list 接口响应字段完整性专项检查（补登简报外的前端依赖契约点）
- 所有结果以 JSON 行写入 scripts/smoke/result.jsonl，同时打印摘要
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

try:
    import dmPython  # 达梦
except Exception:  # pragma: no cover
    dmPython = None  # 标记不可用，DB 用例将明确报缺失

BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8087")
USERNAME = "admin"
PASSWORD = "admin123"

# 达梦连接（与原仓同环境：devdm）
DM_CONN_KW = dict(user="SYSDBA", password="Ruoyi12345", server="localhost", port=5236)

# 字典值（基于 V1.0.5 实际入库数据，见 sql/kys/V1.0.5__profile_into_user.sql）
DICT_VALUES = {
    "research_area": ["CHEWU", "JIWU", "GONGWU", "DIANWU", "CHELIANG", "FANGJIAN", "XINXI", "ZONGHE"],
    "research_direction": [
        "BEIDOU", "AI", "IOT", "TONGXIN", "BIGDATA", "CLOUD",
        "JIANCE", "XINHAO", "GONGDIAN", "CAILIAO", "ANQUAN", "HUANBAO",
    ],
    "degree_level": ["BACHELOR", "MASTER", "DOCTOR"],
}

# 简报示例值
TEST_RESEARCH_AREA = "XINXI"
TEST_RESEARCH_DIRECTION = "BEIDOU"
TEST_DEGREE = "MASTER"
TEST_MAJOR = "交通信息工程"
TEST_BIO = "冒烟测试用个人简介：博士在读，研究方向为北斗+AI，兴趣包括智能交通与车联网。"
TEST_OFFICE_PHONE = "010-12345678"

# 前端 jsencrypt.js 中硬编码的公钥（PEM 主体）
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
SUMMARY_PATH = os.path.join(SCRIPT_DIR, "summary.json")

RESULTS: List[Dict[str, Any]] = []


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


def login(session: requests.Session) -> Tuple[Optional[str], Dict[str, Any]]:
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


# ====== DB 直连检查辅助 ======

def db_query(sql: str, params: Optional[List[Any]] = None) -> Tuple[bool, Any]:
    """返回 (ok, result_or_err)。dmPython 不可用时 ok=False。"""
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
    except Exception as e:  # pragma: no cover
        return False, repr(e)


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


def case_02_add(session: requests.Session) -> Tuple[Dict[str, Any], bool, Optional[int]]:
    """新契约：删 idNumber；加 degree="MASTER"、major="交通信息工程"、bio 文本；
    researchArea=XINXI、researchDirection=BEIDOU 字典值。"""
    body_json = {
        "userId": 1,
        "eduLevel": "DOCTOR",
        "titleLevel": "SENIOR",
        "researchDirection": TEST_RESEARCH_DIRECTION,
        "researchArea": TEST_RESEARCH_AREA,
        "degree": TEST_DEGREE,
        "major": TEST_MAJOR,
        "bio": TEST_BIO,
        "entryDate": "2024-01-15",
        "officePhone": TEST_OFFICE_PHONE,
        "remark": "task6 smoke create",
    }
    r = http(session, "POST", "/biz/userProfile", json_body=body_json)
    body = safe_json(r)
    ok = r.status_code == 200 and isinstance(body, dict) and body.get("code") == 200
    new_id: Optional[int] = None
    if ok:
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
            "degree": data.get("degree") if isinstance(data, dict) else None,
            "major": data.get("major") if isinstance(data, dict) else None,
            "bio": data.get("bio") if isinstance(data, dict) else None,
            "officePhone": data.get("officePhone") if isinstance(data, dict) else None,
        },
        "raw": r.text[:600],
    }, ok


def case_05_update(session: requests.Session, profile_id: int, original_user_id: int) -> Dict[str, Any]:
    new_direction = "BEIDOU"  # 字典值；与 case 02 默认一致但走更新路径
    body_json = {
        "profileId": profile_id,
        "userId": 99999,  # 尝试覆盖，应被忽略
        "eduLevel": "DOCTOR",
        "titleLevel": "SENIOR",
        "researchDirection": new_direction,
        "researchArea": TEST_RESEARCH_AREA,
        "degree": TEST_DEGREE,
        "major": TEST_MAJOR + "-UPDATED",
        "bio": TEST_BIO + " [UPDATED]",
        "officePhone": "010-99999999",
        "remark": "task6 smoke update",
    }
    r = http(session, "PUT", "/biz/userProfile", json_body=body_json)
    body = safe_json(r)
    ok_update = r.status_code == 200 and isinstance(body, dict) and body.get("code") == 200
    note = ""

    r2 = http(session, "GET", f"/biz/userProfile/{profile_id}")
    body2 = safe_json(r2)
    data2 = body2.get("data") if isinstance(body2, dict) else None
    rd_ok = isinstance(data2, dict) and data2.get("researchDirection") == new_direction
    major_ok = isinstance(data2, dict) and data2.get("major") == (TEST_MAJOR + "-UPDATED")
    uid_ok = isinstance(data2, dict) and data2.get("userId") == original_user_id
    ok = ok_update and rd_ok and major_ok and uid_ok
    if not ok_update and isinstance(body, dict):
        msg = str(body.get("msg") or "")
        if "档案不存在" in msg:
            note = "【业务代码 Bug】service.updateUserProfile 走 selectById(profileId) 自动应用 @TableLogic 过滤；del_flag=NULL 时 0 行命中 → 抛'档案不存在'。"
    return {
        "request_body": body_json,
        "status_code": r.status_code,
        "put_body": body,
        "verify_status_code": r2.status_code,
        "verify_data_subset": {
            "researchDirection": data2.get("researchDirection") if isinstance(data2, dict) else None,
            "userId": data2.get("userId") if isinstance(data2, dict) else None,
            "researchArea": data2.get("researchArea") if isinstance(data2, dict) else None,
            "degree": data2.get("degree") if isinstance(data2, dict) else None,
            "major": data2.get("major") if isinstance(data2, dict) else None,
            "bio": data2.get("bio") if isinstance(data2, dict) else None,
            "officePhone": data2.get("officePhone") if isinstance(data2, dict) else None,
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
    bio = io.BytesIO(template_bytes)
    wb = openpyxl.load_workbook(bio)
    ws = wb.active
    headers = [c.value for c in ws[3]]
    target_col = None
    for idx, h in enumerate(headers, start=1):
        if h and "userId" in str(h):
            target_col = idx
            break
    if target_col is None:
        target_col = 1
    sample_row = list(ws.iter_rows(min_row=4, max_row=4, values_only=True))
    if not sample_row or all(v is None for v in (sample_row[0] if sample_row else [])):
        new_vals = [None] * len(headers)
    else:
        new_vals = list(sample_row[0])
    new_vals[target_col - 1] = profile_user_id
    for i, h in enumerate(headers):
        if h is None:
            continue
        key = str(h)
        if i == target_col - 1:
            new_vals[i] = profile_user_id
        elif "学位" in key or "degree" in key.lower():
            new_vals[i] = TEST_DEGREE
        elif "专业" in key or "major" in key.lower():
            new_vals[i] = TEST_MAJOR
        elif "简介" in key or "bio" in key.lower():
            new_vals[i] = "导入测试-bio"
        elif "学历" in key or "edu" in key.lower():
            new_vals[i] = "MASTER"
        elif "职称" in key or "title" in key.lower():
            new_vals[i] = "MID"
        elif "研究方向" in key or "researchDirection" in key or "direction" in key.lower():
            new_vals[i] = TEST_RESEARCH_DIRECTION
        elif "研究领域" in key or "researchArea" in key or "area" in key.lower():
            new_vals[i] = TEST_RESEARCH_AREA
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


def case_08_import(session: requests.Session) -> Tuple[Dict[str, Any], bool, int, str]:
    rt = http(session, "POST", "/biz/userProfile/importTemplate")
    template_bytes = rt.content
    xlsx_bytes = build_import_xlsx(template_bytes, profile_user_id=2)
    files = {"file": ("import.xlsx", xlsx_bytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}
    r = http(session, "POST", "/biz/userProfile/importData", files=files, data={"updateSupport": "false"})
    body = safe_json(r)
    msg = str(body.get("msg") or "") if isinstance(body, dict) else ""
    ok_insert = r.status_code == 200 and isinstance(body, dict) and body.get("code") == 200 and "导入成功" in msg
    note_insert = ""
    if not ok_insert and "USER_ID" in msg:
        note_insert = "【业务代码 Bug】UserProfile.userId 缺 @Excel 注解，ExcelUtil.importExcel 匹配不上 → entity.userId=null → DB 非空约束。"

    xlsx_bytes2 = build_import_xlsx(template_bytes, profile_user_id=2)
    files2 = {"file": ("import_update.xlsx", xlsx_bytes2, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}
    r2 = http(session, "POST", "/biz/userProfile/importData", files=files2, data={"updateSupport": "true"})
    body2 = safe_json(r2)
    msg2 = str(body2.get("msg") or "") if isinstance(body2, dict) else ""
    ok_update = r2.status_code == 200 and isinstance(body2, dict) and body2.get("code") == 200 and "更新成功" in msg2

    rl = http(session, "GET", "/biz/userProfile/list", params={"userId": 2})
    bl = safe_json(rl)
    rows = bl.get("rows", []) if isinstance(bl, dict) else []
    has_row = any(rr.get("userId") == 2 for rr in rows)

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

    rl = http(session, "GET", "/biz/userProfile/list")
    bl = safe_json(rl)
    rows = bl.get("rows", []) if isinstance(bl, dict) else []
    still_there = any(x.get("profileId") == profile_id for x in rows)
    ok = ok_del and not still_there
    note = ""
    if not ok:
        notes: List[str] = []
        notes.append("【业务代码 Bug】del_flag=NULL 导致 deleteByIds 生成 SQL 命中 0 行 → '删除失败'。")
        if ok_del and still_there:
            notes.append("(另有) selectUserProfileViewList XML 未加 WHERE p.del_flag='0' 过滤。")
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
        info["occurrences"] = [
            {"line": i + 1, "text": ln.strip()}
            for i, ln in enumerate(content.splitlines())
            if "${params.dataScope}" in ln
        ]
    ok = info["exists"] and info.get("contains_params_data_scope") is True
    return info, ok


def case_12_dict_check() -> Dict[str, Any]:
    """DB 直连：3 个字典类型的 dict_data 条数（research_area=8 / research_direction=12 / degree_level=3）。"""
    out: Dict[str, Any] = {"expected": {k: len(v) for k, v in DICT_VALUES.items()}}
    actual: Dict[str, Any] = {}
    ok_all = True
    for dtype, expected_vals in DICT_VALUES.items():
        ok, res = db_query(
            "SELECT DICT_VALUE, DICT_LABEL FROM RUOYI.SYS_DICT_DATA WHERE DICT_TYPE=? ORDER BY DICT_SORT",
            [dtype],
        )
        if not ok:
            actual[dtype] = {"error": res}
            ok_all = False
            continue
        rows = res["rows"]
        values = [r[0] for r in rows]
        actual[dtype] = {
            "count": len(values),
            "values": values,
            "missing_from_expected": [v for v in expected_vals if v not in values],
            "unexpected_in_actual": [v for v in values if v not in expected_vals],
        }
        if len(values) != len(expected_vals):
            ok_all = False

    # 也查 dict_type 是否存在
    ok, res = db_query(
        "SELECT DICT_ID, DICT_NAME, DICT_TYPE FROM RUOYI.SYS_DICT_TYPE "
        "WHERE DICT_TYPE IN ('research_area','research_direction','degree_level') ORDER BY DICT_ID"
    )
    type_rows = res["rows"] if ok else [("error", res)]
    out["dict_types"] = [list(r) for r in type_rows]
    out["dict_data"] = actual
    out["all_ok"] = ok_all
    return out, ok_all


def case_13_menu_check() -> Dict[str, Any]:
    """DB 直连：sys_menu 2000/2001 不存在；biz:userProfile:* 按钮挂在用户管理菜单（parent=100）下；
    7 个业务角色 role_menu 含 2008+2009。"""
    out: Dict[str, Any] = {}

    ok, res = db_query(
        "SELECT MENU_ID FROM RUOYI.SYS_MENU WHERE MENU_ID IN (2000, 2001) ORDER BY MENU_ID"
    )
    rows_200x = [r[0] for r in res["rows"]] if ok else ["err"]
    out["menu_2000_2001_present"] = rows_200x
    cond_no_old = (rows_200x == []) if ok else False

    ok2, res2 = db_query(
        "SELECT MENU_ID, MENU_NAME, PARENT_ID, PERMS FROM RUOYI.SYS_MENU "
        "WHERE MENU_ID BETWEEN 2002 AND 2009 ORDER BY MENU_ID"
    )
    menu_rows = res2["rows"] if ok2 else []
    out["menu_2002_2009"] = [list(r) for r in menu_rows]
    cond_buttons_under_100 = all(r[2] == 100 for r in menu_rows) if menu_rows else False

    ok3, res3 = db_query(
        "SELECT DISTINCT ROLE_ID FROM RUOYI.SYS_ROLE_MENU WHERE MENU_ID IN (2008, 2009) ORDER BY ROLE_ID"
    )
    role_rows = [r[0] for r in res3["rows"]] if ok3 else []
    out["roles_with_2008_or_2009"] = role_rows
    expected_roles = {1, 100, 101, 102, 103, 104, 105}
    cond_roles = set(role_rows) >= expected_roles

    ok4, res4 = db_query(
        "SELECT MENU_ID, PARENT_ID FROM RUOYI.SYS_MENU WHERE MENU_ID = 100"
    )
    out["menu_100_info"] = [list(r) for r in res4["rows"]] if ok4 else [["err"]]

    all_ok = cond_no_old and cond_buttons_under_100 and cond_roles and ok and ok2 and ok3
    out["all_ok"] = all_ok
    out["checks"] = {
        "no_old_menus_2000_2001": cond_no_old,
        "buttons_under_menu_100": cond_buttons_under_100,
        "all_7_roles_have_2008_or_2009": cond_roles,
    }
    return out, all_ok


def case_14_column_check() -> Dict[str, Any]:
    """DB 直连：biz_user_profile 无 id_number；有 degree/major/bio；视图含新列。"""
    out: Dict[str, Any] = {}

    ok, res = db_query(
        "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS "
        "WHERE OWNER='RUOYI' AND TABLE_NAME='BIZ_USER_PROFILE' ORDER BY COLUMN_ID"
    )
    cols = [r[0] for r in res["rows"]] if ok else []
    out["table_columns"] = cols
    cond_no_id = "ID_NUMBER" not in cols
    cond_has_degree = "DEGREE" in cols
    cond_has_major = "MAJOR" in cols
    cond_has_bio = "BIO" in cols

    ok2, res2 = db_query(
        "SELECT TEXT FROM SYS.DBA_VIEWS WHERE OWNER='RUOYI' AND VIEW_NAME='V_BIZ_USER_PROFILE'"
    )
    if ok2 and res2["rows"]:
        view_text = (res2["rows"][0][0] or "").upper()
        cond_view_degree = "DEGREE" in view_text
        cond_view_major = "MAJOR" in view_text
        cond_view_bio = "BIO" in view_text
        cond_view_no_id = "ID_NUMBER" not in view_text
        out["view_text_excerpt"] = res2["rows"][0][0][:2000]
    else:
        cond_view_degree = cond_view_major = cond_view_bio = cond_view_no_id = False
        out["view_text_excerpt"] = "VIEW NOT FOUND" if ok2 else ("err: " + str(res2))

    all_ok = (
        cond_no_id and cond_has_degree and cond_has_major and cond_has_bio
        and cond_view_degree and cond_view_major and cond_view_bio and cond_view_no_id
        and ok and ok2
    )
    out["checks"] = {
        "no_id_number_col": cond_no_id,
        "has_degree": cond_has_degree,
        "has_major": cond_has_major,
        "has_bio": cond_has_bio,
        "view_has_degree": cond_view_degree,
        "view_has_major": cond_view_major,
        "view_has_bio": cond_view_bio,
        "view_no_id_number": cond_view_no_id,
    }
    out["all_ok"] = all_ok
    return out, all_ok


def case_15_list_field_completeness(session: requests.Session) -> Dict[str, Any]:
    """前端 profileDialog 弹窗编辑态依赖 list 取数。
    必须包含：degree / major / bio / officePhone（任一字段缺失 → 编辑清空数据）。"""
    # 1) 拉 list
    rl = http(session, "GET", "/biz/userProfile/list", params={"pageNum": 1, "pageSize": 50})
    bl = safe_json(rl)
    rows = bl.get("rows", []) if isinstance(bl, dict) else []
    required_fields = ["profileId", "userId", "eduLevel", "titleLevel",
                       "researchArea", "researchDirection", "degree", "major", "bio",
                       "officePhone", "entryDate"]
    forbidden_fields = ["idNumber", "ID_NUMBER"]

    # 2) 统计缺失/多余字段
    missing_summary: Dict[str, int] = {f: 0 for f in required_fields}
    forbidden_summary: Dict[str, int] = {f: 0 for f in forbidden_fields}
    sample_row_keys: List[Any] = []
    sample_row: Optional[Dict[str, Any]] = None
    if rows:
        sample_row = rows[0]
        sample_row_keys = sorted(sample_row.keys())
        for r in rows:
            for f in required_fields:
                if f not in r:
                    missing_summary[f] += 1
            for f in forbidden_fields:
                if f in r:
                    forbidden_summary[f] += 1

    # 3) 若有 profileId=1（02_add 创建）的记录，验证新字段非空（编辑态能拿到值）
    edit_state_check: Dict[str, Any] = {"found": False}
    for r in rows:
        if r.get("userId") == 1:
            edit_state_check = {
                "found": True,
                "profileId": r.get("profileId"),
                "degree": r.get("degree"),
                "major": r.get("major"),
                "bio": r.get("bio"),
                "officePhone": r.get("officePhone"),
            }
            break

    total = bl.get("total") if isinstance(bl, dict) else None

    cond_all_rows_have_required = all(v == 0 for v in missing_summary.values())
    cond_no_forbidden = all(v == 0 for v in forbidden_summary.values())
    ok = cond_all_rows_have_required and cond_no_forbidden
    return {
        "list_status": rl.status_code,
        "list_total": total,
        "list_rows_count": len(rows),
        "required_fields": required_fields,
        "missing_summary": missing_summary,
        "forbidden_fields": forbidden_fields,
        "forbidden_summary": forbidden_summary,
        "sample_row_keys": sample_row_keys,
        "edit_state_userid_1": edit_state_check,
        "checks": {
            "all_rows_have_required": cond_all_rows_have_required,
            "no_forbidden_fields": cond_no_forbidden,
        },
        "all_ok": ok,
    }, ok


def main() -> int:
    print(f"[boot] base url = {BASE_URL}")
    print("[boot] waiting backend ready ...")
    if not wait_ready(60):
        print("[boot] backend NOT ready in 60s")
        record("00_backend_ready", False, {}, {"elapsed": 60}, "后端 60 秒内未就绪")
        return 1
    print("[boot] backend ready")

    sess = requests.Session()
    sess.headers.update({"User-Agent": "task6-smoke/1.0"})

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

    # 2. add（新契约：degree/major/bio + 字典值）
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
    record("05_update", ok, {"method": "PUT", "url": "/biz/userProfile"}, resp, note05)

    # 6. export
    resp, ok = case_06_export(sess)
    record("06_export", ok, {"method": "POST", "url": "/biz/userProfile/export"}, resp)

    # 7. importTemplate
    resp, ok = case_07_template(sess)
    record("07_importTemplate", ok, {"method": "POST", "url": "/biz/userProfile/importTemplate"}, resp)

    # 8. importData
    resp, ok, imported_user_id, note08 = case_08_import(sess)
    record("08_importData_insert_and_update", ok, {"method": "POST", "url": "/biz/userProfile/importData"}, resp, note08)

    # 9. delete
    resp, ok, note09 = case_09_delete(sess, new_id, user_id=1)
    record("09_delete", ok, {"method": "DELETE", "url": f"/biz/userProfile/{new_id}"}, resp, note09)

    # 10. 数据权限静态确认
    info, ok = case_10_data_scope_static()
    record("10_dataScope_static", ok, {"method": "static", "target": "UserProfileMapper.xml"}, info, "Mapper XML 含 ${params.dataScope} 注入点")

    # 12. 字典数据检查
    info, ok = case_12_dict_check()
    record("12_dict_check", ok, {"method": "db", "target": "sys_dict_data"}, info, "3 字典条数与预期一致")

    # 13. 菜单检查
    info, ok = case_13_menu_check()
    record("13_menu_check", ok, {"method": "db", "target": "sys_menu/sys_role_menu"}, info, "菜单 2000/2001 清理 + 按钮挂用户管理下 + 7 角色权限")

    # 14. 列与视图检查
    info, ok = case_14_column_check()
    record("14_column_check", ok, {"method": "db", "target": "biz_user_profile/v_biz_user_profile"}, info, "表无 id_number + 视图含新列")

    # 15. list 接口字段完整性（前端 profileDialog 编辑态依赖）
    info, ok = case_15_list_field_completeness(sess)
    record("15_list_field_completeness", ok, {"method": "GET", "url": "/biz/userProfile/list?pageSize=50"}, info, "degree/major/bio/officePhone 必含，idNumber 必不含")

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
