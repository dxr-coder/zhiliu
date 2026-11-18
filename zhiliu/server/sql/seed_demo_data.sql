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
