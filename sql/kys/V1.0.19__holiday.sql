-- ============================================================================
-- 科研管理平台 阶段8收尾：节假日表（工时填报排除周末+法定节假日）
-- 版本: V1.0.19
-- 日期: 2026-08-17
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户需求"工时计算，节假日也需要排除掉"（产品裁决：周末+法定节假日、填报层禁止）
-- 说明:
--   1. 新表 biz_holiday：holiday_date 主键 + holiday_type(HOLIDAY 放假/WORKDAY 调休上班)
--      + holiday_name。周末默认休息不入表；表中 HOLIDAY 行把工作日覆盖为休（法定假日），
--      WORKDAY 行把周末覆盖为班（调休补班）。应用层 isRestDay =
--      (周六/日 或 HOLIDAY 行) 且 非 WORKDAY 行
--   2. 预置 2026 年国务院放假安排：放假 33 天（HOLIDAY）+ 调休上班 6 天（WORKDAY）。
--      每年国务院公布次年安排后需补一版数据 SQL（无管理界面，SQL 维护）
--   3. 幂等：建表 PL 块预检 USER_TABLES；数据 INSERT ... WHERE NOT EXISTS（同 V1.0.6+ 风格）
--   4. 不新增菜单/角色挂载（无独立管理页；工时模块内部消费）
-- ============================================================================

-- ============================================================================
-- 一、建表 biz_holiday（幂等）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TABLES WHERE TABLE_NAME = 'BIZ_HOLIDAY';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE biz_holiday (
            holiday_date  DATE         NOT NULL,
            holiday_type  VARCHAR(10)  NOT NULL,
            holiday_name  VARCHAR(50),
            CONSTRAINT pk_biz_holiday PRIMARY KEY (holiday_date)
        )';
        EXECUTE IMMEDIATE 'COMMENT ON TABLE biz_holiday IS ''节假日配置（HOLIDAY=法定放假日 WORKDAY=调休上班日；周末默认休息不入表）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN biz_holiday.holiday_date IS ''日期（主键）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN biz_holiday.holiday_type IS ''类型：HOLIDAY 放假 / WORKDAY 调休上班''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN biz_holiday.holiday_name IS ''节日名（元旦/春节/清明节/劳动节/端午节/中秋节/国庆节）''';
    END IF;
END;

-- ============================================================================
-- 二、2026 年放假安排数据（国办通知；放假 33 天 + 调休上班 6 天，全部幂等）
-- ============================================================================

-- 元旦：1月1日—3日放假 3 天；1月4日（周日）上班
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-01-01', 'HOLIDAY', '元旦' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-01-01');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-01-02', 'HOLIDAY', '元旦' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-01-02');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-01-03', 'HOLIDAY', '元旦' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-01-03');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-01-04', 'WORKDAY', '元旦调休上班' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-01-04');

-- 春节：2月15日—23日放假 9 天；2月14日（周六）、2月28日（周六）上班
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-14', 'WORKDAY', '春节调休上班' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-14');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-15', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-15');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-16', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-16');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-17', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-17');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-18', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-18');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-19', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-19');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-20', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-20');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-21', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-21');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-22', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-22');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-23', 'HOLIDAY', '春节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-23');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-02-28', 'WORKDAY', '春节调休上班' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-02-28');

-- 清明节：4月4日—6日放假 3 天，无调休
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-04-04', 'HOLIDAY', '清明节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-04-04');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-04-05', 'HOLIDAY', '清明节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-04-05');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-04-06', 'HOLIDAY', '清明节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-04-06');

-- 劳动节：5月1日—5日放假 5 天；5月9日（周六）上班
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-05-01', 'HOLIDAY', '劳动节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-05-01');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-05-02', 'HOLIDAY', '劳动节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-05-02');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-05-03', 'HOLIDAY', '劳动节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-05-03');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-05-04', 'HOLIDAY', '劳动节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-05-04');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-05-05', 'HOLIDAY', '劳动节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-05-05');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-05-09', 'WORKDAY', '劳动节调休上班' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-05-09');

-- 端午节：6月19日—21日放假 3 天，无调休
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-06-19', 'HOLIDAY', '端午节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-06-19');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-06-20', 'HOLIDAY', '端午节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-06-20');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-06-21', 'HOLIDAY', '端午节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-06-21');

-- 中秋节：9月25日—27日放假 3 天；9月20日（周日）为国庆调休上班（并入国庆段）
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-09-20', 'WORKDAY', '国庆节调休上班' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-09-20');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-09-25', 'HOLIDAY', '中秋节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-09-25');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-09-26', 'HOLIDAY', '中秋节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-09-26');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-09-27', 'HOLIDAY', '中秋节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-09-27');

-- 国庆节：10月1日—7日放假 7 天；10月10日（周六）上班
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-01', 'HOLIDAY', '国庆节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-01');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-02', 'HOLIDAY', '国庆节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-02');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-03', 'HOLIDAY', '国庆节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-03');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-04', 'HOLIDAY', '国庆节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-04');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-05', 'HOLIDAY', '国庆节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-05');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-06', 'HOLIDAY', '国庆节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-06');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-07', 'HOLIDAY', '国庆节' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-07');
INSERT INTO biz_holiday (holiday_date, holiday_type, holiday_name) SELECT DATE '2026-10-10', 'WORKDAY', '国庆节调休上班' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM biz_holiday WHERE holiday_date = DATE '2026-10-10');

COMMIT;

-- ============================================================================
-- 完
-- ============================================================================
