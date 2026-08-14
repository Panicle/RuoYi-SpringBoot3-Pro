#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 4 — 阶段2 变更1（课题编号人工 + 预算细分）接口冒烟
- 覆盖（对应用例清单）：
  C1 新增课题不传 projectNo → 报错（非空校验）
  C2 新增课题传重复 projectNo → 报错（唯一校验）
  C3 新增课题带 10 项预算细分 → 成功，详情返回 budgetSplitList 10 条，budgetTotal = Σ 10 项（金额断言）
  C4 修改课题预算细分 → 全量替换正确，budgetTotal 更新
  C5 修改课题传不同 projectNo → 编号不变（不被改）
  C6 回归：人工编号下原课题 CRUD/状态机/数据权限核心不回归（增查改删 + 合法/非法状态迁移）
- 复用 smoke_project.py 的登录(RSA)/断言/http/db 直查工具；脚本 import 前需 DM_PASSWORD 环境变量
- 结果写 scripts/smoke/result_budget.jsonl（与 task-3 的 result.jsonl 隔离）
- 只测不改业务代码；发现的 Bug 记入报告
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from decimal import Decimal, getcontext
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke_project as SP  # noqa: E402   # 复用 login/http/record/db_* 等

getcontext().prec = 28

BASE_URL = SP.BASE_URL
ADMIN_USER = SP.ADMIN_USER
ADMIN_PASS = SP.ADMIN_PASS
TEST_MARK = "smoke-task4-budget"

SCRIPT_DIR = SP.SCRIPT_DIR
RESULT_PATH = os.path.join(SCRIPT_DIR, "result_budget.jsonl")

# 10 项预算科目（按字典 budget_category，值大写）
# ——金额总和 = 100.50+200.00+300.00+150.00+80.00+120.25+90.00+250.75+200.00+50.00 = 1541.50
# OUTSOURCING 由原 400.00 降至 200.00：B=840.75（EQUIPMENT=200 计入直接费但从 B 中减），间接费上限 252.23，
# INDIRECT=250.75≤252.23 ✓，委外上限 252.23，OUTSOURCING=200≤252.23 ✓（参见 task-2-report §四 C-2）
SPLITS_10 = [
    {"category": "LABOR",        "budgetAmount": "100.50"},
    {"category": "EQUIPMENT",    "budgetAmount": "200.00"},
    {"category": "MATERIAL",     "budgetAmount": "300.00"},
    {"category": "TESTING",      "budgetAmount": "150.00"},
    {"category": "FUEL",         "budgetAmount": "80.00"},
    {"category": "TRAVEL",       "budgetAmount": "120.25"},
    {"category": "PUBLICATION",  "budgetAmount": "90.00"},
    {"category": "INDIRECT",     "budgetAmount": "250.75"},
    {"category": "OUTSOURCING",  "budgetAmount": "200.00"},
    {"category": "TAX",          "budgetAmount": "50.00"},
]
SUM_10 = Decimal("1541.50")

# 替换后的 5 项预算细分——金额总和 = 1.00+2.00+3.00+4.00+5.00 = 15.00
SPLITS_5 = [
    {"category": "LABOR",     "budgetAmount": "1.00"},
    {"category": "EQUIPMENT", "budgetAmount": "2.00"},
    {"category": "MATERIAL",  "budgetAmount": "3.00"},
    {"category": "TESTING",   "budgetAmount": "4.00"},
    {"category": "FUEL",      "budgetAmount": "5.00"},
]
SUM_5 = Decimal("15.00")

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

def db_project_field(pid: int, col: str) -> Tuple[bool, Any]:
    return SP.db_project_field(pid, col)


def db_split_count(pid: int, del_flag: str = "0") -> Tuple[bool, int]:
    ok, res = SP.db_query(
        "SELECT COUNT(*) FROM RUOYI.BUDGET_SPLIT WHERE PROJECT_ID = ? AND DEL_FLAG = ?", [pid, del_flag])
    if not ok or not res["rows"]:
        return False, -1
    return True, res["rows"][0][0]


def db_split_rows(pid: int, del_flag: str = "0") -> Tuple[bool, Any]:
    ok, res = SP.db_query(
        "SELECT CATEGORY, BUDGET_AMOUNT FROM RUOYI.BUDGET_SPLIT WHERE PROJECT_ID = ? AND DEL_FLAG = ? "
        "ORDER BY CATEGORY", [pid, del_flag])
    return ok, res


