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
