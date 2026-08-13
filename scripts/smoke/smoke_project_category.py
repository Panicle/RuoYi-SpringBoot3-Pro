#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 4 — 阶段2 变更2（项目类别/专业分类 + 删除级联 + 主持人改组长）接口冒烟
- 覆盖（对应用例清单）：
  C1 新增不传 projectCategory → 报错（必填校验：项目类别不能为空）
  C2 新增传 A 不传 specialty → 报错（必填校验：专业分类不能为空）
  C3 新增传 A/Y → 成功，详情返回 projectCategory=A、specialty=Y；列表按两字段筛选生效（cbdc946 过滤修复）
  C4 删除含组长+成员的课题 → 成功，project 与 project_member 均 del_flag='2'（级联删除验证）
  C5 回归：原 CRUD/状态机/数据权限不回归（复用 smoke_project.py 全套 43 用例）
  C6 member_role HOST label=组长（DB 直查确认）
- 复用 smoke_project.py 的登录(RSA)/断言/http/db 直查工具；运行前需 export DM_PASSWORD=xxx
- 结果写 scripts/smoke/result_category.jsonl
- 只测不改业务代码；发现的 Bug 记入报告
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke_project as SP  # noqa: E402  # 复用 login/http/record/db_* 等（import 需 DM_PASSWORD）

BASE_URL = SP.BASE_URL
ADMIN_USER = SP.ADMIN_USER
ADMIN_PASS = SP.ADMIN_PASS
TEST_MARK = "smoke-task4-category"

SCRIPT_DIR = SP.SCRIPT_DIR
RESULT_PATH = os.path.join(SCRIPT_DIR, "result_category.jsonl")

RESULTS: List[Dict[str, Any]] = []
STATE: Dict[str, Any] = {}


def record_local(case: str, ok: bool, req: Dict[str, Any], resp: Dict[str, Any], note: str = "") -> None:
    RESULTS.append({
        "case": case, "ok": ok, "note": note, "request": req, "response": resp,
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
    })
    flag = "PASS" if ok else "FAIL"
    print(f"[{flag}] {case} :: {note}")


def dump_results() -> None:
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        for r in RESULTS:
            f.write(json.dumps(r, ensure_ascii=False, default=str) + "\n")


# ====================== 工具 ======================

def add_project(sess, name: str, leader_id: int, project_type: str = "NATIONAL",
                project_category: str = None, specialty: str = None) -> Tuple[Dict, bool, Optional[int]]:
    # 阶段1 变更1起 insert 需人工 projectNo（如 KY-2026-9xxxx）；自动生成唯一编号
    STATE["no_seq"] = STATE.get("no_seq", (int(time.time()) % 800) + 100) + 1
    body = {"projectName": name, "projectType": project_type, "leaderId": leader_id,
            "projectNo": f"KY-2026-{(STATE['no_seq'] % 900) + 100:03d}",
            "remark": TEST_MARK}
    if project_category is not None:
        body["projectCategory"] = project_category
    if specialty is not None:
        body["specialty"] = specialty
    r = SP.http(sess, "POST", "/biz/project", json_body=body)
    b = SP.safe_json(r)
    data = SP.get_data(b)
    pid = data.get("projectId") if isinstance(data, dict) else None
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and pid is not None
    return {"request_body": body, "status_code": r.status_code, "body": b,
            "data": data, "raw": r.text[:500]}, ok, pid


def get_detail(sess, pid: int) -> Tuple[Dict[str, Any], Optional[Dict[str, Any]]]:
    r = SP.http(sess, "GET", f"/biz/project/{pid}")
    b = SP.safe_json(r)
    data = SP.get_data(b)
    return {"status_code": r.status_code, "code": b.get("code") if isinstance(b, dict) else None,
            "body": b, "raw": r.text[:500]}, data


