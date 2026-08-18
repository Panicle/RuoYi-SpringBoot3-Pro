# -*- coding: utf-8 -*-
"""执行 V1.0.19__holiday.sql 到 devdm 达梦库（SYSDBA/RUOYI schema）。
PL 块（DECLARE...END;）整段执行；普通语句按分号切；幂等可重跑。"""
import os
import sys
import io

import dmPython

DM_PASSWORD = os.environ.get("DM_PASSWORD", "Ruoyi12345")
CONN_KW = dict(user="SYSDBA", password=DM_PASSWORD, server="localhost", port=5236)

SQL_FILE = os.path.join(os.path.dirname(__file__), "..", "..", "sql", "kys", "V1.0.19__holiday.sql")


def split_statements(text):
    stmts = []
    lines = text.splitlines()
    buf = []
    in_pl = False
    for line in lines:
        stripped = line.strip()
        if not in_pl and (not stripped or stripped.startswith("--")):
            continue
        if not in_pl and stripped.upper().startswith("DECLARE"):
            in_pl = True
            buf = [line]
            continue
        if in_pl:
            buf.append(line)
            if stripped.upper() == "END;":
                stmts.append("\n".join(buf))
                buf = []
                in_pl = False
            continue
        buf.append(line)
        if stripped.endswith(";"):
            stmt = "\n".join(buf).strip()
            stmts.append(stmt.rstrip(";"))
            buf = []
    return stmts


def main():
    with io.open(SQL_FILE, "r", encoding="utf-8") as f:
        text = f.read()
    stmts = split_statements(text)
    conn = dmPython.connect(**CONN_KW)
    conn.cursor().execute("SET SCHEMA RUOYI")
    ok = fail = 0
    try:
        cur = conn.cursor()
        for i, s in enumerate(stmts, 1):
            try:
                cur.execute(s)
                ok += 1
            except Exception as e:
                fail += 1
                sys.stdout.buffer.write(("FAIL #%d: %s\n  %s\n" % (i, str(e)[:200], s[:120])).encode("utf-8"))
        conn.commit()
    finally:
        conn.close()
    # 校验
    conn = dmPython.connect(**CONN_KW)
    try:
        cur = conn.cursor()
        cur.execute("SELECT holiday_type, COUNT(*) FROM RUOYI.biz_holiday GROUP BY holiday_type ORDER BY holiday_type")
        rows = cur.fetchall()
    finally:
        conn.close()
    sys.stdout.buffer.write(("DONE ok=%d fail=%d verify=%s\n" % (ok, fail, rows)).encode("utf-8"))


if __name__ == "__main__":
    main()
