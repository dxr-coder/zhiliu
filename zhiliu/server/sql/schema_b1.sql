-- SmartFlow B1 建表：配置/权限/请假/审批/审计/通知
-- 功能：与 BUSINESS_DESIGN_v2 B1 对齐；前缀 sf_。
-- 数据流向：执行本脚本 -> MySQL mianshi -> Java 业务读写。
-- 数据形状：见各 CREATE TABLE 注释。
-- 用法：mysql -uroot -p mianshi < schema_b1.sql

CREATE DATABASE IF NOT EXISTS mianshi DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mianshi;

-- 部门树
CREATE TABLE IF NOT EXISTS sf_dept (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  parent_id    BIGINT NOT NULL DEFAULT 0,
  name         VARCHAR(64) NOT NULL,
  cost_center  VARCHAR(64) DEFAULT NULL,
  manager_id   BIGINT DEFAULT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'active',
  UNIQUE KEY uk_dept_name_parent (name, parent_id)
) COMMENT '部门主数据';

-- 用户
CREATE TABLE IF NOT EXISTS sf_user (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  emp_no         VARCHAR(32) NOT NULL,
  username       VARCHAR(64) NOT NULL,
  password_hash  VARCHAR(200) NOT NULL,
  real_name      VARCHAR(64) DEFAULT NULL,
  phone          VARCHAR(20) DEFAULT NULL,
  dept_id        BIGINT NOT NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_emp_no (emp_no),
  KEY idx_dept (dept_id)
) COMMENT '用户';

-- 角色 / 权限 / 关联
CREATE TABLE IF NOT EXISTS sf_role (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code   VARCHAR(32) NOT NULL,
  name        VARCHAR(64) NOT NULL,
  description VARCHAR(255) DEFAULT NULL,
  UNIQUE KEY uk_role_code (role_code)
) COMMENT '角色';

CREATE TABLE IF NOT EXISTS sf_permission (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  perm_code VARCHAR(64) NOT NULL,
  name      VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_perm_code (perm_code)
) COMMENT '权限点';

CREATE TABLE IF NOT EXISTS sf_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
) COMMENT '用户-角色多对多';

CREATE TABLE IF NOT EXISTS sf_role_permission (
  role_id BIGINT NOT NULL,
  perm_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, perm_id)
) COMMENT '角色-权限';

-- 假种配置
CREATE TABLE IF NOT EXISTS sf_leave_type (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  code         VARCHAR(32) NOT NULL,
  name         VARCHAR(64) NOT NULL,
  need_balance TINYINT(1) NOT NULL DEFAULT 0,
  cert_days    INT NOT NULL DEFAULT 0,
  chain_policy VARCHAR(64) NOT NULL DEFAULT 'manager',
  status       VARCHAR(16) NOT NULL DEFAULT 'active',
  UNIQUE KEY uk_leave_code (code)
) COMMENT '假种可配';

-- 审批模板
CREATE TABLE IF NOT EXISTS sf_approval_template (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_type       VARCHAR(32) NOT NULL,
  version        INT NOT NULL DEFAULT 1,
  steps_json     TEXT NOT NULL,
  reject_policy  VARCHAR(32) NOT NULL DEFAULT 'to_start',
  status         VARCHAR(16) NOT NULL DEFAULT 'active',
  UNIQUE KEY uk_biz_ver (biz_type, version)
) COMMENT '审批流模板';

-- 审批实例 / 任务
CREATE TABLE IF NOT EXISTS sf_approval_instance (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_type       VARCHAR(32) NOT NULL,
  biz_id         BIGINT NOT NULL,
  template_id    BIGINT NOT NULL,
  template_ver   INT NOT NULL DEFAULT 1,
  current_step   INT NOT NULL DEFAULT 1,
  status         VARCHAR(16) NOT NULL DEFAULT 'running',
  starter_id     BIGINT NOT NULL,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at    DATETIME DEFAULT NULL,
  KEY idx_biz (biz_type, biz_id),
  KEY idx_status (status)
) COMMENT '审批实例';

CREATE TABLE IF NOT EXISTS sf_approval_task (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  instance_id  BIGINT NOT NULL,
  step         INT NOT NULL,
  approver_id  BIGINT NOT NULL,
  action       VARCHAR(16) NOT NULL DEFAULT 'pending',
  comment      VARCHAR(500) DEFAULT NULL,
  acted_at     DATETIME DEFAULT NULL,
  KEY idx_inst (instance_id),
  KEY idx_approver_action (approver_id, action)
) COMMENT '审批任务';

-- 请假单
CREATE TABLE IF NOT EXISTS sf_leave (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  leave_no             VARCHAR(32) NOT NULL,
  user_id              BIGINT NOT NULL,
  dept_id              BIGINT NOT NULL,
  type                 VARCHAR(32) NOT NULL,
  start_date           DATE NOT NULL,
  end_date             DATE NOT NULL,
  days                 DECIMAL(6,2) NOT NULL,
  reason               VARCHAR(500) DEFAULT NULL,
  balance_before       DECIMAL(8,2) DEFAULT NULL,
  balance_hold         DECIMAL(8,2) DEFAULT NULL,
  balance_after        DECIMAL(8,2) DEFAULT NULL,
  status               VARCHAR(32) NOT NULL DEFAULT 'draft',
  chain_snapshot       TEXT,
  approval_instance_id BIGINT DEFAULT NULL,
  ai_draft             TINYINT(1) NOT NULL DEFAULT 0,
  created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_leave_no (leave_no),
  KEY idx_user_status (user_id, status),
  KEY idx_dept_status (dept_id, status)
) COMMENT '请假单';

