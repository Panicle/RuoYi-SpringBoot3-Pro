SET SCHEMA "RUOYI";
/
UPDATE SYS_CONFIG SET config_value='false' WHERE config_key='sys.account.captchaEnabled';
/
UPDATE SYS_USER SET try_count=0;
/
SELECT config_key, config_value FROM SYS_CONFIG WHERE config_key='sys.account.captchaEnabled';
/
SELECT user_name, try_count FROM SYS_USER;
/
exit
/