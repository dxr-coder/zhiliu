-- SmartFlow 一键初始化（按序执行全部 schema + 种子）
-- 用法: mysql -uroot -p mianshi < sql/init.sql


-- ===== schema_b1.sql =====

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


-- ===== schema_b2.sql =====

-- SmartFlow B2：报销 + 工单/SLA + 预算占用
-- 功能：B1 之后的交易型业务；前缀 sf_。
-- 数据流向：schema_b2.sql -> MySQL -> ExpenseService/TicketService。
-- 数据形状：见各表注释；幂等键防重复提交。

USE mianshi;

-- ── 报销 ──
CREATE TABLE IF NOT EXISTS sf_expense (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  expense_no        VARCHAR(32) NOT NULL,
  user_id           BIGINT NOT NULL,
  dept_id           BIGINT NOT NULL,
  type              VARCHAR(32) NOT NULL,          -- travel|meal|office|train|other
  amount_total      DECIMAL(12,2) NOT NULL,
  currency          VARCHAR(8) NOT NULL DEFAULT 'CNY',
  cost_center       VARCHAR(64) DEFAULT NULL,
  budget_period     VARCHAR(7) DEFAULT NULL,       -- 2026-11
  budget_hold       DECIMAL(12,2) DEFAULT NULL,
  status            VARCHAR(32) NOT NULL DEFAULT 'draft',
  -- draft/submitted/pending_manager/pending_finance/approved/paid/rejected/cancelled/voided
  policy_flags      JSON DEFAULT NULL,
  idempotency_key   VARCHAR(128) DEFAULT NULL,
  approval_instance_id BIGINT DEFAULT NULL,
  ai_generated      TINYINT(1) NOT NULL DEFAULT 0,
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_expense_no (expense_no),
  UNIQUE KEY uk_idem (idempotency_key),
  KEY idx_user_status (user_id, status),
  KEY idx_dept_status (dept_id, status)
) COMMENT '报销单';

CREATE TABLE IF NOT EXISTS sf_expense_item (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  expense_id   BIGINT NOT NULL,
  name         VARCHAR(128) NOT NULL,
  amount       DECIMAL(12,2) NOT NULL,
  invoice_no   VARCHAR(64) DEFAULT NULL,
  expense_date DATE DEFAULT NULL,
  KEY idx_expense (expense_id),
  UNIQUE KEY uk_invoice (invoice_no)
) COMMENT '报销明细+发票号唯一';

-- ── 工单 + SLA ──
CREATE TABLE IF NOT EXISTS sf_ticket (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_no      VARCHAR(32) NOT NULL,
  title          VARCHAR(200) NOT NULL,
  category       VARCHAR(64) NOT NULL,
  sub_category   VARCHAR(64) DEFAULT NULL,
  priority       VARCHAR(8) NOT NULL DEFAULT 'p2', -- p0|p1|p2|p3
  status         VARCHAR(32) NOT NULL DEFAULT 'triage',
  -- draft/triage/assigned/in_progress/waiting_user/waiting_third/escalated/
  -- resolved/closed/rejected/duplicate/reopen
  reporter_id    BIGINT NOT NULL,
  assignee_id    BIGINT DEFAULT NULL,
  group_id       BIGINT DEFAULT NULL,
  dept_id        BIGINT NOT NULL,
  source         VARCHAR(32) NOT NULL DEFAULT 'portal',
  ai_confidence  DECIMAL(4,3) DEFAULT NULL,
  first_response_at DATETIME DEFAULT NULL,
  sla_deadline   DATETIME DEFAULT NULL,
  resolution     VARCHAR(1000) DEFAULT NULL,
  merge_target_id BIGINT DEFAULT NULL,
  reopen_count   INT NOT NULL DEFAULT 0,
  csat_score     INT DEFAULT NULL,
  tags           VARCHAR(255) DEFAULT NULL,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ticket_no (ticket_no),
  KEY idx_status_pri (status, priority),
  KEY idx_assignee (assignee_id),
  KEY idx_sla (sla_deadline)
) COMMENT '工单';

CREATE TABLE IF NOT EXISTS sf_ticket_event (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id   BIGINT NOT NULL,
  from_status VARCHAR(32) DEFAULT NULL,
  to_status   VARCHAR(32) NOT NULL,
  actor_id    BIGINT DEFAULT NULL,
  reason      VARCHAR(500) DEFAULT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ticket (ticket_id)
) COMMENT '工单事件流';

CREATE TABLE IF NOT EXISTS sf_sla_policy (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  priority         VARCHAR(8) NOT NULL,
  first_resp_min   INT NOT NULL,
  resolve_min      INT NOT NULL,
  escalate_role    VARCHAR(32) NOT NULL DEFAULT 'manager',
  status           VARCHAR(16) NOT NULL DEFAULT 'active',
  UNIQUE KEY uk_sla_pri (priority)
) COMMENT 'SLA 策略可配';

