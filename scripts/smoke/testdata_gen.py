# -*- coding: utf-8 -*-
"""科研管理平台 测试数据生成（devdm 达梦，SYSDBA/RUOYI，幂等可重跑）

组织结构：集团公司(总公司100) → 科研所(101) → 5 部门
  办公室(office) / 劳人科(labor_hr) / 科管科(science_admin) / 研发一室 / 研发二室
每部门 5 人（1 科长 + 4 科员），共 25 人，统一密码 admin123（复用 admin 哈希）。
研发室 10 人每人 3 个课题（组长=本人，ACTIVE，预算 50 万），共 30 课题。
每人一份科研档案。

角色映射：办公室→office(102) / 劳人科→labor_hr(103) / 科管科→science_admin(101)
  研发室科长→dept_leader(104) / 研发室科员→researcher(105)
"""
import os
import sys

import dmPython

DM_PASSWORD = os.environ.get("DM_PASSWORD", "")
CONN_KW = dict(user="SYSDBA", password=DM_PASSWORD, server="localhost", port=5236)

# 研究方向字典值 → 中文名（课题名/档案用）
DIR_CN = {
    "BEIDOU": "北斗定位", "AI": "人工智能", "IOT": "物联网", "TONGXIN": "通信",
    "BIGDATA": "大数据", "CLOUD": "云计算", "JIANCE": "智能监测", "XINHAO": "信号控制",
    "GONGDIAN": "牵引供电", "CAILIAO": "工程材料", "ANQUAN": "安全应急", "HUANBAO": "节能环保",
}

# 部门：(dept_name, order_num)
DEPTS = [("办公室", 1), ("劳人科", 2), ("科管科", 3), ("研发一室", 4), ("研发二室", 5)]

# 用户：(dept_name, user_name, nick_name, is_chief)
# 科长角色：职能科室用职能角色；研发室科长用 dept_leader(104)，科员 researcher(105)
DEPT_ROLE = {"办公室": 102, "劳人科": 103, "科管科": 101, "研发一室": 105, "研发二室": 105}
CHIEF_ROLE = {"研发一室": 104, "研发二室": 104}  # 研发室科长额外用 dept_leader

USERS = [
    # 办公室
    ("办公室", "bg_zhang", "张明", True), ("办公室", "bg_li", "李华", False),
    ("办公室", "bg_wang", "王芳", False), ("办公室", "bg_zhao", "赵强", False),
    ("办公室", "bg_chen", "陈静", False),
    # 劳人科
    ("劳人科", "lr_liu", "刘洋", True), ("劳人科", "lr_yang", "杨丽", False),
    ("劳人科", "lr_zhou", "周伟", False), ("劳人科", "lr_wu", "吴敏", False),
    ("劳人科", "lr_zheng", "郑涛", False),
    # 科管科
    ("科管科", "kg_sun", "孙磊", True), ("科管科", "kg_zhu", "朱琳", False),
    ("科管科", "kg_ma", "马超", False), ("科管科", "kg_hu", "胡雪", False),
    ("科管科", "kg_guo", "郭峰", False),
    # 研发一室
    ("研发一室", "yf1_huang", "黄海", True), ("研发一室", "yf1_xu", "徐静", False),
    ("研发一室", "yf1_lin", "林涛", False), ("研发一室", "yf1_he", "何勇", False),
    ("研发一室", "yf1_gao", "高翔", False),
    # 研发二室
    ("研发二室", "yf2_luo", "罗杰", True), ("研发二室", "yf2_liang", "梁敏", False),
    ("研发二室", "yf2_song", "宋斌", False), ("研发二室", "yf2_xie", "谢娜", False),
    ("研发二室", "yf2_han", "韩磊", False),
]

# 研发室每人 3 个研究方向（课题主题）
RD_DIRS = {
    "黄海": ["AI", "CLOUD", "BIGDATA"], "徐静": ["BEIDOU", "TONGXIN", "XINHAO"],
    "林涛": ["IOT", "BIGDATA", "CAILIAO"], "何勇": ["TONGXIN", "GONGDIAN", "JIANCE"],
    "高翔": ["AI", "JIANCE", "ANQUAN"],
    "罗杰": ["BEIDOU", "GONGDIAN", "HUANBAO"], "梁敏": ["AI", "IOT", "CLOUD"],
    "宋斌": ["TONGXIN", "XINHAO", "CAILIAO"], "谢娜": ["BIGDATA", "CLOUD", "HUANBAO"],
    "韩磊": ["GONGDIAN", "ANQUAN", "JIANCE"],
}

PTYPES = ["NATIONAL", "PROVINCIAL", "CR_GROUP", "COMPANY", "INSTITUTE", "LATERAL"]
PCATS = ["A", "B", "C"]
SPECS = ["Y", "J", "GD", "C", "G", "D", "X", "Z", "F"]
MAJOR_BY_DEPT = {"办公室": "行政管理", "劳人科": "人力资源管理", "科管科": "科技管理", "研发一室": "交通信息工程", "研发二室": "电气工程"}


def q1(cur, sql, params=()):
    cur.execute(sql, params)
    row = cur.fetchone()
    return row[0] if row else None


