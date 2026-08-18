-- ============================================================================
-- 科研管理平台 课题变更4：课题研究领域多选
-- 版本: V1.0.21
-- 日期: 2026-08-17
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 用户需求"课题的领域设置可以多选；首页分专业、分领域分类展示"（产品裁决：
--   领域复用 research_direction 字典 12 项；专业 specialty 保持单值；进度=状态+预算执行率）
-- 说明:
--   1. 新表 project_field：课题↔研究领域 多对多（field_code 取 research_direction 字典值）
--   2. 唯一索引 idx_project_field_pf（project_id, field_code）防重复关联
--   3. 幂等：PL 块预检 USER_TABLES / USER_INDEXES，可重复执行
-- ============================================================================

-- ============================================================================
-- 一、建表 project_field（幂等）
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_TABLES WHERE TABLE_NAME = 'PROJECT_FIELD';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE project_field (
            field_id    BIGINT        IDENTITY,
            project_id  BIGINT        NOT NULL,
            field_code  VARCHAR(20)   NOT NULL,
            del_flag    CHAR(1)       DEFAULT ''0'',
            create_by   VARCHAR(64),
            create_time TIMESTAMP,
            update_by   VARCHAR(64),
            update_time TIMESTAMP,
            remark      VARCHAR(500)
        )';
        EXECUTE IMMEDIATE 'COMMENT ON TABLE project_field IS ''课题研究领域关联（field_code 取 research_direction 字典值，多选）''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_field.project_id IS ''课题ID''';
        EXECUTE IMMEDIATE 'COMMENT ON COLUMN project_field.field_code IS ''研究领域编码（research_direction 字典值）''';
    END IF;
END;

-- ============================================================================
-- 二、唯一索引 idx_project_field_pf（project_id, field_code）幂等
-- ============================================================================
DECLARE
    CNT INT;
BEGIN
    SELECT COUNT(*) INTO CNT FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_PROJECT_FIELD_PF';
    IF CNT = 0 THEN
        EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX idx_project_field_pf ON project_field (project_id, field_code)';
    END IF;
END;

COMMIT;

-- ============================================================================
-- 完
-- ============================================================================
