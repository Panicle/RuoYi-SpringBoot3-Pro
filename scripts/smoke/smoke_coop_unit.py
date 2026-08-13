#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 4 — 阶段6 合作单位接口冒烟
- 覆盖：层级深度校验 / 子类型继承 / 联系人CRUD / 课题关联 / 删除保护 / 换父级同步 / 回归
- 复用 smoke_project.py 的登录框架与 DB 直连工具
- 结果写 scripts/smoke/result.jsonl
- 只测不改业务代码；发现的 Bug 记入报告
- 附加验证：treeselect 形态；DELETE /biz/project/unit/{ids} 语义
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

DM_PASSWORD = os.environ.get("DM_PASSWORD")
if not DM_PASSWORD:
    sys.stderr.write("[fatal] 缺少环境变量 DM_PASSWORD（达梦 SYSDBA 口令）\n")
    sys.exit(2)
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

TEST_MARK = "smoke-task4-unit"   # 收尾按此清理

RESULTS: List[Dict[str, Any]] = []
STATE: Dict[str, Any] = {}  # 跨用例共享：unitId/projectId 等


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
#  数据准备 / 清理
# ============================================================

def cleanup_test_data() -> Tuple[bool, str]:
    """按 TEST_MARK 清理单位/联系人/课题关联/课题/课题成员。"""
    ok, r = db_execute(
        "DELETE FROM RUOYI.PROJECT_UNIT WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清关联单位失败: " + str(r)
    ok, r = db_execute(
        "DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
        "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清成员失败: " + str(r)
    ok, r = db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清课题失败: " + str(r)
    # 单位：按 remark 或 unit_name 含 TEST_MARK 收尾
    ok, r = db_execute(
        "DELETE FROM RUOYI.UNIT_CONTACT WHERE UNIT_ID IN "
        "(SELECT UNIT_ID FROM RUOYI.COOPERATIVE_UNIT WHERE REMARK = ? OR UNIT_NAME LIKE ?)",
        [TEST_MARK, f"{TEST_MARK}%"])
    if not ok:
        return False, "清联系人失败: " + str(r)
    # 合作单位允许假删后续清：del_flag='2' 也强制删
    ok, r = db_execute(
        "DELETE FROM RUOYI.COOPERATIVE_UNIT WHERE REMARK = ? OR UNIT_NAME LIKE ?",
        [TEST_MARK, f"{TEST_MARK}%"])
    if not ok:
        return False, "清单位失败: " + str(r)
    return True, "ok"


def _find_unit_id(sess, unit_name: str, parent_id: int) -> Optional[int]:
    """后端 toAjax 不返回 unitId；用 list 按 name+parent_id 反查最新匹配。"""
    r = http(sess, "GET", "/biz/unit/list", params={"unitName": unit_name})
    b = safe_json(r)
    arr = b.get("data", []) if isinstance(b, dict) else []
    # parentId=0 时返回的 parent_id 是 0
    candidates = [x for x in arr if x.get("unitName") == unit_name
                  and (x.get("parentId") or 0) == parent_id]
    if not candidates:
        return None
    return candidates[-1].get("unitId")


def make_unit_body(name: str, parent_id: int = 0, external_type: str = "COMPANY",
                   unit_type: str = "EXTERNAL", company_type: str = "GENERAL",
                   company_category: str = "PRIVATE") -> Dict[str, Any]:
    """构造单位请求 body。"""
    body: Dict[str, Any] = {
        "unitName": name,
        "parentId": parent_id,
        "externalUnitType": external_type,
        "unitType": unit_type,
    }
    if parent_id == 0:
        # 顶级必须传 externalUnitType（已在 body）
        body["companyType"] = company_type
        body["companyCategory"] = company_category
        body["expertise"] = f"{name}-行业领域"
    body["remark"] = TEST_MARK
    return body


# ============================================================
#  Case 1: 公司树三层成 / 第四层拒
# ============================================================

def case_1_company_three_layer(sess) -> Dict[str, Any]:
    """A公司→B子→C子 三层成功；C 下建第四层被拒。"""
    out: Dict[str, Any] = {}

    # 1) A 顶级
    body_a = make_unit_body(f"{TEST_MARK}-A公司", 0, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_a)
    b = safe_json(r)
    a_id = _find_unit_id(sess, body_a["unitName"], 0) if isinstance(b, dict) and b.get("code") == 200 else None
    a_ok = a_id is not None
    out["A_create"] = {"body": body_a, "status_code": r.status_code, "resp": b, "unitId": a_id}
    if not a_ok:
        return out, False, "A公司创建失败"

    # 2) B 子（parent=A）
    body_b = make_unit_body(f"{TEST_MARK}-B子公司", a_id, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_b)
    b = safe_json(r)
    b_id = _find_unit_id(sess, body_b["unitName"], a_id) if isinstance(b, dict) and b.get("code") == 200 else None
    b_ok = b_id is not None
    out["B_create"] = {"body": body_b, "status_code": r.status_code, "resp": b, "unitId": b_id}
    if not b_ok:
        return out, False, "B子公司创建失败"

    # 3) C 子（parent=B）
    body_c = make_unit_body(f"{TEST_MARK}-C孙公司", b_id, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_c)
    b = safe_json(r)
    c_id = _find_unit_id(sess, body_c["unitName"], b_id) if isinstance(b, dict) and b.get("code") == 200 else None
    c_ok = c_id is not None
    out["C_create"] = {"body": body_c, "status_code": r.status_code, "resp": b, "unitId": c_id}
    if not c_ok:
        return out, False, "C孙公司创建失败"

    # 4) D 第四层被拒（parent=C）
    body_d = make_unit_body(f"{TEST_MARK}-D曾孙", c_id, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_d)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    d_rejected = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "公司层级最多三层" in msg
    out["D_reject"] = {"body": body_d, "status_code": r.status_code, "resp": b, "msg": msg}

    STATE.update({"companyA": a_id, "companyB": b_id, "companyC": c_id})
    ok = a_ok and b_ok and c_ok and d_rejected
    note = "" if ok else f"a_ok={a_ok} b_ok={b_ok} c_ok={c_ok} d_rejected={d_rejected}"
    return out, ok, note


# ============================================================
#  Case 2: 学校树两层成 / 第三层拒
# ============================================================

def case_2_school_two_layer(sess) -> Dict[str, Any]:
    """X大学→Y学院 两层成功；Y 下建第三层被拒。"""
    out: Dict[str, Any] = {}
    body_x = make_unit_body(f"{TEST_MARK}-X大学", 0, "SCHOOL")
    r = http(sess, "POST", "/biz/unit", json_body=body_x)
    b = safe_json(r)
    x_id = _find_unit_id(sess, body_x["unitName"], 0) if isinstance(b, dict) and b.get("code") == 200 else None
    x_ok = x_id is not None
    out["X_create"] = {"body": body_x, "status_code": r.status_code, "resp": b, "unitId": x_id}
    if not x_ok:
        return out, False, "X大学创建失败"

    body_y = make_unit_body(f"{TEST_MARK}-Y学院", x_id, "SCHOOL")
    r = http(sess, "POST", "/biz/unit", json_body=body_y)
    b = safe_json(r)
    y_id = _find_unit_id(sess, body_y["unitName"], x_id) if isinstance(b, dict) and b.get("code") == 200 else None
    y_ok = y_id is not None
    out["Y_create"] = {"body": body_y, "status_code": r.status_code, "resp": b, "unitId": y_id}
    if not y_ok:
        return out, False, "Y学院创建失败"

    body_z = make_unit_body(f"{TEST_MARK}-Z专业", y_id, "SCHOOL")
    r = http(sess, "POST", "/biz/unit", json_body=body_z)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    z_rejected = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "学校层级最多两层" in msg
    out["Z_reject"] = {"body": body_z, "status_code": r.status_code, "resp": b, "msg": msg}

    STATE["schoolX"] = x_id
    STATE["schoolY"] = y_id
    ok = x_ok and y_ok and z_rejected
    return out, ok, "" if ok else f"x_ok={x_ok} y_ok={y_ok} z_rejected={z_rejected}"


# ============================================================
#  Case 3: 各层联系人 CRUD（含职务/电话；老师含 major/researchField）
# ============================================================

def _find_contact_id(sess, unit_id: int, name: str) -> Optional[int]:
    """contact add 也走 toAjax（不返 id）；用 contact/list 反查。"""
    r = http(sess, "GET", "/biz/unit/contact/list", params={"unitId": unit_id})
    b = safe_json(r)
    arr = b.get("data", []) if isinstance(b, dict) else []
    matches = [x for x in arr if x.get("contactName") == name]
    return matches[-1].get("contactId") if matches else None


def case_3_contacts(sess) -> Dict[str, Any]:
    """B子公司/C孙公司（公司类）+ Y学院（学校类）分别建联系人 + 改 + 删。"""
    out: Dict[str, Any] = {}

    # 3.1 公司联系人（B/C）：position+phone+email；主联系人
    contact_b = {
        "unitId": STATE["companyB"],
        "contactName": "张总-公司B",
        "position": "总经理",
        "phone": "13800000001",
        "email": "zhangb@smoke.com",
        "isPrimary": "1",
        "remark": TEST_MARK,
    }
    r = http(sess, "POST", "/biz/unit/contact", json_body=contact_b)
    b = safe_json(r)
    cb_id = _find_contact_id(sess, STATE["companyB"], contact_b["contactName"]) if isinstance(b, dict) and b.get("code") == 200 else None
    cb_ok = cb_id is not None
    out["contact_B_add"] = {"body": contact_b, "status_code": r.status_code, "resp": b, "contactId": cb_id}

    contact_c = {
        "unitId": STATE["companyC"],
        "contactName": "李经理-公司C",
        "position": "市场经理",
        "phone": "13800000002",
        "email": "lic@smoke.com",
        "remark": TEST_MARK,
    }
    r = http(sess, "POST", "/biz/unit/contact", json_body=contact_c)
    b = safe_json(r)
    cc_id = _find_contact_id(sess, STATE["companyC"], contact_c["contactName"]) if isinstance(b, dict) and b.get("code") == 200 else None
    cc_ok = cc_id is not None
    out["contact_C_add"] = {"body": contact_c, "status_code": r.status_code, "resp": b, "contactId": cc_id}

    # 3.2 老师联系人（Y学院）：major + researchField
    contact_y = {
        "unitId": STATE["schoolY"],
        "contactName": "王教授",
        "position": "教授",
        "phone": "13800000003",
        "email": "wangp@smoke.edu",
        "major": "计算机科学与技术",
        "researchField": "人工智能/NLP",
        "isPrimary": "1",
        "remark": TEST_MARK,
    }
    r = http(sess, "POST", "/biz/unit/contact", json_body=contact_y)
    b = safe_json(r)
    cy_id = _find_contact_id(sess, STATE["schoolY"], contact_y["contactName"]) if isinstance(b, dict) and b.get("code") == 200 else None
    cy_ok = cy_id is not None
    out["contact_Y_add"] = {"body": contact_y, "status_code": r.status_code, "resp": b, "contactId": cy_id}

    # 3.3 contact/list 验证三家联系人齐全
    def contact_list(unit_id: int) -> Dict[str, Any]:
        r2 = http(sess, "GET", "/biz/unit/contact/list", params={"unitId": unit_id})
        b2 = safe_json(r2)
        return b2.get("data", []) if isinstance(b2, dict) else []
    lb = contact_list(STATE["companyB"])
    lc = contact_list(STATE["companyC"])
    ly = contact_list(STATE["schoolY"])
    out["contact_B_list"] = lb
    out["contact_C_list"] = lc
    out["contact_Y_list"] = ly
    list_ok = (
        len(lb) == 1 and lb[0].get("contactId") == cb_id
        and len(lc) == 1 and lc[0].get("contactId") == cc_id
        and len(ly) == 1 and ly[0].get("contactId") == cy_id
        and ly[0].get("major") == "计算机科学与技术"
        and ly[0].get("researchField") == "人工智能/NLP"
    )

    # 3.4 修改：改 C 联系人
    if cc_id:
        upd = {"contactId": cc_id, "contactName": "李经理-公司C-改名",
               "position": "市场总监", "phone": "13800000099",
               "unitId": STATE["companyC"], "remark": TEST_MARK}
        r = http(sess, "PUT", "/biz/unit/contact", json_body=upd)
        b = safe_json(r)
        upd_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
        # DB 直查
        ok, res = db_query(
            "SELECT CONTACT_NAME, POSITION, PHONE FROM RUOYI.UNIT_CONTACT WHERE CONTACT_ID = ?",
            [cc_id])
        rows = res["rows"] if ok else []
        db_updated = (len(rows) == 1 and rows[0][0] == "李经理-公司C-改名"
                      and rows[0][1] == "市场总监" and rows[0][2] == "13800000099")
        out["contact_C_update"] = {"status_code": r.status_code, "resp": b, "db_row": list(rows[0]) if rows else None}
    else:
        upd_ok, db_updated = False, False
        out["contact_C_update"] = {"skipped": True}

    # 3.5 删除 B 联系人
    if cb_id:
        r = http(sess, "DELETE", f"/biz/unit/contact/{cb_id}")
        b = safe_json(r)
        del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
        ok, res = db_query(
            "SELECT DEL_FLAG FROM RUOYI.UNIT_CONTACT WHERE CONTACT_ID = ?", [cb_id])
        row = res["rows"][0] if ok and res["rows"] else None
        db_del_flag = row[0] if row else None
        out["contact_B_del"] = {"status_code": r.status_code, "resp": b, "db_del_flag": db_del_flag}
        del_ok = del_ok and db_del_flag == "2"
    else:
        del_ok = False
        out["contact_B_del"] = {"skipped": True}

    STATE.update({"contactC": cc_id, "contactY": cy_id})
    ok = cb_ok and cc_ok and cy_ok and list_ok and upd_ok and db_updated and del_ok
    return out, ok, "" if ok else (
        f"add cb={cb_ok} cc={cc_ok} cy={cy_ok} list={list_ok} upd={upd_ok} db_upd={db_updated} del={del_ok}")


# ============================================================
#  Case 4: 子单位类型继承（body 传不同类型被忽略）
# ============================================================

def case_4_type_inherit(sess) -> Dict[str, Any]:
    """B 父=COMPANY，给 B 加子节点时 body 显式传 SCHOOL/OTHER 应被忽略 → 子仍为 COMPANY。"""
    out: Dict[str, Any] = {}

    # 准备：建顶级 P（COMPANY）
    body_p = make_unit_body(f"{TEST_MARK}-P父公司", 0, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_p)
    b = safe_json(r)
    p_id = _find_unit_id(sess, body_p["unitName"], 0) if isinstance(b, dict) and b.get("code") == 200 else None
    p_ok = p_id is not None
    out["P_create"] = {"body": body_p, "status_code": r.status_code, "resp": b, "unitId": p_id}
    if not p_ok:
        return out, False, "P父公司创建失败"

    # 给 P 加子，body 显式传 externalUnitType=SCHOOL / unitType=INTERNAL（应被忽略）
    body_child = {
        "unitName": f"{TEST_MARK}-P子-类型应被忽略",
        "parentId": p_id,
        "externalUnitType": "SCHOOL",  # 应忽略
        "unitType": "INTERNAL",         # 应忽略
        "companyType": "MICRO",
        "companyCategory": "SOE",
        "remark": TEST_MARK,
    }
    r = http(sess, "POST", "/biz/unit", json_body=body_child)
    b = safe_json(r)
    child_id = _find_unit_id(sess, body_child["unitName"], p_id) if isinstance(b, dict) and b.get("code") == 200 else None
    child_ok = child_id is not None
    out["child_create"] = {"body": body_child, "status_code": r.status_code, "resp": b, "unitId": child_id}

    # DB 直查
    if child_id:
        ok, res = db_query(
            "SELECT UNIT_TYPE, EXTERNAL_UNIT_TYPE FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?",
            [child_id])
        rows = res["rows"] if ok else []
        db_utype, db_etype = (rows[0][0], rows[0][1]) if rows else (None, None)
    else:
        db_utype, db_etype = None, None
    # 期望：unit_type=EXTERNAL（继承自 P），external_unit_type=COMPANY（继承自 P）
    type_ok = db_utype == "EXTERNAL" and db_etype == "COMPANY"
    out["db_verify"] = {"unit_type": db_utype, "external_unit_type": db_etype}

    # 编辑子节点尝试改 externalUnitType 也应被忽略（Service 强制重算为父级类型）
    body_edit = {
        "unitId": child_id,
        "unitName": f"{TEST_MARK}-P子-改后",
        "parentId": p_id,
        "externalUnitType": "OTHER",  # 应被忽略（继承父）
        "unitType": "EXTERNAL",
        "companyType": "MICRO",
        "companyCategory": "SOE",
        "remark": TEST_MARK,
    }
    r = http(sess, "PUT", "/biz/unit", json_body=body_edit)
    b = safe_json(r)
    edit_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    if child_id:
        ok2, res2 = db_query(
            "SELECT UNIT_TYPE, EXTERNAL_UNIT_TYPE FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?",
            [child_id])
        rows2 = res2["rows"] if ok2 else []
        db2_utype, db2_etype = (rows2[0][0], rows2[0][1]) if rows2 else (None, None)
    else:
        db2_utype, db2_etype = None, None
    edit_type_ok = db2_utype == "EXTERNAL" and db2_etype == "COMPANY"
    out["child_edit"] = {"body": body_edit, "status_code": r.status_code, "resp": b,
                         "db_unit_type": db2_utype, "db_external_unit_type": db2_etype}

    STATE["inheritParent"] = p_id
    STATE["inheritChild"] = child_id
    ok = p_ok and child_ok and type_ok and edit_ok and edit_type_ok
    return out, ok, "" if ok else f"p={p_ok} child={child_ok} type={type_ok} edit={edit_ok} edit_type={edit_type_ok}"


# ============================================================
#  Case 5: 课题关联（add LEAD+PARTICIPANT/重复友好报错/list 含 unitName/remove）
# ============================================================

def add_project_for_unit(sess, name: str, leader_id: int = 1) -> Tuple[Optional[int], Dict[str, Any]]:
    """造 DRAFT 课题供单位关联使用（leader=admin user_id=1）。"""
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {
        "projectName": name,
        "projectType": "NATIONAL",
        "leaderId": leader_id,
        "projectNo": f"KY-T4-{(STATE['no_seq'] % 900) + 100:03d}",
        "projectCategory": "A",
        "specialty": "Y",
        "remark": TEST_MARK,
    }
    r = http(sess, "POST", "/biz/project", json_body=body)
    b = safe_json(r)
    pid = get_data(b).get("projectId") if isinstance(b, dict) and b.get("code") == 200 else None
    return pid, {"request": body, "status_code": r.status_code, "resp": b}


def case_5_project_unit_assoc(sess) -> Dict[str, Any]:
    out: Dict[str, Any] = {}

    # 5.1 造课题 D
    pid, info = add_project_for_unit(sess, f"{TEST_MARK}-D课题-单位关联")
    out["project_D_add"] = info
    if pid is None:
        return out, False, "课题 D 创建失败"
    STATE["projectD"] = pid

    # 5.2 addUnit LEAD（A 顶级公司=STATE["companyA"]）
    add_lead = {"projectId": pid, "unitId": STATE["companyA"], "cooperationType": "LEAD",
                "remark": TEST_MARK}
    r = http(sess, "POST", "/biz/project/unit", json_body=add_lead)
    b = safe_json(r)
    lead_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["assoc_LEAD_add"] = {"body": add_lead, "status_code": r.status_code, "resp": b}

    # 5.3 addUnit PARTICIPANT（X大学=STATE["schoolX"]）
    add_par = {"projectId": pid, "unitId": STATE["schoolX"], "cooperationType": "PARTICIPANT",
               "remark": TEST_MARK}
    r = http(sess, "POST", "/biz/project/unit", json_body=add_par)
    b = safe_json(r)
    par_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["assoc_PARTICIPANT_add"] = {"body": add_par, "status_code": r.status_code, "resp": b}

    # 5.4 list 含 unitName/externalUnitType
    r = http(sess, "GET", "/biz/project/unit/list", params={"projectId": pid})
    b = safe_json(r)
    rows = b.get("data", []) if isinstance(b, dict) else []
    names = [x.get("unitName") for x in rows]
    types = [x.get("externalUnitType") for x in rows]
    list_ok = (len(rows) == 2
               and any(x.get("cooperationType") == "LEAD" and x.get("unitName", "").endswith("A公司")
                       for x in rows)
               and any(x.get("cooperationType") == "PARTICIPANT" and x.get("unitName", "").endswith("X大学")
                       and x.get("externalUnitType") == "SCHOOL"
                       for x in rows))
    out["assoc_list"] = {"status_code": r.status_code, "resp": b, "names": names, "types": types}

    # 5.5 重复关联 A 公司 → 友好报错
    r = http(sess, "POST", "/biz/project/unit", json_body=add_lead)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    dup_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "该单位已关联此课题" in msg
    out["assoc_duplicate_rejected"] = {"body": add_lead, "status_code": r.status_code, "resp": b, "msg": msg}

    # 5.6 remove 关联（先抓 A 关联的 id）
    assoc_id = None
    for x in rows:
        if x.get("cooperationType") == "LEAD" and x.get("unitName", "").endswith("A公司"):
            assoc_id = x.get("id")
            break
    if assoc_id is None:
        return out, False, "未取到 LEAD 关联 id"
    r = http(sess, "DELETE", f"/biz/project/unit/{assoc_id}")
    b = safe_json(r)
    rem_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    # DB 直查 del_flag
    ok, res = db_query(
        "SELECT DEL_FLAG FROM RUOYI.PROJECT_UNIT WHERE ID = ?", [assoc_id])
    db_del = res["rows"][0][0] if ok and res["rows"] else None
    rem_ok = rem_ok and db_del == "2"
    out["assoc_remove"] = {"id": assoc_id, "status_code": r.status_code, "resp": b, "db_del_flag": db_del}

    ok = lead_ok and par_ok and list_ok and dup_ok and rem_ok
    return out, ok, "" if ok else f"lead={lead_ok} par={par_ok} list={list_ok} dup={dup_ok} rem={rem_ok}"


# ============================================================
#  Case 6: 删除保护（有子节点拒 / 被课题引用拒 / 解除引用后叶子删除成功 del_flag='2'）
# ============================================================

def case_6_delete_protect(sess) -> Dict[str, Any]:
    out: Dict[str, Any] = {}

    # 6.1 有子节点删除被拒（A 公司有 B 子）
    r = http(sess, "DELETE", f"/biz/unit/{STATE['companyA']}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    has_child_ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                    and "存在下级单位" in msg)
    out["del_with_child_reject"] = {"target": STATE["companyA"], "resp": b, "msg": msg}

    # 6.2 被课题引用删除被拒：用 P子（无子 + 被课题关联；先准备）
    #   用 case_4 的 inheritChild（P 子），给它建一个课题关联（否则 countByUnitId=0 不会被拒）
    #   为简化：把 companyC 关联到一个 DRAFT 课题做引用计数（P 子无子无引用，需新造关联）
    #   直接用 companyC（无子节点），并先建关联
    time.sleep(2.5)  # 避免 RepeatSubmit 拦截
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    pj_body = {"projectName": f"{TEST_MARK}-F课题-引用", "projectType": "PROVINCIAL",
               "leaderId": 1, "projectNo": f"KY-T4-F{(STATE['no_seq'] % 900) + 100:03d}",
               "projectCategory": "A", "specialty": "Y", "remark": TEST_MARK}
    r = http(sess, "POST", "/biz/project", json_body=pj_body)
    fpj = get_data(safe_json(r)).get("projectId") if isinstance(safe_json(r), dict) else None
    if fpj:
        add_c = {"projectId": fpj, "unitId": STATE["companyC"], "cooperationType": "PARTICIPANT",
                 "remark": TEST_MARK}
        r = http(sess, "POST", "/biz/project/unit", json_body=add_c)
        out["del_make_ref"] = {"pj": fpj, "add_status": r.status_code}

    time.sleep(2.5)
    r = http(sess, "DELETE", f"/biz/unit/{STATE['companyC']}")
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ref_ok = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
              and "已被课题关联" in msg)
    out["del_referenced_reject"] = {"target": STATE["companyC"], "resp": b, "msg": msg}

    # 6.3 解除关联 + 删除 C（叶子无引用，应成功，del_flag='2'）
    if fpj:
        # list 取 C 关联 id
        r = http(sess, "GET", "/biz/project/unit/list", params={"projectId": fpj})
        b = safe_json(r)
        rows = b.get("data", []) if isinstance(b, dict) else []
        c_assoc_id = None
        for x in rows:
            if x.get("unitId") == STATE["companyC"]:
                c_assoc_id = x.get("id")
                break
        if c_assoc_id:
            time.sleep(2.5)
            r = http(sess, "DELETE", f"/biz/project/unit/{c_assoc_id}")
            out["del_c_assoc"] = {"id": c_assoc_id, "status_code": r.status_code, "resp": safe_json(r)}
    time.sleep(2.5)
    r = http(sess, "DELETE", f"/biz/unit/{STATE['companyC']}")
    b = safe_json(r)
    c_del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok, res = db_query("SELECT DEL_FLAG FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?",
                       [STATE["companyC"]])
    c_del_flag = res["rows"][0][0] if ok and res["rows"] else None
    c_del_ok = c_del_ok and c_del_flag == "2"
    out["del_C_success"] = {"resp": b, "db_del_flag": c_del_flag}

    # 6.4 删除 Y 学院（无引用；contact Y 仍存在不影响删除逻辑）
    time.sleep(2.5)
    r = http(sess, "DELETE", f"/biz/unit/{STATE['schoolY']}")
    b = safe_json(r)
    y_del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok, res = db_query("SELECT DEL_FLAG FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?",
                       [STATE["schoolY"]])
    y_del_flag = res["rows"][0][0] if ok and res["rows"] else None
    y_del_ok = y_del_ok and y_del_flag == "2"
    out["del_Y_success"] = {"resp": b, "db_del_flag": y_del_flag}

    # 6.5 X 学院（顶层，被 D 课题关联）先解除关联再删（X 也有 Y 子，但 Y 已删）
    # 先取 X 在 D 课题的关联
    r = http(sess, "GET", "/biz/project/unit/list", params={"projectId": STATE["projectD"]})
    b = safe_json(r)
    rows = b.get("data", []) if isinstance(b, dict) else []
    x_assoc_id = None
    for x in rows:
        if x.get("unitId") == STATE["schoolX"]:
            x_assoc_id = x.get("id")
            break
    if x_assoc_id:
        time.sleep(2.5)
        r = http(sess, "DELETE", f"/biz/project/unit/{x_assoc_id}")
        out["del_x_assoc"] = {"id": x_assoc_id, "status_code": r.status_code, "resp": safe_json(r)}
    time.sleep(2.5)
    r = http(sess, "DELETE", f"/biz/unit/{STATE['schoolX']}")
    b = safe_json(r)
    x_del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok, res = db_query("SELECT DEL_FLAG FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?",
                       [STATE["schoolX"]])
    x_del_flag = res["rows"][0][0] if ok and res["rows"] else None
    x_del_ok = x_del_ok and x_del_flag == "2"
    out["del_X_success"] = {"resp": b, "db_del_flag": x_del_flag}

    # 6.6 /biz/unit/list 不再出现 C（已逻辑删除）
    r = http(sess, "GET", "/biz/unit/list", params={"unitName": f"{TEST_MARK}-C孙公司"})
    b = safe_json(r)
    arr = b.get("data", []) if isinstance(b, dict) else []
    in_list = any(x.get("unitId") == STATE["companyC"] for x in arr)
    list_excluded = not in_list
    out["list_excludes_deleted"] = {"arr_count": len(arr), "in_list": in_list}

    ok = has_child_ok and ref_ok and c_del_ok and y_del_ok and x_del_ok and list_excluded
    return out, ok, "" if ok else (
        f"has_child={has_child_ok} ref={ref_ok} c={c_del_ok} y={y_del_ok} x={x_del_ok} "
        f"list_excl={list_excluded}")


# ============================================================
#  Case 7: 换父级（B 移到另一顶级公司下）后 B/C 的 ancestors 同步正确
# ============================================================

def case_7_move_parent(sess) -> Dict[str, Any]:
    """换父级同步：自建 fresh 三层 A→B→C + 顶级 Q，将 B 移到 Q 下，B/C.ancestors 同步正确。"""
    out: Dict[str, Any] = {}

    # 7.1 造 fresh A→B→C 三层（避免与 case_1/case_6 删除态干扰）
    #   A 顶级 → B 子 → C 孙
    body_a = make_unit_body(f"{TEST_MARK}-M7-A", 0, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_a)
    a_id = _find_unit_id(sess, body_a["unitName"], 0) if isinstance(safe_json(r), dict) and safe_json(r).get("code") == 200 else None
    if a_id is None:
        return out, False, "M7 A 创建失败"

    time.sleep(2.5)
    body_b = make_unit_body(f"{TEST_MARK}-M7-B", a_id, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_b)
    b_id = _find_unit_id(sess, body_b["unitName"], a_id) if isinstance(safe_json(r), dict) and safe_json(r).get("code") == 200 else None
    if b_id is None:
        return out, False, "M7 B 创建失败"

    time.sleep(2.5)
    body_c = make_unit_body(f"{TEST_MARK}-M7-C", b_id, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_c)
    c_id = _find_unit_id(sess, body_c["unitName"], b_id) if isinstance(safe_json(r), dict) and safe_json(r).get("code") == 200 else None
    if c_id is None:
        return out, False, "M7 C 创建失败"

    # 7.2 造另一顶级公司 Q（独立无子）
    time.sleep(2.5)
    body_q = make_unit_body(f"{TEST_MARK}-M7-Q", 0, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_q)
    q_id = _find_unit_id(sess, body_q["unitName"], 0) if isinstance(safe_json(r), dict) and safe_json(r).get("code") == 200 else None
    q_ok = q_id is not None
    out["Q_create"] = {"body": body_q, "status_code": r.status_code, "resp": safe_json(r), "unitId": q_id}
    if not q_ok:
        return out, False, "Q 公司创建失败"

    # 7.3 把 B 换父到 Q
    time.sleep(2.5)
    # 先记录改前
    ok, res = db_query("SELECT PARENT_ID, ANCESTORS FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?", [b_id])
    before = list(res["rows"][0]) if ok and res["rows"] else None

    body_edit = {
        "unitId": b_id,
        "unitName": f"{TEST_MARK}-M7-B",
        "parentId": q_id,
        "remark": TEST_MARK,
    }
    r = http(sess, "PUT", "/biz/unit", json_body=body_edit)
    b = safe_json(r)
    edit_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["B_move_edit"] = {"body": body_edit, "status_code": r.status_code, "resp": b}

    # 7.4 DB 直查：B.parent_id=q_id, B.ancestors=','+q_id（无尾随逗号）
    ok, res = db_query("SELECT PARENT_ID, ANCESTORS FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?", [b_id])
    rows = res["rows"] if ok else []
    if rows:
        b_parent, b_ancestors = rows[0][0], rows[0][1]
    else:
        b_parent, b_ancestors = None, None
    expected_b_ancestors = f",{q_id}"
    b_ancestors_ok = b_parent == q_id and b_ancestors == expected_b_ancestors
    out["B_after"] = {"parent_id": b_parent, "ancestors": b_ancestors,
                       "expected_ancestors": expected_b_ancestors, "before": before}

    # 7.5 C 同步：C.ancestors 应变为 "," + q_id + "," + b_id
    ok, res = db_query("SELECT ANCESTORS FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID = ?", [c_id])
    rows = res["rows"] if ok else []
    c_ancestors = rows[0][0] if rows else None
    expected_c_ancestors = f",{q_id},{b_id}"
    c_ancestors_ok = c_ancestors == expected_c_ancestors
    out["C_after"] = {"ancestors": c_ancestors, "expected_ancestors": expected_c_ancestors}

    # 7.6 不能选自身/后代为父
    time.sleep(2.5)
    body_self = {"unitId": b_id, "parentId": b_id, "remark": TEST_MARK}
    r = http(sess, "PUT", "/biz/unit", json_body=body_self)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    self_reject = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                   and ("上级单位不能是自己" in msg or "自身或后代" in msg))
    out["B_self_reject"] = {"resp": b, "msg": msg}

    time.sleep(2.5)
    body_desc = {"unitId": b_id, "parentId": c_id, "remark": TEST_MARK}
    r = http(sess, "PUT", "/biz/unit", json_body=body_desc)
    b = safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    desc_reject = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                   and "自身或后代" in msg)
    out["B_descendant_reject"] = {"resp": b, "msg": msg}

    # 7.7 换父后层级校验：B 已移到 Q 下（depth=1），给 B 加子 D2 后 depth=2（允许 ≤3）
    #   再给 D2 加孙 D3 后 depth=3，应被拒（公司层级最多三层）
    time.sleep(2.5)
    body_d2 = make_unit_body(f"{TEST_MARK}-M7-D2", b_id, "COMPANY")
    r = http(sess, "POST", "/biz/unit", json_body=body_d2)
    d2_id = _find_unit_id(sess, body_d2["unitName"], b_id) if isinstance(safe_json(r), dict) and safe_json(r).get("code") == 200 else None
    d2_ok = d2_id is not None
    out["D2_create"] = {"body": body_d2, "status_code": r.status_code, "resp": safe_json(r), "unitId": d2_id}

    if d2_id:
        time.sleep(2.5)
        body_d3 = make_unit_body(f"{TEST_MARK}-M7-D3", d2_id, "COMPANY")
        r = http(sess, "POST", "/biz/unit", json_body=body_d3)
        b = safe_json(r)
        msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
        depth_reject = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200
                        and "公司层级最多三层" in msg)
        out["D3_reject"] = {"resp": b, "msg": msg}
    else:
        depth_reject = False
        out["D3_reject"] = {"skipped": True}

    # 7.8 换父子树深度校验：B 带 C 子，移到另一树 depth=1 节点 R 下
    #   拓扑：Q2(顶级)→R(depth=1) ; 原 A→B→C 中 C 在 B 下（depth=2）
    #   移 B 到 R：B.ancestors=",Q2,R" depth=2 OK；但 C.ancestors=",Q2,R,B" depth=3 → 应被拒
    time.sleep(2.5)
    body_q2 = make_unit_body(f"{TEST_MARK}-M7-Q2", 0, "COMPANY")
    r_q2 = http(sess, "POST", "/biz/unit", json_body=body_q2)
    q2_id = _find_unit_id(sess, body_q2["unitName"], 0) if isinstance(safe_json(r_q2), dict) and safe_json(r_q2).get("code") == 200 else None
    if q2_id is None:
        out["subtree_depth_skip"] = "Q2 顶级创建失败"
        subtree_reject = False
    else:
        time.sleep(2.5)
        body_r = make_unit_body(f"{TEST_MARK}-M7-R", q2_id, "COMPANY")
        r_r = http(sess, "POST", "/biz/unit", json_body=body_r)
        r_id = _find_unit_id(sess, body_r["unitName"], q2_id) if isinstance(safe_json(r_r), dict) and safe_json(r_r).get("code") == 200 else None
        if r_id is None:
            out["subtree_depth_skip"] = "R 子节点创建失败"
            subtree_reject = False
        else:
            time.sleep(2.5)
            body_move = {
                "unitId": b_id,
                "unitName": f"{TEST_MARK}-M7-B",
                "parentId": r_id,
                "remark": TEST_MARK,
            }
            r_move = http(sess, "PUT", "/biz/unit", json_body=body_move)
            bm = safe_json(r_move)
            msg = str(bm.get("msg") or "") if isinstance(bm, dict) else ""
            subtree_reject = (r_move.status_code == 200 and isinstance(bm, dict)
                              and bm.get("code") != 200 and "公司层级最多三层" in msg)
            out["B_move_with_subtree_reject"] = {
                "body": body_move, "status_code": r_move.status_code, "resp": bm, "msg": msg
            }
            # 复查：B/C.ancestors 未变（拒绝路径不应触发 update）
            ok_after, res_after = db_query(
                "SELECT PARENT_ID, ANCESTORS FROM RUOYI.COOPERATIVE_UNIT WHERE UNIT_ID IN (?, ?)",
                [b_id, c_id])
            out["B_C_unchanged"] = (res_after["rows"] if ok_after else None)

    ok = q_ok and edit_ok and b_ancestors_ok and c_ancestors_ok and self_reject and desc_reject and d2_ok and depth_reject and subtree_reject
    return out, ok, "" if ok else (
        f"q={q_ok} edit={edit_ok} b_anc={b_ancestors_ok} c_anc={c_ancestors_ok} "
        f"self={self_reject} desc={desc_reject} d2={d2_ok} depth={depth_reject} "
        f"subtree={subtree_reject}")


