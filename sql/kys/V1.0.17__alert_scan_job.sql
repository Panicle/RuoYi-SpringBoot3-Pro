-- ============================================================================
-- 科研管理平台 阶段9：预警引擎-预警扫描 Quartz 定时任务
-- 版本: V1.0.17
-- 日期: 2026-08-15
-- 数据库: 达梦DM8（Oracle方言兼容）
-- 变更依据: 任务卡_阶段9 预警引擎与对话精灵 Task 3
-- 说明:
--   1. sys_job 插一条"预警扫描"定时任务：invoke_target='alertScanTask.scanAll()'，
--      cron='0 0 8 * * ?'（每天 08:00），misfire_policy='0'（默认，ScheduleConstants.MISFIRE_DEFAULT），
--      concurrent='1'（禁止并发）、status='0'（正常启用）
--   2. job_id 取当前 MAX(job_id)+1（动态子查询，照 V1.0.11 sys_config 惯例，避免硬编码冲突）
--   3. 幂等：INSERT...SELECT...WHERE NOT EXISTS (job_name='预警扫描')
-- ============================================================================

INSERT INTO sys_job (job_id, job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT (SELECT MAX(job_id) + 1 FROM sys_job), '预警扫描', 'DEFAULT', 'alertScanTask.scanAll()', '0 0 8 * * ?', '0', '1', '0', 'admin', SYSDATE, '预警引擎-每天08:00扫描合同节点/经费超限/资料逾期'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE job_name = '预警扫描');
