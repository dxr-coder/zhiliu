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