# ============================================================
#  Case 8: 回归 smoke_project.py 关键子集不退化
# ============================================================

def case_8_regression(sess) -> Dict[str, Any]:
    """admin 登录 + 课题 list + 新增 + 成员 + 删除（核心流程不退化）。"""
    out: Dict[str, Any] = {}
    # 8.1 /biz/project/list
    r = http(sess, "GET", "/biz/project/list", params={"pageNum": 1, "pageSize": 10})
    b = safe_json(r)
    list_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["list"] = {"status_code": r.status_code, "total": b.get("total") if isinstance(b, dict) else None}

    # 8.2 新增
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {"projectName": f"{TEST_MARK}-回归课题", "projectType": "PROVINCIAL",
            "leaderId": 1, "projectNo": f"KY-T4-R{(STATE['no_seq'] % 900) + 100:03d}",
            "projectCategory": "A", "specialty": "Y", "remark": TEST_MARK}
    r = http(sess, "POST", "/biz/project", json_body=body)
    b = safe_json(r)
    pid = get_data(b).get("projectId") if isinstance(b, dict) and b.get("code") == 200 else None
    add_ok = pid is not None
    out["add"] = {"status_code": r.status_code, "projectId": pid, "resp": b}

    # 8.3 详情
    r = http(sess, "GET", f"/biz/project/{pid}")
    b = safe_json(r)
    detail_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["detail"] = {"status_code": r.status_code, "resp": b}

    # 8.4 删除（admin 全权）
    r = http(sess, "DELETE", f"/biz/project/{pid}")
    b = safe_json(r)
    del_ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    out["del"] = {"status_code": r.status_code, "resp": b}

    ok = list_ok and add_ok and detail_ok and del_ok
    return out, ok, "" if ok else f"list={list_ok} add={add_ok} detail={detail_ok} del={del_ok}"


