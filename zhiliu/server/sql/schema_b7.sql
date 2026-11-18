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
