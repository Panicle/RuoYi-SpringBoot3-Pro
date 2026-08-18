# -*- coding: utf-8 -*-
"""测试二级公司数据（幂等，可重跑）：
集团 root（sys_dept parent_id=0）下建 2 个二级公司（选单位/按单位预算测试用）：
  设计院 → 设计一室（4 人：1 主任 dept_leader(104)+researcher(105)，3 科员 researcher(105)）
  检测中心 → 检测部（4 人：同上）
人员 user_name 前缀 sj（设计）/ic（检测）避免与现有冲突；密码复用 admin。
幂等：存在则 UPDATE dept_id 跳过新增；user_role 存在性判定。
"""
import sys
import datetime

import dmPython

CONN_KW = dict(user="SYSDBA", password="Ruoyi12345", server="localhost", port=5236)

ROLE_DL, ROLE_RES = 104, 105


def out(s):
    sys.stdout.buffer.write((str(s) + "\n").encode("utf-8"))


class Db:
    def __init__(self):
        self.conn = dmPython.connect(**CONN_KW)
        self.cur = self.conn.cursor()
        self.cur.execute("SET SCHEMA RUOYI")

    def one(self, sql, *params):
        self.cur.execute(sql, list(params) if params else None)
        return self.cur.fetchone()

    def all(self, sql, *params):
        self.cur.execute(sql, list(params) if params else None)
        return self.cur.fetchall()

    def exe(self, sql, *params):
        self.cur.execute(sql, list(params) if params else None)

    def commit(self):
        self.conn.commit()


db = Db()
NOW = datetime.datetime.now()
CNT = {"dept": 0, "user": 0, "role": 0}


# ============ 组织 ============

def ensure_dept(parent_id, ancestors, name, order_num):
    row = db.one("SELECT dept_id FROM sys_dept WHERE dept_name=? AND parent_id=? AND del_flag='0'", name, parent_id)
    if row:
        return row[0]
    db.exe("INSERT INTO sys_dept(parent_id, ancestors, dept_name, order_num, status, del_flag, create_by, create_time)"
           " VALUES(?,?,?,?, '0','0','admin',?)", parent_id, ancestors, name, order_num, NOW)
    CNT["dept"] += 1
    return db.one("SELECT dept_id FROM sys_dept WHERE dept_name=? AND parent_id=? AND del_flag='0'", name, parent_id)[0]


root = db.one("SELECT dept_id, ancestors FROM sys_dept WHERE parent_id=0 AND del_flag='0'")
root_id, root_anc = root[0], (root[1] or "0")

# (公司名, 子部门名, 人员: (user_name, 姓名, 是否主任))
COMPANIES = [
    ("设计院", "设计一室", [
        ("sj01", "施明", True), ("sj02", "沈青", False), ("sj03", "宋磊", False), ("sj04", "邵雨", False),
    ]),
    ("检测中心", "检测部", [
        ("ic01", "江峰", True), ("ic02", "贾宁", False), ("ic03", "蒋雪", False), ("ic04", "金涛", False),
    ]),
]


# ============ 人员 ============

ADMIN = db.one("SELECT password, tenant_id FROM sys_user WHERE user_name='admin'")
ADMIN_PWD, TENANT = ADMIN[0], ADMIN[1]


def ensure_user(user_name, nick, dept_id, role_ids, phone):
    row = db.one("SELECT user_id FROM sys_user WHERE user_name=? AND del_flag='0'", user_name)
    if row:
        uid = row[0]
        db.exe("UPDATE sys_user SET dept_id=? WHERE user_id=?", dept_id, uid)
    else:
        db.exe("INSERT INTO sys_user(tenant_id, dept_id, user_name, nick_name, user_type, email, phonenumber,"
               " sex, password, status, del_flag, create_by, create_time)"
               " VALUES(?,?,?,?, '00','',?, '0',?, '0','0','admin',?)",
               TENANT, dept_id, user_name, nick, phone, ADMIN_PWD, NOW)
        uid = db.one("SELECT user_id FROM sys_user WHERE user_name=? AND del_flag='0'", user_name)[0]
        CNT["user"] += 1
    for rid in role_ids:
        if not db.one("SELECT 1 FROM sys_user_role WHERE user_id=? AND role_id=?", uid, rid):
            db.exe("INSERT INTO sys_user_role(user_id, role_id) VALUES(?,?)", uid, rid)
            CNT["role"] += 1
    return uid


phone_seq = 13800003001
for i, (comp_name, sub_name, staff) in enumerate(COMPANIES, 1):
    comp_id = ensure_dept(root_id, root_anc + "," + str(root_id), comp_name, i)
    comp_anc = root_anc + "," + str(root_id) + "," + str(comp_id)
    sub_id = ensure_dept(comp_id, comp_anc, sub_name, 1)
    for uname, nick, is_leader in staff:
        roles = [ROLE_DL, ROLE_RES] if is_leader else [ROLE_RES]
        ensure_user(uname, nick, sub_id, roles, str(phone_seq))
        phone_seq += 1

db.commit()
out("=== DONE === " + str(CNT))

# 校验输出
out("VERIFY companies: " + str(db.all(
    "SELECT d.dept_id, d.dept_name, d.parent_id, d.ancestors FROM sys_dept d"
    " WHERE d.dept_name IN ('设计院','检测中心','设计一室','检测部') AND d.del_flag='0' ORDER BY d.dept_id")))
out("VERIFY users per sub dept: " + str(db.all(
    "SELECT d.dept_name, COUNT(*) FROM sys_user u JOIN sys_dept d ON d.dept_id=u.dept_id"
    " WHERE d.dept_name IN ('设计一室','检测部') AND u.del_flag='0' GROUP BY d.dept_name ORDER BY d.dept_name")))
out("VERIFY roles per user: " + str(db.all(
    "SELECT d.dept_name, u.user_name, COUNT(ur.role_id) FROM sys_user u"
    " JOIN sys_dept d ON d.dept_id=u.dept_id LEFT JOIN sys_user_role ur ON ur.user_id=u.user_id"
    " WHERE d.dept_name IN ('设计一室','检测部') AND u.del_flag='0'"
    " GROUP BY d.dept_name, u.user_name ORDER BY d.dept_name, u.user_name")))