def db_split_ids_before(pid: int) -> Tuple[bool, Dict[str, int]]:
    """取调整前每科目 split_id 字典，供 case_04 比对 split_id 不变（任务卡 §七.1）。"""
    ok, res = SP.db_query(
        "SELECT CATEGORY, SPLIT_ID FROM RUOYI.BUDGET_SPLIT WHERE PROJECT_ID = ? AND DEL_FLAG = '0' "
        "ORDER BY CATEGORY", [pid])
    if not ok or not res["rows"]:
        return False, {}
    return True, {r[0]: int(r[1]) for r in res["rows"]}


def add_project(sess, name: str, leader_id: int, project_no: str, project_type: str = "NATIONAL",
                splits: Optional[List[Dict]] = None) -> Tuple[Dict, bool, Optional[int]]:
    body = {"projectName": name, "projectType": project_type, "leaderId": leader_id,
            "projectNo": project_no, "projectCategory": "A", "specialty": "Y",
            "remark": TEST_MARK}
    if splits is not None:
        body["budgetSplitList"] = splits
    r = SP.http(sess, "POST", "/biz/project", json_body=body)
    b = SP.safe_json(r)
    data = SP.get_data(b)
    pid = data.get("projectId") if isinstance(data, dict) else None
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and pid is not None
    return {"request_body": body, "status_code": r.status_code, "body": b,
            "data": data, "raw": r.text[:500]}, ok, pid


def get_detail(sess, pid: int) -> Tuple[Dict, bool]:
    r = SP.http(sess, "GET", f"/biz/project/{pid}")
    b = SP.safe_json(r)
    data = SP.get_data(b)
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200 and isinstance(data, dict)
    return {"status_code": r.status_code, "body": b, "data": data, "raw": r.text[:500]}, ok


# ====================== C1：新增不传 projectNo → 报错 ======================