CREATE TABLE IF NOT EXISTS sf_sla_event (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id   BIGINT NOT NULL,
  type        VARCHAR(32) NOT NULL,  -- first_response|resolve|remind|breach
  at          DATETIME NOT NULL,
  notifier_id BIGINT DEFAULT NULL,
  KEY idx_ticket_type (ticket_id, type)
) COMMENT 'SLA 事件';

-- ── 部门预算 ──
CREATE TABLE IF NOT EXISTS sf_dept_budget (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  dept_id     BIGINT NOT NULL,
  period      VARCHAR(7) NOT NULL,   -- 2026-11
  amount      DECIMAL(12,2) NOT NULL,
  used        DECIMAL(12,2) NOT NULL DEFAULT 0,
  UNIQUE KEY uk_dept_period (dept_id, period)
) COMMENT '部门月预算';

-- ── 种子 ──
INSERT IGNORE INTO sf_sla_policy (priority, first_resp_min, resolve_min, escalate_role) VALUES
  ('p0', 15, 120, 'manager'),
  ('p1', 60, 480, 'manager'),
  ('p2', 240, 1440, 'manager'),
  ('p3', 1440, 4320, 'agent');

INSERT IGNORE INTO sf_dept_budget (dept_id, period, amount, used) VALUES
  (301, '2026-11', 50000.00, 0.00),
  (402, '2026-11', 20000.00, 0.00);

INSERT IGNORE INTO sf_permission (id, perm_code, name) VALUES
  (20, 'expense:submit', '提交报销'),
  (21, 'expense:approve', '审批报销'),
  (22, 'expense:pay', '打款标记'),
  (23, 'ticket:handle', '处理工单'),
  (24, 'ticket:create', '创建工单');

INSERT IGNORE INTO sf_role_permission (role_id, perm_id) VALUES
  (1,20),(1,24),(1,7),
  (2,21),(2,23),
  (3,23),(3,24),
  (5,21),(5,22),
  (6,5),(6,21),(6,22);


-- ===== schema_b4.sql =====

-- SmartFlow B4：会议室（冲突 + 签到 + 爽约）
USE mianshi;

CREATE TABLE IF NOT EXISTS sf_meeting_room (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(32) NOT NULL,
  name        VARCHAR(64) NOT NULL,
  capacity    INT NOT NULL DEFAULT 8,
  equip       VARCHAR(255) DEFAULT NULL,
  location    VARCHAR(128) DEFAULT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'active',
  UNIQUE KEY uk_room_code (code)
) COMMENT '会议室主数据';

CREATE TABLE IF NOT EXISTS sf_room_booking (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  booking_no         VARCHAR(32) NOT NULL,
  room_id            BIGINT NOT NULL,
  start_at           DATETIME NOT NULL,
  end_at             DATETIME NOT NULL,
  organizer_id       BIGINT NOT NULL,
  attendees          VARCHAR(512) DEFAULT NULL,
  title              VARCHAR(200) DEFAULT NULL,
  status             VARCHAR(16) NOT NULL DEFAULT 'booked',
  -- booked/checked_in/done/cancelled/no_show/late_cancel
  check_in_deadline  DATETIME NOT NULL,
  late_cancel_flag   TINYINT(1) NOT NULL DEFAULT 0,
  credit_delta       INT NOT NULL DEFAULT 0,
  created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_booking_no (booking_no),
  KEY idx_room_time (room_id, start_at, end_at),
  KEY idx_status (status)
) COMMENT '会议室预订';

INSERT IGNORE INTO sf_meeting_room (id, code, name, capacity, equip, location) VALUES
  (1, 'R-301', '301会议室', 12, '投影,白板', '3F-A'),
  (2, 'R-302', '302会议室', 8, '电视', '3F-B'),
  (3, 'R-501', '大会议室', 30, '投影,视频会议', '5F');

INSERT IGNORE INTO sf_permission (id, perm_code, name) VALUES
  (30, 'room:book', '预订会议室'),
  (31, 'room:manage', '会议室管理');

INSERT IGNORE INTO sf_role_permission (role_id, perm_id) VALUES
  (1,30),(3,30),(6,31);


-- ===== schema_b6.sql =====

-- SmartFlow B6：IT 资产生命周期 + 离职 checklist
USE mianshi;

CREATE TABLE IF NOT EXISTS sf_asset (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  sn             VARCHAR(64) NOT NULL,
  name           VARCHAR(128) NOT NULL,
  category_id    BIGINT DEFAULT NULL,
  status         VARCHAR(32) NOT NULL DEFAULT 'stock',
  -- stock/assigned/repair/lost/lost_pending_compensate/dispose/disposed
  holder_id      BIGINT DEFAULT NULL,
  dept_id        BIGINT DEFAULT NULL,
  buy_date       DATE DEFAULT NULL,
  warranty_end   DATE DEFAULT NULL,
  depreciate_years INT DEFAULT 3,
  location       VARCHAR(128) DEFAULT NULL,
  last_check_at  DATETIME DEFAULT NULL,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_asset_sn (sn),
  KEY idx_status (status),
  KEY idx_holder (holder_id)
) COMMENT 'IT 资产台账';