# ============================================================
#  附加验证：(a) treeselect 结构 (b) DELETE /biz/project/unit/{ids} 路径参数语义
# ============================================================

def case_a_treeselect_shape(sess) -> Dict[str, Any]:
    """GET /biz/unit/treeselect 返回结构：[{id, label, disabled, children?:[]}]。

    形态确认：根节点含 children（子为 TreeSelect 列表）；叶子节点因 @JsonInclude(NON_EMPTY)
    children 字段被省略，但 id/label/disabled 三字段恒在。
    """
    # 先确保有父子结构（用 case_4 的 inheritParent/inheritChild，但 case_4 之后 P 可能被保留）
    r = http(sess, "GET", "/biz/unit/treeselect")
    b = safe_json(r)
    arr = b.get("data", []) if isinstance(b, dict) else []

    # 形态 1：根节点列表
    shape_ok = isinstance(arr, list)

    # 形态 2：每个根节点字段集（id/label/disabled 必须存在）
    sample_root = arr[0] if arr else None
    root_fields_ok = (sample_root is not None
                      and "id" in sample_root and "label" in sample_root
                      and "disabled" in sample_root)
    sample_root_has_children = isinstance(sample_root.get("children"), list) if sample_root else False

    # 形态 3：找带 children 的根节点，确认其 children 形如 [{id, label, disabled?}]
    parent_with_children = None
    for x in arr:
        if isinstance(x, dict) and isinstance(x.get("children"), list) and x["children"]:
            parent_with_children = x
            break
    if parent_with_children:
        child0 = parent_with_children["children"][0]
        child_fields_ok = ("id" in child0 and "label" in child0)
        child_keys = list(child0.keys())
    else:
        child_fields_ok = False
        child_keys = []

    return {
        "status_code": r.status_code,
        "top_level_count": len(arr),
        "sample_root": sample_root,
        "sample_root_keys": list(sample_root.keys()) if sample_root else None,
        "sample_root_has_children_field": sample_root_has_children,
        "parent_with_children_keys": (list(parent_with_children.keys()) if parent_with_children else None),
        "child_keys": child_keys,
        "child_id_sample": child0.get("id") if parent_with_children and child0 else None,
    }, shape_ok and root_fields_ok and child_fields_ok, (
        "" if (shape_ok and root_fields_ok and child_fields_ok)
        else f"shape={shape_ok} root_fields={root_fields_ok} child_fields={child_fields_ok}")


