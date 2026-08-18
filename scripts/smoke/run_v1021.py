# -*- coding: utf-8 -*-
"""执行 V1.0.21__project_field.sql 到 devdm 达梦库（SYSDBA/RUOYI schema）。幂等可重跑。"""
import os
import sys
import io

import dmPython

DM_PASSWORD = os.environ.get("DM_PASSWORD", "Ruoyi12345")
CONN_KW = dict(user="SYSDBA", password=DM_PASSWORD, server="localhost", port=5236)

SQL_FILE = os.path.join(os.path.dirname(__file__), "..", "..", "sql", "kys", "V1.0.21__project_field.sql")


def split_statements(text):
    stmts = []
    buf = []
    in_pl = False
    for line in text.splitlines():
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
            stmts.append("\n".join(buf).strip().rstrip(";"))
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
    conn = dmPython.connect(**CONN_KW)
    try:
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) FROM SYS.USER_TABLES WHERE TABLE_NAME='PROJECT_FIELD'")
        tbl = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM SYS.USER_INDEXES WHERE INDEX_NAME='IDX_PROJECT_FIELD_PF'")
        idx = cur.fetchone()[0]
    finally:
        conn.close()
    sys.stdout.buffer.write(("DONE ok=%d fail=%d  project_field表=%d idx=%d\n" % (ok, fail, tbl, idx)).encode("utf-8"))


if __name__ == "__main__":
    main()