CREATE TABLE IF NOT EXISTS sf_asset_flow (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  asset_id     BIGINT NOT NULL,
  action       VARCHAR(32) NOT NULL,
  from_holder  BIGINT DEFAULT NULL,
  to_holder    BIGINT DEFAULT NULL,
  actor_id     BIGINT DEFAULT NULL,
  reason       VARCHAR(500) DEFAULT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_asset (asset_id)
) COMMENT '资产流转流水';

CREATE TABLE IF NOT EXISTS sf_offboard_checklist (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT NOT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'open',
  -- open/done
  assets_returned TINYINT(1) NOT NULL DEFAULT 0,
  accounts_disabled TINYINT(1) NOT NULL DEFAULT 0,
  notes        VARCHAR(500) DEFAULT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user (user_id)
) COMMENT '离职 checklist';

INSERT IGNORE INTO sf_asset (id, sn, name, status, dept_id, buy_date, warranty_end) VALUES
  (1, 'SN-LAP-001', 'ThinkPad X1', 'stock', 301, '2025-01-10', '2028-01-10'),
  (2, 'SN-LAP-002', 'MacBook Pro', 'assigned', 301, '2024-06-01', '2027-06-01'),
  (3, 'SN-MON-001', 'Dell 27寸显示器', 'stock', 301, '2025-03-01', '2028-03-01');

-- 给 10086 挂一台（模拟在用）
UPDATE sf_asset SET holder_id=10086, status='assigned' WHERE id=2;

INSERT IGNORE INTO sf_permission (id, perm_code, name) VALUES
  (40, 'asset:manage', '资产管理'),
  (41, 'asset:apply', '资产领用');

INSERT IGNORE INTO sf_role_permission (role_id, perm_id) VALUES
  (1,41),(3,40),(6,40);


-- ===== schema_b7.sql =====

-- SmartFlow B7：审批模板补种 + 采购域 + 离职 checklist 扩展 + AI 开关
-- 功能：T1/T2 补强所需的表结构与种子。
-- 数据流向：schema -> 业务 Service 读配置/写单据。
-- 数据形状：见各 CREATE TABLE 注释。
USE mianshi;

-- ── 1. 报销审批模板（配置驱动，替换代码硬编码链）──
INSERT IGNORE INTO sf_approval_template (biz_type, version, steps_json, reject_policy, status) VALUES
  ('expense', 1,
   '[{"step":1,"role":"manager","mode":"serial"},{"step":2,"role":"finance","mode":"serial"}]',
   'to_start', 'active'),
  ('leave', 1,
   '[{"step":1,"role":"manager","mode":"serial"},{"step":2,"role":"hr","mode":"serial"}]',
   'to_start', 'active');
UPDATE sf_approval_template SET status='active' WHERE biz_type IN ('leave','expense') AND status IS NULL;

-- ── 2. 采购需求（demand → quote≥2 → approve → ordered → received → accepted | rejected）──
CREATE TABLE IF NOT EXISTS sf_purchase_request (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  demand_no             VARCHAR(32) NOT NULL,
  title                 VARCHAR(200) NOT NULL,
  category              VARCHAR(32) NOT NULL DEFAULT 'office',
  qty                   INT NOT NULL DEFAULT 1,
  budget                DECIMAL(12,2) DEFAULT NULL,
  reason                VARCHAR(500) DEFAULT NULL,
  requester_id          BIGINT NOT NULL,
  dept_id               BIGINT DEFAULT NULL,
  -- demand/quoted/approved/ordered/received/accepted/rejected/cancelled
  status                VARCHAR(24) NOT NULL DEFAULT 'demand',
  quotes_json           TEXT,
  chosen_quote_index    INT DEFAULT NULL,
  approval_instance_id  BIGINT DEFAULT NULL,
  linked_expense_id     BIGINT DEFAULT NULL,
  created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_demand_no (demand_no),
  KEY idx_status (status),
  KEY idx_requester (requester_id)
) COMMENT '采购需求单';

-- ── 3. 离职 checklist 扩展：权限/门禁/借款 ──
-- 旧表只有 assets_returned / accounts_disabled，补三列门槛
-- 注意：MySQL 对已存在列会报 Duplicate column，重跑时忽略该句错误即可
ALTER TABLE sf_offboard_checklist
  ADD COLUMN permissions_revoked TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN badge_returned      TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN loan_cleared        TINYINT(1) NOT NULL DEFAULT 0;