def cleanup_test_data() -> Tuple[bool, str]:
    """收尾：按本脚本 TEST_MARK 清理课题/成员 + 共享测试用户。"""
    ok, r = SP.db_execute("DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
                          "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清成员失败: " + str(r)
    ok, r = SP.db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清课题失败: " + str(r)
    ok, r = SP.db_execute("DELETE FROM RUOYI.SYS_USER_ROLE WHERE USER_ID IN (?, ?, ?, ?, ?)",
                          [SP.SCI_USER_ID, SP.RES_USER_ID, SP.DL_USER_ID, SP.MEM2_USER_ID, SP.MEM3_USER_ID])
    if not ok:
        return False, "清用户角色失败: " + str(r)
    ok, r = SP.db_execute("DELETE FROM RUOYI.SYS_USER WHERE USER_ID IN (?, ?, ?, ?, ?)",
                          [SP.SCI_USER_ID, SP.RES_USER_ID, SP.DL_USER_ID, SP.MEM2_USER_ID, SP.MEM3_USER_ID])
    if not ok:
        return False, "清用户失败: " + str(r)
    return True, "ok"


# ====================== 用例 ======================

def case_c1_required_category(sess) -> Tuple[Dict[str, Any], bool]:
    """C1：新增不传 projectCategory/specialty → 报错（项目类别不能为空）。"""
    resp, ok, pid = add_project(sess, "冒烟C1-缺项目类别", 3, project_type="NATIONAL")
    b = resp["body"]
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    cond = (resp["status_code"] == 200 and isinstance(b, dict) and b.get("code") != 200
            and "项目类别不能为空" in msg)
    return resp, cond


def case_c2_required_specialty(sess) -> Tuple[Dict[str, Any], bool]:
    """C2：新增传 A 不传 specialty → 报错（专业分类不能为空）。"""
    resp, ok, pid = add_project(sess, "冒烟C2-缺专业分类", 3, project_type="NATIONAL", project_category="A")
    b = resp["body"]
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    cond = (resp["status_code"] == 200 and isinstance(b, dict) and b.get("code") != 200
            and "专业分类不能为空" in msg)
    return resp, cond


def case_c3_add_ay(sess) -> Tuple[Dict[str, Any], bool]:
    """C3a：新增传 A/Y → 成功，记录 projectId。"""
    resp, ok, pid = add_project(sess, "冒烟C3-项目类别A专业Y", 3, project_type="NATIONAL",
                                project_category="A", specialty="Y")
    STATE["c3_id"] = pid
    return resp, ok


def case_c3_detail(sess) -> Tuple[Dict[str, Any], bool]:
    """C3b：详情返回 projectCategory=A、specialty=Y。"""
    pid = STATE["c3_id"]
    resp, data = get_detail(sess, pid)
    ok = (data is not None
          and data.get("projectCategory") == "A" and data.get("specialty") == "Y")
    subset = {k: data.get(k) for k in ("projectId", "projectNo", "projectCategory", "specialty",
                                       "projectCategoryLabel", "specialtyLabel")} if data else None
    return {"status_code": resp["status_code"], "code": resp["code"], "data_subset": subset,
            "raw": resp["raw"]}, ok


def case_c3_list_filter(sess) -> Tuple[Dict[str, Any], bool]:
    """C3c：列表按两字段筛选生效（cbdc946 过滤修复）。
    正向 projectCategory=A&specialty=Y 应命中 C3；反向 A&J 不应命中。"""
    pid = STATE["c3_id"]
    checks: Dict[str, Any] = {}

    r = SP.http(sess, "GET", "/biz/project/list",
                params={"projectCategory": "A", "specialty": "Y", "pageNum": 1, "pageSize": 50})
    b = SP.safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["ay_hit"] = {"total": b.get("total") if isinstance(b, dict) else None,
                        "found_c3": any(x.get("projectId") == pid for x in rows),
                        "all_category_A": all(x.get("projectCategory") == "A" for x in rows),
                        "all_specialty_Y": all(x.get("specialty") == "Y" for x in rows),
                        "status_code": r.status_code}

    r = SP.http(sess, "GET", "/biz/project/list",
                params={"projectCategory": "A", "specialty": "J", "pageNum": 1, "pageSize": 50})
    b = SP.safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    checks["aj_miss"] = {"total": b.get("total") if isinstance(b, dict) else None,
                         "c3_absent": all(x.get("projectId") != pid for x in rows),
                         "all_specialty_J": all(x.get("specialty") == "J" for x in rows),
                         "status_code": r.status_code}

    ok = (checks["ay_hit"]["found_c3"] and checks["ay_hit"]["all_category_A"]
          and checks["ay_hit"]["all_specialty_Y"] and checks["aj_miss"]["c3_absent"])
    return {"checks": checks}, ok


def case_c4_cascade_delete(sess) -> Tuple[Dict[str, Any], bool]:
    """C4：删除含组长+成员的课题 → project 与 project_member 均 del_flag='2'（级联删除）。"""
    # 造课题（自动生成 HOST 组长行）
    resp, ok_add, pid = add_project(sess, "冒烟C4-级联删除验证", 3, project_type="NATIONAL",
                                    project_category="B", specialty="C")
    if not ok_add:
        return resp, False
    # 加两名成员
    body = {"projectId": pid, "members": [{"userId": SP.MEM2_USER_ID, "role": "PARTICIPANT"},
                                          {"userId": SP.MEM3_USER_ID, "role": "PARTICIPANT"}]}
    r = SP.http(sess, "POST", "/biz/project/member", json_body=body)
    b = SP.safe_json(r)
    ok_mem = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    if not ok_mem:
        return {"add_body": resp["body"], "add_members_body": b}, False

    # 前置 DB：有效成员数（HOST + 2 PARTICIPANT = 3）
    ok, res = SP.db_query("SELECT COUNT(*) FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID = ? AND DEL_FLAG='0'", [pid])
    pre_active = res["rows"][0][0] if ok and res["rows"] else -1

    # 删除课题
    r = SP.http(sess, "DELETE", f"/biz/project/{pid}")
    b = SP.safe_json(r)
    ok_del = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200

    # DB 后置：project.del_flag 与 project_member.del_flag
    ok2, res2 = SP.db_query("SELECT DEL_FLAG FROM RUOYI.PROJECT WHERE PROJECT_ID = ?", [pid])
    proj_del = res2["rows"][0][0] if ok2 and res2["rows"] else None
    ok3, res3 = SP.db_query("SELECT DEL_FLAG, COUNT(*) FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID = ? GROUP BY DEL_FLAG", [pid])
    member_del_map = {r[0]: r[1] for r in res3["rows"]} if ok3 and res3["rows"] else {}
    all_member_soft = member_del_map.get("2") == pre_active and "0" not in member_del_map

    # 列表不出现
    r2 = SP.http(sess, "GET", "/biz/project/list", params={"pageNum": 1, "pageSize": 100})
    b2 = SP.safe_json(r2)
    rows2 = b2.get("rows", []) if isinstance(b2, dict) else []
    not_in_list = all(x.get("projectId") != pid for x in rows2)

    ok = ok_del and proj_del == "2" and all_member_soft and not_in_list
    return {"add_body": resp["body"], "add_members_body": b, "pre_active_members": pre_active,
            "delete_body": b, "db_project_del_flag": proj_del, "db_member_del_map": member_del_map,
            "all_member_soft_deleted": all_member_soft, "in_list_after": not_in_list,
            "status_code": r.status_code}, ok


def case_c6_host_label_db() -> Tuple[Dict[str, Any], bool]:
    """C6：DB 直查 member_role 字典 HOST 标签 = 组长。"""
    ok, res = SP.db_query("SELECT DICT_VALUE, DICT_LABEL, STATUS FROM RUOYI.SYS_DICT_DATA "
                          "WHERE DICT_TYPE='member_role' AND DICT_VALUE='HOST'")
    rows = [list(r) for r in res["rows"]] if ok and res["rows"] else []
    cond = ok and len(rows) == 1 and rows[0][1] == "组长"
    return {"dict_rows": rows}, cond


def case_c5_regression() -> Tuple[Dict[str, Any], bool]:
    """C5：回归——子进程跑 smoke_project.py 全套，解析 SUMMARY。"""
    env = dict(os.environ)
    env["DM_PASSWORD"] = os.environ.get("DM_PASSWORD", "")
    env["PYTHONIOENCODING"] = "utf-8"  # 子进程 stdout 强制 UTF-8，规避 Windows GBK 管道解码异常
    try:
        proc = subprocess.run([sys.executable, os.path.join(SCRIPT_DIR, "smoke_project.py")],
                              capture_output=True, text=True, encoding="utf-8",
                              env=env, timeout=900)
    except Exception as e:  # noqa: BLE001
        return {"_exception": repr(e)}, False
    out = (proc.stdout or "") + (proc.stderr or "")
    m = re.search(r"\[SUMMARY\] total=(\d+) pass=(\d+) fail=(\d+)", out)
    if not m:
        return {"exit_code": proc.returncode, "tail": out[-1500:]}, False
    total, passed, failed = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    ok = failed == 0 and total >= 40
    fail_lines = [ln for ln in out.splitlines() if ln.startswith("  - ")]
    return {"exit_code": proc.returncode, "total": total, "pass": passed, "fail": failed,
            "fail_lines": fail_lines[:20], "tail": out[-800:]}, ok


# ====================== main ======================

def main() -> int:
    print(f"[boot] base url = {BASE_URL}")
    if not SP.wait_ready(60):
        record_local("00_backend_ready", False, {}, {"elapsed": 60}, "后端 60 秒内未就绪")
        dump_results()
        return 1
    print("[boot] backend ready")

    # 清理历史残留（幂等）
    cleanup_test_data()

    # ---- C5 回归：先跑 smoke_project.py 全套（自带建测试用户并在末尾清理）----
    resp, ok = case_c5_regression()
    record_local("05_regression_full", ok, {"method": "subprocess smoke_project.py"},
                 resp, "" if ok else "回归套件存在失败项，详见 response")

    # ---- 重建测试用户（回归套件末尾已清理）----
    ok, msg = SP.setup_test_users()
    record_local("05_setup_test_users", ok, {"target": "sys_user+sys_user_role"}, {"msg": msg}, msg)

    # ---- 主会话：science_admin（role 101, data_scope=1）----
    sess = SP.requests.Session()
    sess.headers.update({"User-Agent": "task4-category-smoke/1.0"})
    token, login_resp = SP.login(sess, SP.SCI_USERNAME, ADMIN_PASS)
    ok_login = token is not None
    if ok_login:
        sess.headers.update({"Authorization": "Bearer " + token})
    record_local("10_login_sci_admin", ok_login, {"username": SP.SCI_USERNAME},
                 {"status_code": login_resp.get("status_code"), "body": login_resp.get("body")})

    cases = [
        ("11_c1_required_category", lambda: case_c1_required_category(sess)),
        ("12_c2_required_specialty", lambda: case_c2_required_specialty(sess)),
        ("13_c3_add_ay", lambda: case_c3_add_ay(sess)),
        ("14_c3_detail", lambda: case_c3_detail(sess)),
        ("15_c3_list_filter", lambda: case_c3_list_filter(sess)),
        ("16_c4_cascade_delete", lambda: case_c4_cascade_delete(sess)),
        ("17_c6_host_label_db", lambda: case_c6_host_label_db()),
    ]
    for name, fn in cases:
        try:
            resp, ok = fn()
            record_local(name, ok, {"url": "/biz/project/*"}, resp)
        except Exception as e:  # noqa: BLE001
            record_local(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # ---- 收尾 ----
    ok_c, msg_c = cleanup_test_data()
    record_local("50_cleanup", ok_c, {"method": "db"}, {"msg": msg_c})

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