-- 余额流水（销假回滚）
CREATE TABLE IF NOT EXISTS sf_balance_ledger (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  leave_type  VARCHAR(32) NOT NULL,
  change_amt  DECIMAL(8,2) NOT NULL,
  biz_type    VARCHAR(32) NOT NULL,
  biz_id      BIGINT NOT NULL,
  reason      VARCHAR(128) DEFAULT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_type (user_id, leave_type)
) COMMENT '假期余额流水';

-- 审计
CREATE TABLE IF NOT EXISTS sf_audit_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  operator_id  BIGINT DEFAULT NULL,
  action       VARCHAR(64) NOT NULL,
  target_type  VARCHAR(64) DEFAULT NULL,
  target_id    VARCHAR(64) DEFAULT NULL,
  before_json  JSON DEFAULT NULL,
  after_json   JSON DEFAULT NULL,
  trace_id     VARCHAR(64) DEFAULT NULL,
  ip           VARCHAR(64) DEFAULT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_operator (operator_id),
  KEY idx_action_time (action, created_at)
) COMMENT '审计日志';

-- 通知
CREATE TABLE IF NOT EXISTS sf_notice (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  type       VARCHAR(32) NOT NULL,
  title      VARCHAR(128) NOT NULL,
  body       VARCHAR(500) DEFAULT NULL,
  biz_type   VARCHAR(32) DEFAULT NULL,
  biz_id     BIGINT DEFAULT NULL,
  read_flag  TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_read (user_id, read_flag)
) COMMENT '站内通知';

-- ========== 种子（B1 演示） ==========
INSERT IGNORE INTO sf_dept (id, parent_id, name, cost_center, manager_id) VALUES
  (100, 0, '总公司', 'CC-HQ', NULL),
  (200, 100, '研发中心', 'CC-RD', 3001),
  (301, 200, '后端组', 'CC-RD-BE', 3001),
  (302, 200, '前端组', 'CC-RD-FE', 3002),
  (400, 100, '职能中心', 'CC-GA', 4001),
  (401, 400, '人事组', 'CC-HR', 4001),
  (402, 400, '财务组', 'CC-FIN', 4002);

INSERT IGNORE INTO sf_role (id, role_code, name, description) VALUES
  (1, 'employee', '普通员工', '发起自己的单'),
  (2, 'manager', '部门主管', '审批本部门'),
  (3, 'agent', '服务台坐席', '处理工单'),
  (4, 'hr', '人事', '人事域终审'),
  (5, 'finance', '财务', '财务域终审'),
  (6, 'admin', '系统管理员', '全局配置');

INSERT IGNORE INTO sf_permission (id, perm_code, name) VALUES
  (1, 'leave:submit', '提交请假'),
  (2, 'leave:approve', '审批请假'),
  (3, 'leave:cancel', '撤回请假'),
  (4, 'user:manage', '用户管理'),
  (5, 'config:manage', '配置中心'),
  (6, 'audit:view', '查看审计'),
  (7, 'notice:view', '查看通知'),
  (8, 'approval:todo', '审批待办');

INSERT IGNORE INTO sf_role_permission (role_id, perm_id) VALUES
  (1,1),(1,3),(1,7),
  (2,2),(2,7),(2,8),
  (3,7),(3,8),
  (4,2),(4,7),(4,8),
  (5,7),(5,8),
  (6,4),(6,5),(6,6),(6,7),(6,8);

-- 密码统一 123456 的 BCrypt（生产必须改）；先用可识别 hash 占位，首次启动用代码重置或 UPDATE
-- 这里插入演示用户，password_hash 由启动时 PasswordSeed 覆盖为 BCrypt(123456)
INSERT IGNORE INTO sf_user (id, emp_no, username, password_hash, real_name, dept_id, status) VALUES
  (10086, 'E10086', 'zhangsan', '{noop}123456', '张三', 301, 'active'),
  (3001,  'E3001',  'liming',   '{noop}123456', '李明', 301, 'active'),
  (4001,  'E4001',  'wangwu',   '{noop}123456', '王五', 401, 'active'),
  (4002,  'E4002',  'zhaoliu',  '{noop}123456', '赵六', 402, 'active'),
  (1,     'E1',     'admin',    '{noop}123456', '管理员', 100, 'active');

INSERT IGNORE INTO sf_user_role (user_id, role_id) VALUES
  (10086, 1),
  (3001, 1), (3001, 2),
  (4001, 1), (4001, 4),
  (4002, 1), (4002, 5),
  (1, 1), (1, 6);

INSERT IGNORE INTO sf_leave_type (code, name, need_balance, cert_days, chain_policy) VALUES
  ('annual', '年假', 1, 0, 'manager_then_hr_if_gt_3'),
  ('sick', '病假', 0, 2, 'manager_then_hr'),
  ('personal', '事假', 0, 0, 'manager'),
  ('comp', '调休', 1, 0, 'manager');

INSERT IGNORE INTO sf_approval_template (biz_type, version, steps_json, reject_policy) VALUES
  ('leave', 1, '[{"step":1,"role":"manager","mode":"serial"},{"step":2,"role":"hr","mode":"serial","skipWhen":"days<=3"}]', 'to_start');

INSERT IGNORE INTO sf_notice (user_id, type, title, body, biz_type, biz_id) VALUES
  (10086, 'system', '欢迎使用 SmartFlow', 'B1 已就绪：请假可提交并走审批', NULL, NULL);