-- ── 4. 系统开关（AI 全局降级等，键值配置）──
CREATE TABLE IF NOT EXISTS sf_sys_config (
  cfg_key    VARCHAR(64) PRIMARY KEY,
  cfg_value  VARCHAR(500) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '系统开关/键值配置';

INSERT IGNORE INTO sf_sys_config (cfg_key, cfg_value) VALUES
  ('ai.enabled', 'true'),
  ('ai.triage_min_conf', '0.6');

-- ── 5. 采购权限 ──
INSERT IGNORE INTO sf_permission (id, perm_code, name) VALUES
  (50, 'purchase:manage', '采购管理'),
  (51, 'purchase:apply', '采购提报');
INSERT IGNORE INTO sf_role_permission (role_id, perm_id) VALUES
  (1, 51), (3, 50), (5, 50), (6, 50), (1, 50);


-- ===== schema_b8.sql =====

-- SmartFlow B8：P0 补齐（报销标准配置 + 一键依赖说明）
-- 功能：expense_policy 主数据；与 init.sql 一并执行。
-- 数据流向：配置中心 CRUD -> ExpenseService 提交时校验。
-- 数据形状：{type, limitPerDay, needInvoice, status}

CREATE TABLE IF NOT EXISTS sf_expense_policy (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  type            VARCHAR(32) NOT NULL,
  limit_per_day   DECIMAL(12,2) DEFAULT NULL,
  need_invoice    TINYINT(1) NOT NULL DEFAULT 1,
  status          VARCHAR(16) NOT NULL DEFAULT 'active',
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_exp_type (type)
) COMMENT '报销标准（按类型限额/是否必须发票）';

INSERT IGNORE INTO sf_expense_policy (type, limit_per_day, need_invoice, status) VALUES
  ('travel', 800.00, 1, 'active'),
  ('meal',   200.00, 1, 'active'),
  ('office', 500.00, 1, 'active'),
  ('train',  600.00, 1, 'active'),
  ('other',  300.00, 0, 'active');


-- ===== seed_demo_data.sql =====

-- SmartFlow demo data: system looks like running for ~3 weeks
USE mianshi;

DELETE FROM sf_approval_task WHERE instance_id IN (SELECT id FROM sf_approval_instance);
DELETE FROM sf_approval_instance;
DELETE FROM sf_balance_ledger;
DELETE FROM sf_leave;
DELETE FROM sf_expense_item;
DELETE FROM sf_expense;
DELETE FROM sf_ticket_event;
DELETE FROM sf_sla_event;
DELETE FROM sf_ticket;
DELETE FROM sf_room_booking;
DELETE FROM sf_notice;
DELETE FROM sf_audit_log;

INSERT INTO sf_leave
  (leave_no, user_id, dept_id, type, start_date, end_date, days, reason,
   balance_before, balance_hold, balance_after, status, chain_snapshot, approval_instance_id, ai_draft, created_at)
VALUES
  ('L-2026-1101-01', 10086, 301, 'annual', '2026-11-03', '2026-11-05', 3, '国庆调休补休', 12, 3, 9, 'done',
   '[{step1}]', 101, 0, '2026-11-01 09:12:00'),
  ('L-2026-1105-02', 10086, 301, 'personal', '2026-11-07', '2026-11-07', 1, '家中有事', NULL, NULL, NULL, 'cancelled',
   '[{step1}]', NULL, 1, '2026-11-05 14:30:00'),
  ('L-2026-1108-03', 10086, 301, 'sick', '2026-11-09', '2026-11-10', 2, '感冒发烧', NULL, NULL, NULL, 'rejected',
   '[{step1}]', 102, 0, '2026-11-08 08:05:00'),
  ('L-2026-1112-04', 10086, 301, 'annual', '2026-11-18', '2026-11-20', 3, '回家探亲', 9, 3, 6, 'pending_manager',
   '[{step1}]', 103, 1, '2026-11-12 10:20:00'),
  ('L-2026-1110-05', 10086, 301, 'comp', '2026-11-14', '2026-11-14', 1, '上周加班调休', 9, 1, 8, 'approved',
   '[{step1}]', 104, 0, '2026-11-10 16:40:00'),
  ('L-2026-1015-06', 10086, 301, 'annual', '2026-10-20', '2026-10-24', 5, '年假出游', 15, 5, 10, 'done',
   '[{step1}]', 105, 0, '2026-10-15 11:00:00'),
  ('L-2026-1113-07', 10086, 301, 'personal', '2026-11-25', '2026-11-26', 2, '办理证件', NULL, NULL, NULL, 'pending_hr',
   '[{step1}]', 106, 0, '2026-11-13 09:00:00'),
  ('L-2026-1028-08', 10086, 301, 'sick', '2026-10-29', '2026-10-29', 1, '肠胃不适', NULL, NULL, NULL, 'effective',
   '[{step1}]', 107, 0, '2026-10-28 07:50:00');

INSERT INTO sf_approval_instance (id, biz_type, biz_id, template_id, template_ver, current_step, status, starter_id, created_at, finished_at) VALUES
  (101,'leave',1,1,1,1,'approved',10086,'2026-11-01 09:12:00','2026-11-01 15:00:00'),
  (102,'leave',3,1,1,1,'rejected',10086,'2026-11-08 08:05:00','2026-11-08 10:20:00'),
  (103,'leave',4,1,1,1,'running',10086,'2026-11-12 10:20:00',NULL),
  (104,'leave',5,1,1,1,'approved',10086,'2026-11-10 16:40:00','2026-11-11 09:00:00'),
  (105,'leave',6,1,1,1,'approved',10086,'2026-10-15 11:00:00','2026-10-16 10:00:00'),
  (106,'leave',7,1,1,2,'running',10086,'2026-11-13 09:00:00',NULL),
  (107,'leave',8,1,1,1,'approved',10086,'2026-10-28 07:50:00','2026-10-28 09:00:00');

INSERT INTO sf_approval_task (instance_id, step, approver_id, action, comment, acted_at) VALUES
  (101,1,3001,'agree','OK','2026-11-01 14:00:00'),
  (101,2,4001,'agree','OK','2026-11-01 15:00:00'),
  (102,1,3001,'reject','证明不足','2026-11-08 10:20:00'),
  (103,1,3001,'pending',NULL,NULL),
  (104,1,3001,'agree','OK','2026-11-11 09:00:00'),
  (105,1,3001,'agree','OK','2026-10-15 18:00:00'),
  (105,2,4001,'agree','OK','2026-10-16 10:00:00'),
  (106,1,3001,'agree','OK','2026-11-13 11:00:00'),
  (106,2,4001,'pending',NULL,NULL),
  (107,1,3001,'agree','OK','2026-10-28 09:00:00');

INSERT INTO sf_expense
  (expense_no, user_id, dept_id, type, amount_total, currency, cost_center, budget_period,
   budget_hold, status, policy_flags, idempotency_key, approval_instance_id, ai_generated, created_at)
VALUES
  ('E-2026-1102-01', 10086, 301, 'travel', 2486.50, 'CNY', 'CC-RD-01', '2026-11', 2486.50, 'paid', '[]', 'seed-e1', 201, 0, '2026-11-02 18:20:00'),
  ('E-2026-1106-02', 10086, 301, 'meal', 386.00, 'CNY', 'CC-RD-01', '2026-11', 386.00, 'paid', '[]', 'seed-e2', 202, 0, '2026-11-06 12:40:00'),
  ('E-2026-1111-03', 10086, 301, 'office', 1599.00, 'CNY', 'CC-RD-01', '2026-11', 1599.00, 'approved', '["over_standard"]', 'seed-e3', 203, 0, '2026-11-11 15:10:00'),
  ('E-2026-1113-04', 10086, 301, 'train', 553.00, 'CNY', 'CC-RD-01', '2026-11', 553.00, 'pending_finance', '[]', 'seed-e4', 204, 1, '2026-11-13 09:30:00'),
  ('E-2026-1114-05', 10086, 301, 'meal', 128.00, 'CNY', 'CC-RD-01', '2026-11', 128.00, 'pending_manager', '[]', 'seed-e5', 205, 0, '2026-11-14 19:05:00'),
  ('E-2026-1020-06', 10086, 301, 'travel', 8600.00, 'CNY', 'CC-RD-01', '2026-10', NULL, 'voided', '["over_standard"]', 'seed-e6', 206, 0, '2026-10-20 10:00:00'),
  ('E-2026-1030-07', 10086, 301, 'other', 260.00, 'CNY', 'CC-RD-01', '2026-10', 260.00, 'rejected', '[]', 'seed-e7', 207, 0, '2026-10-30 14:22:00');

INSERT INTO sf_expense_item (expense_id, name, amount, invoice_no, expense_date) VALUES
  (1,'高铁往返',896.50,'INV-1001','2026-11-01'),
  (1,'酒店住宿',1590.00,'INV-1002','2026-11-02'),
  (2,'客户餐叙',386.00,'INV-1003','2026-11-06'),
  (3,'人体工学椅',1599.00,'INV-1004','2026-11-10'),
  (4,'高铁票',553.00,'INV-1005','2026-11-12'),
  (5,'加班餐',128.00,'INV-1006','2026-11-14'),
  (6,'机票改签费',8600.00,'INV-1007','2026-10-19'),
  (7,'打印耗材',260.00,'INV-1008','2026-10-29');

INSERT INTO sf_approval_instance (id, biz_type, biz_id, template_id, template_ver, current_step, status, starter_id, created_at, finished_at) VALUES
  (201,'expense',1,1,1,1,'approved',10086,'2026-11-02 18:20:00','2026-11-04 10:00:00'),
  (202,'expense',2,1,1,1,'approved',10086,'2026-11-06 12:40:00','2026-11-07 09:00:00'),
  (203,'expense',3,1,1,1,'approved',10086,'2026-11-11 15:10:00','2026-11-12 11:00:00'),
  (204,'expense',4,1,1,2,'running',10086,'2026-11-13 09:30:00',NULL),
  (205,'expense',5,1,1,1,'running',10086,'2026-11-14 19:05:00',NULL),
  (206,'expense',6,1,1,1,'rejected',10086,'2026-10-20 10:00:00','2026-10-21 15:00:00'),
  (207,'expense',7,1,1,1,'rejected',10086,'2026-10-30 14:22:00','2026-10-31 09:00:00');

INSERT INTO sf_approval_task (instance_id, step, approver_id, action, comment, acted_at) VALUES
  (201,1,3001,'agree','OK','2026-11-03 10:00:00'),(201,2,4002,'agree','paid','2026-11-04 10:00:00'),
  (202,1,3001,'agree','OK','2026-11-07 09:00:00'),(202,2,4002,'agree','OK','2026-11-07 09:30:00'),
  (203,1,3001,'agree','OK','2026-11-12 11:00:00'),(203,2,4002,'agree','OK','2026-11-12 14:00:00'),
  (204,1,3001,'agree','OK','2026-11-13 10:00:00'),(204,2,4002,'pending',NULL,NULL),
  (205,1,3001,'pending',NULL,NULL),
  (206,1,3001,'reject','over budget','2026-10-21 15:00:00'),
  (207,1,3001,'reject','no invoice','2026-10-31 09:00:00');

INSERT INTO sf_ticket
  (ticket_no, title, category, priority, status, reporter_id, assignee_id, dept_id, source,
   first_response_at, sla_deadline, resolution, reopen_count, csat_score, tags, created_at)
VALUES
  ('T-2026-1010-01','VPN cannot connect','it.network','p1','closed',10086,3001,301,'ai_chat',
   '2026-10-10 09:30:00','2026-10-10 17:30:00','reset vpn policy',0,5,'vpn,network','2026-10-10 09:15:00'),
  ('T-2026-1018-02','monitor flicker','it.hardware','p2','closed',10086,3001,301,'portal',
   '2026-10-18 14:00:00','2026-10-19 14:00:00','replaced cable',0,4,'hardware','2026-10-18 13:50:00'),
  ('T-2026-1025-03','leave balance wrong','hr','p3','closed',10086,NULL,301,'portal',
   '2026-10-25 10:00:00','2026-10-28 10:00:00','hr fixed balance',1,NULL,'hr,leave','2026-10-25 09:40:00'),
  ('T-2026-1101-04','printer offline','admin','p2','in_progress',10086,3001,301,'portal',
   '2026-11-01 11:00:00','2026-11-02 11:00:00',NULL,0,NULL,'printer','2026-11-01 10:45:00'),
  ('T-2026-1105-05','expense submit fail','finance','p1','resolved',10086,3001,301,'ai_chat',
   '2026-11-05 15:20:00','2026-11-05 23:20:00','fixed validation',0,NULL,'expense,bug','2026-11-05 15:05:00'),
  ('T-2026-1108-06','db access request','it.network','p3','assigned',10086,3001,301,'portal',
   '2026-11-08 16:00:00','2026-11-11 16:00:00',NULL,0,NULL,'db,access','2026-11-08 15:30:00'),
  ('T-2026-1110-07','projector no signal','admin','p2','triage',10086,NULL,301,'portal',
   NULL,'2026-11-11 10:00:00',NULL,0,NULL,'av,meeting','2026-11-10 09:50:00'),
  ('T-2026-1112-08','badge scan fail','hr','p2','waiting_user',10086,3001,301,'ai_chat',
   '2026-11-12 09:10:00','2026-11-13 09:10:00',NULL,0,NULL,'attendance','2026-11-12 09:00:00'),
  ('T-2026-1113-09','laptop fan noise','it.hardware','p3','escalated',10086,3001,301,'portal',
   '2026-11-13 14:00:00','2026-11-14 14:00:00',NULL,0,NULL,'laptop,hardware','2026-11-13 13:40:00'),
  ('T-2026-1114-10','download speed question','it.network','p3','duplicate',10086,NULL,301,'portal',
   '2026-11-14 10:00:00','2026-11-17 10:00:00',NULL,0,NULL,'network','2026-11-14 09:30:00'),
  ('T-2026-1114-11','annual leave FAQ','hr','p3','rejected',10086,NULL,301,'ai_chat',
   '2026-11-14 11:00:00','2026-11-17 11:00:00','covered by FAQ',0,NULL,'hr,faq','2026-11-14 10:50:00'),
  ('T-2026-1115-12','mail attachment blocked','it.network','p1','in_progress',10086,3001,301,'portal',
   '2026-11-15 08:40:00','2026-11-15 16:40:00',NULL,0,NULL,'mail,security','2026-11-15 08:30:00');

INSERT INTO sf_ticket_event (ticket_id, from_status, to_status, actor_id, reason, created_at) VALUES
  (1,NULL,'triage',10086,'created','2026-10-10 09:15:00'),
  (1,'triage','assigned',3001,'accept','2026-10-10 09:25:00'),
  (1,'assigned','in_progress',3001,NULL,'2026-10-10 09:28:00'),
  (1,'in_progress','resolved',3001,'fixed','2026-10-10 16:00:00'),
  (1,'resolved','closed',10086,'confirm','2026-10-10 17:00:00'),
  (4,NULL,'triage',10086,'created','2026-11-01 10:45:00'),
  (4,'triage','assigned',3001,NULL,'2026-11-01 10:55:00'),
  (4,'assigned','in_progress',3001,NULL,'2026-11-01 11:00:00'),
  (7,NULL,'triage',10086,'created','2026-11-10 09:50:00'),
  (9,'triage','assigned',3001,NULL,'2026-11-13 13:50:00'),
  (9,'assigned','escalated',3001,'wait part','2026-11-14 10:00:00'),
  (12,NULL,'triage',10086,'created','2026-11-15 08:30:00'),
  (12,'triage','assigned',3001,NULL,'2026-11-15 08:35:00'),
  (12,'assigned','in_progress',3001,NULL,'2026-11-15 08:40:00');

INSERT INTO sf_sla_event (ticket_id, type, at, notifier_id) VALUES
  (1,'first_response','2026-11-10 09:30:00',3001),
  (4,'first_response','2026-11-01 11:00:00',3001),
  (5,'first_response','2026-11-05 15:20:00',3001),
  (5,'resolve','2026-11-05 17:00:00',3001),
  (9,'first_response','2026-11-13 14:00:00',3001),
  (9,'breach','2026-11-14 14:00:00',NULL),
  (12,'first_response','2026-11-15 08:40:00',3001);

INSERT INTO sf_room_booking
  (booking_no, room_id, start_at, end_at, organizer_id, attendees, title, status, check_in_deadline, late_cancel_flag, credit_delta, created_at)
VALUES
  ('M-2026-1020-01',1,'2026-10-20 10:00:00','2026-10-20 11:00:00',10086,'10086,3001','sprint review','done','2026-10-20 10:15:00',0,0,'2026-10-18 09:00:00'),
  ('M-2026-1028-02',2,'2026-10-28 14:00:00','2026-10-28 15:00:00',10086,'10086','1on1','no_show','2026-10-28 14:15:00',0,-1,'2026-10-27 16:00:00'),
  ('M-2026-1105-03',1,'2026-11-05 09:30:00','2026-11-05 10:30:00',10086,'10086,3001,4001','weekly sync','done','2026-11-05 09:45:00',0,0,'2026-11-03 10:00:00'),
  ('M-2026-1112-04',3,'2026-11-12 15:00:00','2026-11-12 16:30:00',10086,'10086,3001','cross team','done','2026-11-12 15:15:00',0,0,'2026-11-10 11:00:00'),
  ('M-2026-1114-05',1,'2026-11-14 11:00:00','2026-11-14 12:00:00',10086,'10086','late cancel demo','late_cancel','2026-11-14 11:15:00',1,-1,'2026-11-13 18:00:00'),
  ('M-2026-1118-06',2,'2026-11-18 10:00:00','2026-11-18 11:00:00',10086,'10086,3001','iteration plan','booked','2026-11-18 10:15:00',0,0,'2026-11-15 14:00:00'),
  ('M-2026-1120-07',1,'2026-11-20 14:00:00','2026-11-20 15:00:00',10086,'10086,4001','interview retro','booked','2026-11-20 14:15:00',0,0,'2026-11-15 16:00:00'),
  ('M-2026-1110-08',3,'2026-11-10 09:00:00','2026-11-10 10:00:00',10086,'10086','cancelled demo','cancelled','2026-11-10 09:15:00',0,0,'2026-11-08 10:00:00');

UPDATE sf_asset SET status='stock', holder_id=NULL WHERE id IN (1,3);
UPDATE sf_asset SET status='assigned', holder_id=10086 WHERE id=2;
INSERT INTO sf_asset (sn, name, status, holder_id, dept_id, buy_date, warranty_end, depreciate_years, location, created_at) VALUES
  ('SN-MON-002','Dell 24 monitor','assigned',10086,301,'2025-06-01','2028-06-01',3,'desk A12','2025-06-01 10:00:00'),
  ('SN-KEY-001','mech keyboard','stock',NULL,301,'2026-01-15','2029-01-15',3,'IT store','2026-01-15 10:00:00'),
  ('SN-DOCK-01','Type-C dock','repair',10086,301,'2025-09-01','2027-09-01',2,'repairing','2025-09-01 10:00:00'),
  ('SN-LAP-010','ThinkPad T14','lost',10086,301,'2024-03-01','2027-03-01',3,NULL,'2024-03-01 10:00:00'),
  ('SN-LAP-011','MacBook Air','lost_pending_compensate',10086,301,'2024-07-01','2027-07-01',3,NULL,'2024-07-01 10:00:00'),
  ('SN-OLD-001','old desktop','disposed',NULL,301,'2018-01-01','2021-01-01',3,NULL,'2018-01-01 10:00:00');

INSERT INTO sf_notice (user_id, type, title, body, biz_type, biz_id, read_flag, created_at) VALUES
  (10086,'approval','pending leave L-2026-1112-04','manager step waiting','leave',4,0,'2026-11-12 10:20:01'),
  (10086,'approval','expense approved E-2026-1111-03','waiting finance pay','expense',3,0,'2026-11-12 14:00:01'),
  (10086,'sla_breach','SLA breach T-2026-1113-09','auto escalated','ticket',9,0,'2026-11-14 14:00:01'),
  (10086,'ticket','ticket update T-2026-1105-05','resolved','ticket',5,1,'2026-11-05 17:00:01'),
  (10086,'ticket','room no-show M-2026-1028-02','slot released credit -1','room',2,1,'2026-10-28 14:16:01'),
  (10086,'approval','pending leave L-2026-1113-07','manager agreed, waiting HR','leave',7,0,'2026-11-13 11:00:01'),
  (10086,'ticket','asset assigned SN-MON-002','monitor issued','asset',NULL,1,'2025-06-01 10:00:01'),
  (3001,'approval','new expense E-2026-1114-05','amount 128.00','expense',5,0,'2026-11-14 19:05:01'),
  (3001,'ticket','new ticket T-2026-1115-12','P1 mail blocked','ticket',12,0,'2026-11-15 08:30:01'),
  (4002,'approval','new expense E-2026-1113-04','amount 553.00 to pay','expense',4,0,'2026-11-13 10:00:01'),
  (10086,'system','welcome','demo data refreshed',NULL,NULL,1,'2026-11-16 09:00:01'),
  (10086,'approval','expense paid E-2026-1106-02','386.00 paid','expense',2,1,'2026-11-07 09:30:01');

INSERT INTO sf_audit_log (operator_id, action, target_type, target_id, before_json, after_json, trace_id, ip, created_at) VALUES
  (10086,'leave.submit','leave','L-2026-1101-01',NULL,'"pending_manager"','seed-1','127.0.0.1','2026-11-01 09:12:00'),
  (3001,'leave.approve','leave','L-2026-1101-01','pending_manager','"approved"','seed-2','127.0.0.1','2026-11-01 15:00:00'),
  (10086,'expense.submit','expense','E-2026-1102-01',NULL,'"pending_manager"','seed-3','127.0.0.1','2026-11-02 18:20:00'),
  (4002,'expense.pay','expense','E-2026-1102-01','approved','"paid"','seed-4','127.0.0.1','2026-11-04 10:00:00'),
  (10086,'ticket.create','ticket','T-2026-1010-01',NULL,'"triage"','seed-5','127.0.0.1','2026-10-10 09:15:00'),
  (3001,'ticket.assigned','ticket','T-2026-1010-01','triage','"assigned"','seed-6','127.0.0.1','2026-10-10 09:25:00'),
  (3001,'ticket.resolved','ticket','T-2026-1010-01','in_progress','"resolved"','seed-7','127.0.0.1','2026-10-10 16:00:00'),
  (10086,'room.book','room_booking','M-2026-1020-01',NULL,'"booked"','seed-8','127.0.0.1','2026-10-18 09:00:00'),
  (10086,'room.no_show','room_booking','M-2026-1028-02','booked','"no_show"','seed-9','127.0.0.1','2026-10-28 14:16:00'),
  (1,'config.sla.save','sla_policy','p3','4320','5000','seed-10','127.0.0.1','2026-11-01 08:00:00'),
  (10086,'asset.apply','asset','SN-MON-002','stock','"assigned"','seed-11','127.0.0.1','2025-06-01 10:00:00'),
  (10086,'expense.void','expense','E-2026-1020-06','approved','"voided"','seed-12','127.0.0.1','2026-10-25 10:00:00'),
  (3001,'leave.reject','leave','L-2026-1108-03','pending_manager','"rejected"','seed-13','127.0.0.1','2026-11-08 10:20:00'),
  (10086,'ticket.create','ticket','T-2026-1115-12',NULL,'"triage"','seed-14','127.0.0.1','2026-11-15 08:30:00'),
  (10086,'room.book','room_booking','M-2026-1118-06',NULL,'"booked"','seed-15','127.0.0.1','2026-11-15 14:00:00');

SELECT 'seed done' AS result;

-- ===== schema_b9.sql =====
-- B9: 配置中心补齐 — 资产分类主数据 + 会议室/分类配置接口
USE mianshi;

CREATE TABLE IF NOT EXISTS sf_asset_category (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  code               VARCHAR(32) NOT NULL UNIQUE,
  name               VARCHAR(64) NOT NULL,
  depreciate_years   INT NOT NULL DEFAULT 3,
  warranty_default   INT NOT NULL DEFAULT 36,
  status             VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) COMMENT '资产分类（配置中心）';

INSERT IGNORE INTO sf_asset_category (code, name, depreciate_years, warranty_default, status) VALUES
  ('laptop',   '笔记本', 3, 36, 'active'),
  ('monitor',  '显示器', 3, 36, 'active'),
  ('phone',    '手机',   2, 24, 'active'),
  ('badge',    '门禁卡', 1, 12, 'active'),
  ('dock',     '扩展坞', 3, 24, 'active'),
  ('furniture','家具',   5, 60, 'active');