def case_01_add_no_projectno(sess) -> Tuple[Dict[str, Any], bool]:
    body = {"projectName": "冒烟预算-缺编号", "projectType": "NATIONAL", "leaderId": 3,
            "projectCategory": "A", "specialty": "Y", "remark": TEST_MARK}
    r = SP.http(sess, "POST", "/biz/project", json_body=body)
    b = SP.safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "课题编号不能为空" in msg
    return {"request_body": body, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok


# ====================== C2：新增传重复 projectNo → 报错 ======================

def case_02_add_dup_projectno(sess) -> Tuple[Dict[str, Any], bool]:
    # 先造一个课题占用编号
    no = "KY-2026-90021"
    _, ok_add, pid = add_project(sess, "冒烟预算-占用编号", 3, no)
    if not ok_add or pid is None:
        return {"add_first": {"ok": ok_add, "pid": pid}}, False
    STATE["dup_owner_id"] = pid
    STATE["dup_owner_no"] = no
    body = {"projectName": "冒烟预算-重复编号", "projectType": "NATIONAL", "leaderId": 3,
            "projectNo": no, "projectCategory": "A", "specialty": "Y", "remark": TEST_MARK}
    r = SP.http(sess, "POST", "/biz/project", json_body=body)
    b = SP.safe_json(r)
    msg = str(b.get("msg") or "") if isinstance(b, dict) else ""
    ok = r.status_code == 200 and isinstance(b, dict) and b.get("code") != 200 and "课题编号已存在" in msg
    return {"first": {"ok": ok_add, "pid": pid, "no": no},
            "dup_request_body": body, "status_code": r.status_code, "body": b, "raw": r.text[:400]}, ok


# ====================== C3：新增带 10 项预算细分 ======================

def case_03_add_10_splits(sess) -> Tuple[Dict[str, Any], bool]:
    no = "KY-2026-90031"
    resp, ok_add, pid = add_project(sess, "冒烟预算-十项细分", 3, no, splits=SPLITS_10)
    if not ok_add or pid is None:
        return resp, False
    STATE["split10_id"] = pid
    STATE["split10_no"] = no
    # 详情：budgetSplitList 10 条 + budgetTotal = Σ
    det, ok_det = get_detail(sess, pid)
    data = det.get("data") or {}
    splits = data.get("budgetSplitList")
    total = data.get("budgetTotal")
    ok_splits = isinstance(splits, list) and len(splits) == 10
    ok_total = False
    if total is not None:
        try:
            ok_total = Decimal(str(total)) == SUM_10
        except Exception:
            ok_total = False
    # DB 直查：budget_split 10 条 + 主表 BUDGET_TOTAL
    ok_db, db_cnt = db_split_count(pid)
    _, db_total = db_project_field(pid, "BUDGET_TOTAL")
    ok_db_total = db_total is not None and Decimal(str(db_total)) == SUM_10
    ok = ok_add and ok_det and ok_splits and ok_total and ok_db and db_cnt == 10 and ok_db_total
    note = ""
    if not ok:
        note = (f"splits_len={len(splits) if isinstance(splits, list) else 'NA'} "
                f"total={total} (期望 {SUM_10}) db_cnt={db_cnt} db_total={db_total}")
    return {"add": resp, "detail": det,
            "splits_len": len(splits) if isinstance(splits, list) else None,
            "budget_total_api": str(total), "db_split_count": db_cnt,
            "db_budget_total": str(db_total), "expected_total": str(SUM_10)}, ok


# ====================== C4：修改预算细分 → D1 增量更新（金额清零保留行） ======================

def case_04_update_splits_replace(sess) -> Tuple[Dict[str, Any], bool]:
    """任务卡 §七.1：D1 改造后，改预算细分时「按 category 增量更新保 split_id」——
    传 SPLITS_5（5 项）后：
    - 5 项金额 = SPLITS_5 的预算金额
    - 另 5 项金额 = 0（库中存在但本次未传 → 保留行+金额置 0）
    - 有效行 = 10 条（无逻辑删）
    - del_flag='2' 行 = 0 条
    - 各行 split_id 与调整前逐一相同
    """
    pid = STATE["split10_id"]
    # 取调整前的 split_id 字典
    _, ids_before = db_split_ids_before(pid)
    body = {"projectId": pid, "budgetSplitList": SPLITS_5}
    r = SP.http(sess, "PUT", "/biz/project", json_body=body)
    b = SP.safe_json(r)
    ok_put = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    # DB：活跃仍 10 条；不再产生 del_flag='2'
    ok_db, new_cnt = db_split_count(pid, "0")
    _, old_cnt = db_split_count(pid, "2")
    _, db_total = db_project_field(pid, "BUDGET_TOTAL")
    ok_total = db_total is not None and Decimal(str(db_total)) == SUM_5

    # 5 项金额正确
    rows5 = {r[0]: Decimal(str(r[1])) for r in (db_split_rows(pid, "0")[1]["rows"])}
    expected5 = {s["category"]: Decimal(s["budgetAmount"]) for s in SPLITS_5}
    expected5_zero = {"EQUIPMENT", "MATERIAL", "TESTING", "FUEL", "TRAVEL", "PUBLICATION",
                      "INDIRECT", "OUTSOURCING", "TAX"}
    # 移除 SPLITS_5 涉及的 5 项（LABOR/EQUIPMENT/MATERIAL/TESTING/FUEL），剩余应全 0
    expected5_zero = expected5_zero - expected5.keys()
    ok_5_amount = all(rows5.get(s["category"]) == expected5[s["category"]] for s in SPLITS_5)
    ok_5_zero = all(rows5.get(cat) == Decimal("0.00") for cat in expected5_zero)

    # split_id 与调整前逐一相同
    _, ids_after = db_split_ids_before(pid)
    all_same = len(ids_before) == len(ids_after) and all(
        ids_before.get(cat) == ids_after.get(cat) for cat in ids_before
    )

    # 详情回显（10 条）
    det, ok_det = get_detail(sess, pid)
    splits = (det.get("data") or {}).get("budgetSplitList")
    ok_detail = isinstance(splits, list) and len(splits) == 10
    api_total = (det.get("data") or {}).get("budgetTotal")
    ok_api_total = api_total is not None and Decimal(str(api_total)) == SUM_5

    ok = (ok_put and new_cnt == 10 and old_cnt == 0 and ok_total
          and ok_5_amount and ok_5_zero and all_same
          and ok_detail and ok_api_total)
    note = ""
    if not ok:
        note = (f"new_cnt={new_cnt} old_cnt={old_cnt} db_total={db_total} "
                f"5_ok={ok_5_amount} zero_ok={ok_5_zero} ids_same={all_same} "
                f"detail_len={len(splits) if isinstance(splits, list) else 'NA'} "
                f"api_total={api_total}")
    return {"put_body": b, "db_new_cnt": new_cnt, "db_old_cnt": old_cnt, "db_budget_total": str(db_total),
            "detail": det, "expected_total": str(SUM_5),
            "ids_before": ids_before, "ids_after": ids_after, "split_id_all_same": all_same,
            "rows_after": {k: str(v) for k, v in rows5.items()}}, ok


# ====================== C5：修改传不同 projectNo → 编号不变 ======================

def case_05_update_projectno_ignored(sess) -> Tuple[Dict[str, Any], bool]:
    pid = STATE["split10_id"]
    orig_no = STATE["split10_no"]
    body = {"projectId": pid, "projectName": "冒烟预算-十项细分(改名)",
            "projectNo": "KY-HACK-99999"}
    r = SP.http(sess, "PUT", "/biz/project", json_body=body)
    b = SP.safe_json(r)
    ok_put = r.status_code == 200 and isinstance(b, dict) and b.get("code") == 200
    _, no_after = db_project_field(pid, "PROJECT_NO")
    ok = ok_put and no_after == orig_no
    return {"put_body": b, "orig_no": orig_no, "db_no_after": no_after, "tried_no": "KY-HACK-99999"}, ok


# ====================== C6：回归——人工编号下原 CRUD/状态机 ======================

def case_06_regression_core(sess) -> Tuple[Dict[str, Any], bool]:
    """人工编号 + 预算细分场景下，原课题 CRUD/状态机核心不回归：
    (a) DRAFT->ACTIVE->COMPLETED->ACCEPTED->ARCHIVED 合法链 + 归档后编辑拒绝（主课题）
    (b) 独立 DRAFT 课题删除成功路径（逻辑删除 del_flag='2'，对齐 task-3 case_37 语义；
        归档课题不可删除为既有业务规则，另验归档删除拒绝）。"""
    # (a) 主课题状态机全链
    no = "KY-2026-90061"
    resp, ok_add, pid = add_project(sess, "冒烟预算-回归主课题", 3, no, splits=SPLITS_10)
    if not ok_add or pid is None:
        return resp, False
    STATE["reg_id"] = pid
    # 列表可按 projectNo 精确命中
    r = SP.http(sess, "GET", "/biz/project/list", params={"projectNo": no, "pageNum": 1, "pageSize": 10})
    b = SP.safe_json(r)
    rows = b.get("rows", []) if isinstance(b, dict) else []
    ok_list = r.status_code == 200 and any(x.get("projectId") == pid for x in rows)
    steps = ["ACTIVE", "COMPLETED", "ACCEPTED"]
    st_ok = True
    for t in steps:
        r = SP.http(sess, "POST", "/biz/project/changeStatus",
                    json_body={"projectId": pid, "targetStatus": t})
        bb = SP.safe_json(r)
        st_ok = st_ok and bb is not None and bb.get("code") == 200
    r = SP.http(sess, "POST", "/biz/project/archive", json_body={"projectId": pid})
    b_arc = SP.safe_json(r)
    ok_arc = b_arc is not None and b_arc.get("code") == 200
    _, final_status = db_project_field(pid, "STATUS")
    ok_chain = st_ok and ok_arc and final_status == "ARCHIVED"
    # 归档后编辑拒绝
    body = {"projectId": pid, "projectName": "尝试改归档课题"}
    r = SP.http(sess, "PUT", "/biz/project", json_body=body)
    b_edit = SP.safe_json(r)
    msg_edit = str(b_edit.get("msg") or "") if isinstance(b_edit, dict) else ""
    ok_edit_reject = "已归档课题不可修改" in msg_edit
    # 归档课题删除拒绝（既有规则，见证）
    r = SP.http(sess, "DELETE", f"/biz/project/{pid}")
    b_del_reject = SP.safe_json(r)
    msg_del_rej = str(b_del_reject.get("msg") or "") if isinstance(b_del_reject, dict) else ""
    ok_del_reject = "已归档课题不可删除" in msg_del_rej
    # (b) 独立 DRAFT 课题删除成功
    _, ok_del2, pid2 = add_project(sess, "冒烟预算-回归删除课题", 3, "KY-2026-90062")
    ok_del2 = ok_del2 and pid2 is not None
    del_detail: Dict[str, Any] = {}
    ok_del_flag = False
    if ok_del2:
        ok_db, r0 = SP.db_execute(
            "UPDATE RUOYI.PROJECT_MEMBER SET DEL_FLAG='2' WHERE PROJECT_ID = ?", [pid2])
        if ok_db:
            r = SP.http(sess, "DELETE", f"/biz/project/{pid2}")
            b_del = SP.safe_json(r)
            del_detail = {"status_code": r.status_code, "body": b_del}
            ok_del_success = b_del is not None and b_del.get("code") == 200
            _, del_flag = db_project_field(pid2, "DEL_FLAG")
            ok_del_flag = ok_del_success and del_flag == "2"
            del_detail["del_flag"] = del_flag
        else:
            del_detail = {"err": str(r0)}
    ok = ok_add and ok_list and ok_chain and ok_edit_reject and ok_del_reject and ok_del_flag
    note = ""
    if not ok:
        note = (f"list={ok_list} chain={st_ok}/{ok_arc}/{final_status} "
                f"edit_reject={ok_edit_reject} del_reject={ok_del_reject} del_success={ok_del_flag}")
    return {"add": resp, "list_ok": ok_list, "chain_ok": st_ok and ok_arc, "final_status": final_status,
            "edit_archived_rejected": ok_edit_reject, "archive_del_rejected": ok_del_reject,
            "draft_delete": del_detail, "draft_del_flag_ok": ok_del_flag}, ok


# ====================== 收尾 ======================

def cleanup_test_data() -> Tuple[bool, str]:
    ok, r = SP.db_execute("DELETE FROM RUOYI.PROJECT_MEMBER WHERE PROJECT_ID IN "
                          "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清成员失败: " + str(r)
    ok, r = SP.db_execute("DELETE FROM RUOYI.BUDGET_SPLIT WHERE PROJECT_ID IN "
                          "(SELECT PROJECT_ID FROM RUOYI.PROJECT WHERE REMARK = ?)", [TEST_MARK])
    if not ok:
        return False, "清预算细分失败: " + str(r)
    ok, r = SP.db_execute("DELETE FROM RUOYI.PROJECT WHERE REMARK = ?", [TEST_MARK])
    if not ok:
        return False, "清课题失败: " + str(r)
    return True, "ok"


# ====================== 入口 ======================

def main() -> int:
    if not SP.wait_ready(60):
        record_local("00_backend_ready", False, {}, {"elapsed": 60}, "后端 60 秒内未就绪")
        dump_results()
        return 1
    print("[boot] backend ready")

    cleanup_test_data()

    import requests as _req
    s = _req.Session()
    s.headers.update({"User-Agent": "task4-budget-smoke/1.0"})
    token, lresp = SP.login(s, ADMIN_USER, ADMIN_PASS)
    ok = token is not None
    if ok:
        s.headers.update({"Authorization": "Bearer " + token})
    record_local("10_login_admin", ok, {"username": ADMIN_USER},
                 {"status_code": lresp.get("status_code"), "body": lresp.get("body")})

    cases = [
        ("11_add_no_projectno_rejected", lambda: case_01_add_no_projectno(s)),
        ("12_add_dup_projectno_rejected", lambda: case_02_add_dup_projectno(s)),
        ("13_add_10_splits", lambda: case_03_add_10_splits(s)),
        ("14_update_splits_replace", lambda: case_04_update_splits_replace(s)),
        ("15_update_projectno_ignored", lambda: case_05_update_projectno_ignored(s)),
        ("16_regression_core", lambda: case_06_regression_core(s)),
    ]
    for name, fn in cases:
        try:
            res = fn()
            if isinstance(res, tuple) and len(res) == 3:
                resp, ok, note = res
            else:
                resp, ok = res
                note = ""
            record_local(name, ok, {"url": "/biz/project/*"}, resp, note)
        except Exception as e:  # noqa: BLE001
            record_local(name, False, {}, {"_exception": repr(e)}, "脚本异常: " + repr(e))

    # 收尾
    ok_c, msg_c = cleanup_test_data()
    record_local("90_cleanup", ok_c, {"method": "db"}, {"msg": msg_c}, msg_c)

    dump_results()
    failed = [r for r in RESULTS if not r["ok"]]
    print(f"\n[SUMMARY] total={len(RESULTS)} pass={len(RESULTS) - len(failed)} fail={len(failed)}")
    for r in failed:
        print(f"  - {r['case']}: {r['note']}")
    return 10 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
