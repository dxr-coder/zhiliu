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
