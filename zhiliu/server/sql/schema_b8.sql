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
