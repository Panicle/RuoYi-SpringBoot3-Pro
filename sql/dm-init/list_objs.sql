SELECT owner, object_name, object_type FROM dba_objects WHERE owner='RUOYI' ORDER BY object_type, object_name;
SELECT count(*) AS total_tables FROM dba_tables WHERE owner='RUOYI';
exit