SELECT username FROM dba_users;
SELECT DISTINCT object_name, object_type FROM dba_objects WHERE object_type IN ('SCH','SCHEMA') OR object_name='RUOYI' AND object_type LIKE '%SCH%';
SELECT schema_name FROM information_schema.schemata;
exit