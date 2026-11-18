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
