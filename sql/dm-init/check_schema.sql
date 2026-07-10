SELECT username, account_status FROM dba_users;
SELECT schema_name FROM dba_schemas ORDER BY schema_name;
SELECT object_name, object_type FROM dba_objects WHERE owner='RUOYI' AND ROWNUM<=10;
exit