def case_b_unit_endpoint_semantics(sess) -> Dict[str, Any]:
    """DELETE /biz/project/unit/{ids} 路径参数语义——关联表主键 id（实测确认）。

    测试 1：传关联 id（数字）→ 删该关联行（DB 中 DEL_FLAG='2'）；
    测试 2：传 unitId（业务主键）→ 接口无法找到该 id 对应的有效关联，应被拒（项目级 + 关联 row 校验）。
    """
    # 造新课题 + 加 2 关联（用 case_1 的 companyA + case_4 的 inheritParent；都不在 case 6 删除集合）
    pid, info = add_project_for_unit(sess, f"{TEST_MARK}-E课题-语义")
    if pid is None:
        return {"project": info}, False, "造 E 课题失败"
    add1 = {"projectId": pid, "unitId": STATE["companyA"], "cooperationType": "LEAD",
            "remark": TEST_MARK}
    add2 = {"projectId": pid, "unitId": STATE["inheritParent"], "cooperationType": "PARTICIPANT",
            "remark": TEST_MARK}
    time.sleep(2.5)
    r1 = http(sess, "POST", "/biz/project/unit", json_body=add1)
    time.sleep(2.5)
    r2 = http(sess, "POST", "/biz/project/unit", json_body=add2)
    b1 = safe_json(r1); b2 = safe_json(r2)
    if not (r1.status_code == 200 and isinstance(b1, dict) and b1.get("code") == 200
            and r2.status_code == 200 and isinstance(b2, dict) and b2.get("code") == 200):
        return {"r1": b1, "r2": b2}, False, "加 2 关联失败"
    r = http(sess, "GET", "/biz/project/unit/list", params={"projectId": pid})
    b = safe_json(r)
    rows = b.get("data", []) if isinstance(b, dict) else []
    if len(rows) != 2:
        return {"rows": rows}, False, "关联数 != 2"
    id_a = next((x.get("id") for x in rows if x.get("unitId") == STATE["companyA"]), None)
    id_p = next((x.get("id") for x in rows if x.get("unitId") == STATE["inheritParent"]), None)
    unit_id_a = STATE["companyA"]
    if id_a is None or id_p is None:
        return {"rows": rows}, False, "未取到 id_a/id_p"

    # 测试 1：按关联 id 删除 → 应成功，删的是 A 关联行；P 关联仍在
    time.sleep(2.5)
    r = http(sess, "DELETE", f"/biz/project/unit/{id_a}")
    b = safe_json(r)
    del_ok_by_relid = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    ok, res = db_query(
        "SELECT DEL_FLAG FROM RUOYI.PROJECT_UNIT WHERE ID = ? AND PROJECT_ID = ?", [id_a, pid])
    a_del_flag = res["rows"][0][0] if ok and res["rows"] else None
    db_relid_ok = a_del_flag == "2"

    r = http(sess, "GET", "/biz/project/unit/list", params={"projectId": pid})
    b = safe_json(r)
    rows_after = b.get("data", []) if isinstance(b, dict) else []
    p_still = any(x.get("id") == id_p and x.get("delFlag") == "0" for x in rows_after)

    # 测试 2：按 unitId（非关联 id）删除 → 应被拒
    time.sleep(2.5)
    r = http(sess, "DELETE", f"/biz/project/unit/{unit_id_a}")
    b = safe_json(r)
    sem_reject_unitid = (r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200)
    sem_msg = str(b.get("msg") or "") if isinstance(b, dict) else ""

    # DB 复查：id_p 是否仍存在且 del_flag='0'
    ok, res = db_query(
        "SELECT DEL_FLAG FROM RUOYI.PROJECT_UNIT WHERE ID = ?", [id_p])
    p_del_flag = res["rows"][0][0] if ok and res["rows"] else None
    p_untouched = p_del_flag == "0"

    return {
        "pid": pid, "id_a": id_a, "id_p": id_p, "unit_id_a": unit_id_a,
        "del_ok_by_relid": del_ok_by_relid, "db_relid_ok": db_relid_ok,
        "rows_after_relid": [x.get("id") for x in rows_after],
        "p_still": p_still,
        "sem_reject_unitid": sem_reject_unitid, "sem_msg": sem_msg,
        "p_untouched": p_untouched, "p_del_flag": p_del_flag,
    }, del_ok_by_relid and db_relid_ok and p_still and sem_reject_unitid and p_untouched, (
        "" if (del_ok_by_relid and db_relid_ok and p_still and sem_reject_unitid and p_untouched)
        else f"relid_del={del_ok_by_relid} db_relid_ok={db_relid_ok} p_still={p_still} "
             f"sem_reject_unitid={sem_reject_unitid} p_untouched={p_untouched}")


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

    sess = requests.Session()
    sess.headers.update({"User-Agent": "task4-coop-unit-smoke/1.0"})
    token, login_resp = login(sess, ADMIN_USER, ADMIN_PASS)
    if not token:
        record("10_login_admin", False, {"username": ADMIN_USER}, login_resp, "admin 登录失败")
        dump_results()
        return 1
    sess.headers.update({"Authorization": "Bearer " + token})
    record("10_login_admin", True, {"username": ADMIN_USER}, {"status_code": login_resp.get("status_code")})

    cases = [
        ("01_company_three_layer", lambda: case_1_company_three_layer(sess)),
        ("02_school_two_layer", lambda: case_2_school_two_layer(sess)),
        ("03_contacts_crud", lambda: case_3_contacts(sess)),
        ("04_type_inherit", lambda: case_4_type_inherit(sess)),
        ("05_project_unit_assoc", lambda: case_5_project_unit_assoc(sess)),
        ("06_delete_protect", lambda: case_6_delete_protect(sess)),
        ("07_move_parent", lambda: case_7_move_parent(sess)),
        ("08_regression", lambda: case_8_regression(sess)),
        ("a_treeselect_shape", lambda: case_a_treeselect_shape(sess)),
        ("b_unit_endpoint_semantics", lambda: case_b_unit_endpoint_semantics(sess)),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record(name, ok, {"url": "/biz/unit/* or /biz/project/unit/*"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # 收尾
    resp, ok = cleanup_test_data()
    record("99_cleanup", ok, {"method": "db"}, {"msg": resp if isinstance(resp, str) else ""})

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