def main():
    conn = dmPython.connect(**CONN_KW)
    cur = conn.cursor()
    cur.execute("SET SCHEMA RUOYI")
    stat = {"dept": 0, "user": 0, "profile": 0, "project": 0, "member": 0, "budget": 0}

    # 复用 admin 密码哈希
    admin_pwd = q1(cur, "SELECT PASSWORD FROM sys_user WHERE user_name='admin'")

    # 1. 部门
    dept_id = {}
    for name, order_num in DEPTS:
        d = q1(cur, "SELECT dept_id FROM sys_dept WHERE dept_name=? AND parent_id=101 AND del_flag='0'", (name,))
        if d:
            dept_id[name] = d
            continue
        cur.execute(
            "INSERT INTO sys_dept (parent_id, ancestors, dept_name, order_num, status, del_flag, create_by, create_time) "
            "VALUES (101, '0,100,101', ?, ?, '0', '0', 'admin', SYSDATE)", (name, order_num))
        dept_id[name] = q1(cur, "SELECT dept_id FROM sys_dept WHERE dept_name=? AND parent_id=101", (name,))
        stat["dept"] += 1

    # 2. 用户 + 角色
    user_id = {}
    for dept, uname, nick, is_chief in USERS:
        uid = q1(cur, "SELECT user_id FROM sys_user WHERE user_name=?", (uname,))
        if not uid:
            cur.execute(
                "INSERT INTO sys_user (dept_id, user_name, nick_name, password, status, del_flag, create_by, create_time) "
                "VALUES (?, ?, ?, ?, '0', '0', 'admin', SYSDATE)",
                (dept_id[dept], uname, nick, admin_pwd))
            uid = q1(cur, "SELECT user_id FROM sys_user WHERE user_name=?", (uname,))
            stat["user"] += 1
        user_id[uname] = uid
        # 角色
        role_id = CHIEF_ROLE.get(dept, DEPT_ROLE[dept]) if is_chief else DEPT_ROLE[dept]
        exists = q1(cur, "SELECT 1 FROM sys_user_role WHERE user_id=? AND role_id=?", (uid, role_id))
        if not exists:
            cur.execute("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", (uid, role_id))

    # 3. 档案（每人一份，按科长/科员差异化）
    for i, (dept, uname, nick, is_chief) in enumerate(USERS):
        uid = user_id[uname]
        exists = q1(cur, "SELECT 1 FROM biz_user_profile WHERE user_id=?", (uid,))
        if exists:
            continue
        edu = "DOCTOR" if is_chief else ("MASTER" if i % 3 else "BACHELOR")
        title = "SENIOR" if is_chief else ("MID" if i % 2 else "SUB_SENIOR")
        degree_txt = "博士" if is_chief else ("硕士" if i % 3 else "学士")
        direction = None
        if dept in ("研发一室", "研发二室"):
            direction = RD_DIRS[nick][0]
        cur.execute(
            "INSERT INTO biz_user_profile (user_id, edu_level, title_level, research_direction, degree, major, "
            "entry_date, office_phone, del_flag, create_by, create_time) "
            "VALUES (?, ?, ?, ?, ?, ?, DATE '2018-07-01', ?, '0', 'admin', SYSDATE)",
            (uid, edu, title, direction, degree_txt, MAJOR_BY_DEPT[dept], "0532-666" + ("%02d" % (i + 1))))
        stat["profile"] += 1

    # 4. 课题（研发室 10 人 × 3，组长=本人）
    seq = 0
    rd_users = [u for u in USERS if u[0] in ("研发一室", "研发二室")]
    for dept, uname, nick, is_chief in USERS:
        if dept not in ("研发一室", "研发二室"):
            continue
        for k, direction in enumerate(RD_DIRS[nick]):
            seq += 1
            pno = "TD%03d" % seq
            if q1(cur, "SELECT 1 FROM project WHERE project_no=?", (pno,)):
                continue
            pname = "%s研究课题%d" % (DIR_CN[direction], k + 1)
            ptype = PTYPES[seq % len(PTYPES)]
            pcat = PCATS[seq % len(PCATS)]
            spec = SPECS[seq % len(SPECS)]
            budget = 500000
            cur.execute(
                "INSERT INTO project (project_no, project_name, project_type, project_category, specialty, "
                "leader_id, budget_total, budget_balance, status, start_date, dept_id, self_hosted, del_flag, create_by, create_time) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', DATE '2026-01-01', ?, '1', '0', 'admin', SYSDATE)",
                (pno, pname, ptype, pcat, spec, user_id[uname], budget, budget, dept_id[dept]))
            pid = q1(cur, "SELECT project_id FROM project WHERE project_no=?", (pno,))
            stat["project"] += 1
            # 组长 HOST 成员
            cur.execute(
                "INSERT INTO project_member (project_id, user_id, role, del_flag, create_by, create_time) "
                "VALUES (?, ?, 'HOST', '0', 'admin', SYSDATE)", (pid, user_id[uname]))
            stat["member"] += 1
            # 同室下一个科员作参与人（循环）
            mates = [u for u in rd_users if u[0] == dept and u[1] != uname]
            if mates:
                mate = mates[k % len(mates)]
                cur.execute(
                    "INSERT INTO project_member (project_id, user_id, role, del_flag, create_by, create_time) "
                    "VALUES (?, ?, 'PARTICIPANT', '0', 'admin', SYSDATE)", (pid, user_id[mate[1]]))
                stat["member"] += 1
            # 预算细分 3 科目
            for cat, amt in [("LABOR", 300000), ("EQUIPMENT", 125000), ("MATERIAL", 75000)]:
                cur.execute(
                    "INSERT INTO budget_split (project_id, category, budget_amount, used_amount, balance, version, del_flag, create_by, create_time) "
                    "VALUES (?, ?, ?, 0, ?, 0, '0', 'admin', SYSDATE)", (pid, cat, amt, amt))
                stat["budget"] += 1

    conn.commit()
    conn.close()
    sys.stdout.buffer.write(("DONE %s\n" % stat).encode("utf-8"))


if __name__ == "__main__":
    main()
