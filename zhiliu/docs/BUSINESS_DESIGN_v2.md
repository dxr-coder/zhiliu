# SmartFlow 业务设计 v2 · Java 传统业务主干 + AI 增强（完整版）

> 功能：企业服务台**业务完整度对齐苍穹外卖/黑马点评的打磨密度**——主数据可配、状态机含边界态、
>   三端按钮级功能、列表五件套、种子数据、生产挂点齐全；域仍是企业办事，不是外卖。
> 数据流向：配置/主数据 → 业务单据状态机 → 统一审批/SLA → 事件与审计 → 看板；AI 仅六点位白名单读写。
> 数据形状：每域给核心表 + 状态全集 + 一条端到端实例；三端功能到按钮。
> 与 v1 关系：v1 定域与状态机骨架，v2 补「苍穹级」六类密度缺口（A–F），可直接当开发范围说明书。

---

## 0. 定位与完整性标准

**一句话**：员工办事 + 智能问答 + 工单/SLA/审批的一站式服务台；**业务是方向盘，AI 是副驾驶**。

**完整性标准（对标苍穹/黑马，域不同、密度相同）**：

| # | 标准 | 本设计落点 |
|---|---|---|
| A | 主数据与业务规则**可配置**，改配置不改代码 | 第 2 章配置中心 |
| B | 每域状态机含**边界态**（撤/销/并/丢/超时） | 第 4–8 章状态全集 |
| C | **三端按钮级**功能清单 | 第 12 章 |
| D | 所有管理列表**搜索/筛选/分页/排序/导出** + 导入 | 第 13 章 |
| E | **种子数据**开箱可演示 | 第 14 章 |
| F | 生产考量挂到具体业务（缓存/防超卖/幂等/定时） | 第 15 章 |

**AI 铁律（不变）**：只走第 11 章六点位；不改终态、不过审批；低置信降级；AI 挂业务可用。

---

## 1. 业务全景（九域）

```text
[配置中心] 主数据：部门树/角色/假种标准/SLA/审批模板/会议室/资产分类/预算
     │
     ▼
①组织权限 ─┬─ ②服务台工单+SLA+转人工
           ├─ ③人事（请假/加班/调休/证明）
           ├─ ④财务（报销/借款/冲销）
           ├─ ⑤IT资产（台账/领用/维修/丢失/报废）
           ├─ ⑥行政（会议室/采购）
           └─ ⑦统一审批中心（全业务共用）
                    │
           ⑧知识库发布态 + AI 六点位
                    │
           ⑨业务大盘 + 运维大盘 + 通知中心
```

**不变式**：钱、假、资产终态必须人审；AI 写字段必带 `ai_generated`；状态迁移必写 `*_events` + `audit_log`。

---

## 2. 配置中心与主数据（苍穹「套餐可配」的等价物）

### 2.1 主数据 CRUD（管理端 P0）

| 实体 | 关键字段 | 配置项（改了不改代码） |
|---|---|---|
| 部门树 `dept` | id, parent_id, name, cost_center_id, manager_id | 层级、负责人、启用停用 |
| 用户 `user` | id, emp_no, dept_id, status, multi-role | 启用/禁用、重置密码、踢下线 |
| 角色权限 `role`/`perm` | role_code, perm_code | 角色-权限矩阵勾选 |
| 假种 `leave_type` | code, need_balance, need_cert, cert_days, chain_policy | 是否扣余额、证明阈值、审批链策略 |
| 报销标准 `expense_policy` | type, limit_per_day, limit_per_night, need_invoice | 单日/单晚限额、是否必须票 |
| SLA 策略 `sla_policy` | priority, first_resp_min, resolve_min, escalate_role | 四级时效与升级对象 |
| 审批模板 `approval_template` | biz_type, steps[] | 每步角色/或签会签/驳回策略 |
| 会议室 `meeting_room` | code, name, capacity, equip[], location, status | 增删改查、停用、设备标签 |
| 资产分类 `asset_category` | code, name, depreciate_years, warranty_default | 分类与折旧年限 |
| 预算 `dept_budget` | dept_id, period, amount, used | 月/季预算与占用 |
| 知识库目录 `kb_space` | name, dept_scope, status | 可见范围、发布审核人 |

### 2.2 配置生效规则

- 配置带 `version` + 生效时间；**在途单据锁提交时快照**（`chain_snapshot`），不被中途改配置带偏  
- 所有配置变更写 `audit_log`（before/after）  
- 例：把年假审批链从「主管」改成「主管→HR」，**已 `pending_manager` 的单不回溯**，新提交单走新链  

### 2.3 列表五件套（全域管理页通用）

```text
GET /api/{resource}?keyword=&status=&dept_id=&from=&to=&page=1&size=20&sort=created_at:desc
→ { total, records: [...] }   // DTO 按角色裁剪列
POST /api/{resource}/export  → xlsx（异步 job，大页）
POST /api/{resource}/import  → 模板下载 + 校验报告（部门/资产用）
```

---

## 3. 角色、数据权限、审计、通知（地基加厚）

### 3.1 角色 × 数据范围

| 角色 | 数据范围 | 典型按钮见第 12 章 |
|---|---|---|
| employee | 仅本人单据 | 发起、撤回、确认收货 |
| manager | 本部门及下级 | 审批、部门看板、代提交 |
| agent | 被分配/组内工单 | 分诊、接单、转派、解决 |
| finance | 财务域 | 终审、打款、冲销、导出 |
| hr | 人事域 + 全公司假期视图 | 终审、销假、证明、导出 |
| it_admin | 资产域 + IT 工单组 | 台账、领用批审、报废 |
| admin | 全局配置 | 用户/配置/模板/种子重置 |

一人多角色：`user_roles` 多对多；JWT 带 `roles[]` + `dept_id`。

### 3.2 通知中心（站内信，完整读态）

```text
notice { id, user_id, type: sla_breach|approval|ticket|kb_publish|asset_warranty,
         title, body, biz_type, biz_id, read_flag, created_at }
```

- 列表：未读筛选、批量已读、按类型分页  
- 触发点：审批待办、SLA 破线、工单指派/回复、会议室临期、资产保修到期、知识库待审  
- **不做**：强依赖第三方 IM（可留 webhook 配置位）

### 3.3 审计

```text
audit_log { id, operator_id, action, target_type, target_id,
            before_json, after_json, trace_id, ip, created_at }
```

动作命名：`{domain}.{verb}`，如 `expense.void`、`room.cancel`、`config.sla.update`。

---

## 4. ② 服务台工单（状态全集 + 边界）

### 4.1 状态全集（12+ 边界态）

```text
draft → triage → assigned → in_progress ⇄ waiting_user
                             ⇄ waiting_third
                              → escalated → in_progress
              ↘ rejected(终态)
in_progress → resolved → closed(终态, 可 reopen→assigned)
resolved → duplicate(合并到 main_ticket_id, 终态)
closed → reopen → assigned
```

| 状态 | 推进方 | 边界规则 |
|---|---|---|
| `duplicate` | agent | 必须填 `merge_target_id`，原单进主单时间线 |
| `rejected` | agent | 必须理由码 + 说明；用户可申诉新开单 |
| `escalated` | SLA 或人工 | 升级对象来自 SLA 配置；可降回 |
| `waiting_user` | agent | 超 72h 未回 → 自动 resolved 或提醒（可配） |
| `closed` | 用户确认 / 超时 3 天自动关 | 关闭后 7 天内可 reopen 一次（可配） |

### 4.2 工单字段（完整）

```json
{
  "ticket_no": "T-2026-1115-0042",
  "title": "VPN 连不上公司内网",
  "category": "it.network",
  "sub_category": "vpn",
  "priority": "p1",
  "status": "in_progress",
  "reporter_id": 10086,
  "assignee_id": 2001,
  "group_id": 10,                      // 服务组
  "dept_id": 301,
  "source": "ai_chat",
  "ai_confidence": 0.82,
  "sla_policy_id": 2,
  "first_response_at": "2026-11-15T10:40:00+08:00",
  "sla_deadline": "2026-11-15T18:00:00+08:00",
  "resolution": "已重置 VPN 策略组",
  "merge_target_id": null,
  "reopen_count": 0,
  "csat_score": null,
  "tags": ["vpn", "network"]
}
```

**事件流（追加）**：`ticket_events { ticket_id, from_status, to_status, actor, reason, at }`  
**实例轨迹**：created→triaged→assigned→in_progress→waiting_user→in_progress→resolved→closed（CSAT=4）

### 4.3 SLA（配置驱动）

| 优先级 | 默认首响 | 默认解决 | 破线动作 |
|---|---|---|---|
| p0 | 15min | 2h | 通知 escalate_role + 站内信 + 看板红灯 |
| p1 | 1h | 8h | 升级 |
| p2 | 4h | 24h | 临期 25% 提醒 |
| p3 | 1d | 72h | 仅记录 |

`sla_events { ticket_id, type: first_response|resolve|remind|breach, at }`  
定时任务每分钟扫描到期（见第 15 章）。

### 4.4 转人工协议

触发：用户说转人工 / AI 连续 2 次 conf&lt;0.6 / 点踩 / 涉钱涉合规。  
动作：`transfer_sessions` 开启，AI 停写，坐席见 AI 摘要；结束须坐席显式关，不静默抢回。

---

## 5. ③ 人事：请假/加班/调休（边界态补全）

### 5.1 假种（来自配置，示例）

| 假种 | 扣余额 | 证明 | 默认审批链 |
|---|---|---|---|
| annual 年假 | 是 | 无 | manager →（&gt;3 天）hr |
| sick 病假 | 否 | &gt;2 天要证明 | manager → hr |
| personal 事假 | 否 | 无 | manager |
| comp 调休 | 是（小时） | 关联加班单 | manager |
| marriage/maternity… | 规则配 | 证明 | manager → hr |

### 5.2 状态全集

```text
draft → submitted → pending_manager → [pending_hr] → approved
                 ↘ rejected(可改再提)
submitted|pending_* → cancelled(撤回)
approved → effective(到岗) → done(考勤核销)
approved → cancelled_after_approve(销假: 扣回已占余额, 仅未生效且 hr+申请人可发起)
effective → done
done → (考勤异常) pending_adjust → done   // 补卡/修正，不回请假单
```

### 5.3 规则（Policy 单测清单）

1. 余额不足 → `submitted` 前拒  
2. 区间重叠已有单 → 拒  
3. 跨自然年 → 按配置拆单或整单挂到起始年  
4. \>N 天自动插 HR 节点（N 来自假种配置）  
5. 病假超阈值无证明 → 拦提交  
6. 销假：`balance_used` 回滚，写 `balance_ledger` 流水  

### 5.4 实例

```json
{
  "leave_no": "L-2026-00451",
  "type": "annual",
  "start": "2026-11-20", "end": "2026-11-22", "days": 3.0,
  "status": "pending_manager",
  "balance_before": 10.0, "balance_hold": 3.0, "balance_after": 7.0,
  "chain_snapshot": [{"step":1,"role":"manager"},{"step":2,"role":"hr","skip":false}],
  "ai_draft": false
}
```

**加班单**：`overtime { date, hours, reason, status: draft→submitted→approved→comp_granted }`  
→ 批准后写入调休余额（`balance_ledger type=grant`）。

---

## 6. ④ 财务：报销/借款/冲销

### 6.1 状态全集

```text
draft → submitted → pending_manager → pending_finance → approved → paid → archived
                 ↘ rejected
approved → cancelled(仅未打款, finance)
paid → voided(冲销: 负向单关联原单, 仅 finance+admin, 双人复核可配)
paid → (发现多付) repay_claim → paid   // 追回流程，不直接改原单
```

### 6.2 单据形状（含幂等与规则）

```json
{
  "expense_no": "E-2026-00891",
  "idempotency_key": "user10086:20261115:travel:1286.50",
  "type": "travel",
  "amount_total": 1286.50,
  "items": [{"name":"高铁票","amount":553.0,"invoice_no":"04412...","date":"2026-11-10"}],
  "cost_center": "CC-RD-01",
  "budget_period": "2026-11",
  "budget_hold": 1286.50,
  "policy_flags": ["over_night_limit"],
  "status": "pending_finance",
  "ai_extract": {"source":"photo","confidence":0.91}
}
```

### 6.3 规则表（可配标准 + 硬规则）

| 规则 | 动作 |
|---|---|
| 发票号全局重复 | 拒提交 |
| 明细和 ≠ 总额 | 拒提交 |
| 超标准（配置） | 打 `over_*` 标，finance 必看 |
| 部门预算不足 | 打 `budget_low` + 通知 manager |
| 同 `idempotency_key` 已存在 | 返回原单（防重复提交） |
| 无票必填项缺失 | 拒（除非类型配置允许无票） |

**借款**：`loan { amount, purpose, status: draft→…→approved→disbursed→settled }`，报销可关联核销借款余额。  
**AI**：发票照片抽字段进 `ai_extract`，人工确认；**禁止 AI 进 approved/paid**。

---

## 7. ⑤ 资产 + ⑥ 行政（边界态补全）

### 7.1 资产生命周期

```text
purchasing → stock → apply → assigned
assigned → repair → assigned | stock
assigned → return → stock
assigned → lost(丢失) → lost_pending_compensate → compensate_done → stock(翻新后) | dispose
stock → dispose(报废) → disposed
assigned → transfer(new_holder)     // 转岗
任意在册 → warranty_expire_notify   // 定时
```

```text
asset { sn, name, category_id, status, holder_id, dept_id,
        buy_date, warranty_end, depreciate_years, location, 
        last_check_at }          // 年度盘点
asset_flow { asset_id, action, from_holder, to_holder, actor, at, reason }
```

- **离职 checklist**：名下 `assigned` 资产必须 `return/transfer` 才允许 HR 关离职单  
- 故障报修：自动生成 `ticket category=it.hardware`，资产 → `repair`  
- 丢失：`lost` 必须填说明 → 赔偿流程（可走审批模板 `asset_compensate`）→ 才能回 `stock/dispose`

### 7.2 会议室（含改期/违约/抢订）

```text
booked → checked_in(开场15min内) → done
booked → cancelled(免费: 开场前≥配置时长, 默认2h)
booked → cancelled(迟到/临期取消: 记 late_cancel, 可配信用扣分)
booked → no_show(超时未签到, 系统释放, 扣信用)
booked → reschedule = cancelled + new booking(同申请单关联)
```

```text
booking { no, room_id, start, end, organizer_id, attendees[],
          status, check_in_deadline, late_cancel_flag, credit_delta }
```

**防超卖（P1）**：同 `room_id` 时间重叠  
1. DB：`UNIQUE` 或区间锁 + 乐观锁 `version`  
2. 高峰：Redis Lua 按 `room:YYYY-MM-DD` 占时段 bitmap/SET，失败快速拒  
3. 回归：并发 50 抢同一时段只成 1 单  

### 7.3 采购（完整闭环）

```text
demand → quote(≥2家) → approve → ordered → received → accepted → paid_link(关联报销) | rejected
```

---

## 8. ⑦ 统一审批中心（引擎能力全集）

```text
approval_instance { id, biz_type, biz_id, template_id, template_ver,
                    current_step, status: running|approved|rejected|cancelled }
approval_task { id, instance_id, step, approver_id, 
                action: pending|agree|reject|transfer|add_sign|skip,
                comment, acted_at, due_at }
```

| 能力 | 规则 |
|---|---|
| 串行/或签/会签 | 模板 step 配置 |
| 加签 | 前置/后置插入，不改已同意步 |
| 转办 | 改 assignee，留 transfer 记录 |
| 驳回策略 | `to_prev` / `to_start` / `to_assigner`，模板可配 |
| 撤回 | 发起人在**首节点未同意**前可撤 |
| 超时 | 催办通知；**默认不自动同意**（钱/假） |
| 撤销已批 | 走业务侧边界态（销假/冲销），不直接改 instance 为假 |

**与业务单关系**：业务只存 `approval_instance_id`；进度以引擎为准，禁止双写状态。

---

## 9. ⑧ 知识库（发布态 + 权限）

```text
document: draft → reviewing → published → archived
  同 doc 多 version，仅 is_latest && published 参与检索
  变更 published 必须审核人 + 原因
  dept_scope: all | dept_ids[]
```

- 上传/切分/向量化在 AI 侧；**发布/下线/权限是 Java 状态机**  
- 下线后检索立即失效（缓存按 `doc_version` 打 key，见第 15 章）

---

## 10. ⑨ 看板：业务大盘 + 运维大盘

### 10.1 业务大盘（给老板看）

| 指标 | 口径 |
|---|---|
| 工单量 | 新建/完成/在办，按日曲线 |
| SLA 达标率 | `sla_events` 无 breach 的完成单 / 完成单 |
| 报销 | 在途笔数与金额、平均审批时长、打款及时率 |
| 请假 | 在途、各部门年假占用 TOP |
| 资产 | 在用/闲置/维修/丢失、保修 30 天内到期数 |
| 会议室 | 使用率、no_show 率、信用扣分人次 |
| CSAT | 均分、点踩率 |
| 知识库 | 本周发布数、被引用 TOP10 问题 |

### 10.2 运维/AI 大盘

| 指标 | 口径 |
|---|---|
| AI 分诊准确抽检 | 人工改分类率 |
| 草稿采纳率 | `ai_draft=true` 且用户未改关键字段提交比例 |
| RAG 解决率 | 无转人工的问答会话 / 总会话 |
| 转人工率 | 按日 |
| Token 成本 | 按日/按应用 |
| 任务健康 | 定时任务上次成功时间、失败队列 |

导出：任意看板日期范围 → Excel。

---

## 11. AI 六点位（白名单，接口冻结）

| # | 点位 | AI | 人 | 降级 |
|---|---|---|---|---|
| A1 | 分诊 | category/priority/conf | agent 可改后接单 | conf&lt;0.6 进人工队列 |
| A2 | 单据起草 | 话术/照片→字段 | **确认才提交** | 空字段人工补 |
| A3 | RAG 问答 | 带 citations | 点踩→转人工 | 无引用 `source=none` |
| A4 | 坐席副驾 | 回复草稿/摘要 | 一键采纳/改 | 不自动发送 |
| A5 | 质检抽检 | closed 单打分 | 质检员复核 | 只出报告 |
| A6 | 运营洞察 | 周报聚类 | 管理员看 | 只读 |

```text
POST /ai/v1/triage | /draft | /answer | /assist | /qc_sample | /insight
```

超时 3s / 熔断 → 业务降级路径（人工分诊、纯表单、拒答转人工）。  
禁止：AI 写 `approved/paid/closed`、绕过审批、无 `ai_generated` 落库。

---

## 12. 三端功能清单（按钮级）

### 12.1 用户端（employee）

1. 登录/登出/改密/会话踢出  
2. 首页：待我确认、在途单、未读通知  
3. 智能问答（SSE，引用可点，转人工按钮）  
4. 发起工单（表单 + AI 分诊预填可改）  
5. 我的工单列表（状态筛选、进度时间线、补充材料、确认解决、评价 CSAT）  
6. 请假：新建（AI 草稿）、余额、撤回、销假申请、证明上传  
7. 报销：新建（拍照抽票）、明细、撤回、进度、关联借款查看  
8. 加班申请 / 调休查询  
9. 会议室：空闲查询、预订、我的预订、签到、取消、改期  
10. 资产：我的资产、报修、归还申请  
11. 采购需求提报（简表）  
12. 通知中心（未读/已读/跳转单据）  
13. 个人资料（部门只读）  
14. 导出：我的报销/假期年度汇总  

### 12.2 坐席/主管端（agent / manager）

1. 登录 + 在线状态  
2. 工作台：待分诊、我的在办、组队列（拖拽/按钮转派）  
3. 分诊：改分类/优先级/指派/合并重复单/驳回  
4. 处理：状态推进、内部备注（用户不可见）、解决方案模板、等待用户/三方  
5. 升级/降级、转组  
6. 审批中心：待办列表、同意/驳回/加签/转办、批量或签  
7. 部门看板（manager：本部门 SLA、在途请假报销）  
8. 代员工建单（请假/报销代提交，标 `on_behalf`）  
9. 会议室管理视图（本部门预订、代订）  
10. 知识库：本部门文档草稿提交审核  
11. 通知处理  
12. 坐席副驾：查看 AI 建议回复、采纳发送  

### 12.3 管理端（admin + 各域专员）

1. 登录/用户管理/角色权限矩阵/部门树/启用禁用/踢下线  
2. 配置中心：假种、报销标准、SLA、审批模板、预算、会议室、资产分类、知识库目录  
3. 工单：全量检索、SLA 破线专列、合并、导出  
4. 审批实例监控：卡点时长、强制转办（admin）  
5. 人事：假期导出、销假审批、加班-调休台账、证明抽查  
6. 财务：报销队列、打款标记、冲销、预算执行、发票重复报表、导出  
7. 资产：入库、盘点、批审领用、维修、丢失赔偿、报废、保修到期、导入导出  
8. 会议室：房间 CRUD、预订总览、信用名单、no_show 处理  
9. 采购：需求/比价/验收全队列  
10. 知识库：审核发布、下线、权限、版本历史  
11. 通知模板与发送记录  
12. 审计日志检索（人/时间/动作/对象）  
13. 看板两套 + 导出  
14. AI 运维：分诊抽检任务、草稿采纳率、Token 成本、AI 开关（全局降级）  
15. 种子数据：生成演示数据 / 重置（二次确认）  
16. 定时任务健康：SLA 扫描、会议室释放、保修提醒、超时关单  
17. 系统：字典、幂等键清理、导出任务队列  
18. 备份状态只读展示（链到运维文档）  

---

## 13. 列表/导入导出规范（P0）

| 资源 | 搜索 | 筛选 | 导出 | 导入 |
|---|---|---|---|---|
| 工单 | 单号/标题/标签 | 状态/优先级/组/时间 | ✓ | — |
| 请假/加班 | 单号/申请人 | 状态/假种/部门/时间 | ✓ | — |
| 报销 | 单号/发票号 | 状态/类型/金额段/时间 | ✓ | — |
| 资产 | SN/名称 | 分类/状态/持有人/部门 | ✓ | ✓（台账） |
| 预订 | 单号/会议室 | 状态/日期/组织者 | ✓ | — |
| 用户/部门 | 姓名/工号/部门名 | 状态/角色 | ✓ | ✓（用户） |
| 审计 | 操作者/对象 ID | 动作/时间 | ✓ | — |
| 通知 | 标题 | 类型/已读/时间 | — | — |

分页默认 20；导出走异步 job + 通知中心下载；导入先校验后事务提交，失败行报告。

---

## 14. 种子数据（演示验收 E）

一键 `POST /admin/seed`（或 CLI）生成：

| 数据 | 数量 | 覆盖 |
|---|---|---|
| 部门 | 3（含子部门） | 树 |
| 用户 | 20 | 全角色 |
| 假种/标准/SLA/审批模板 | 默认全套 | 配置可改 |
| 会议室 | 5（不同容量/设备） | 冲突可演示 |
| 资产 | 30 | stock/assigned/repair/lost |
| 工单 | 10 | 覆盖 12 态中 8+ 态 |
| 请假/报销 | 各 8 | 含驳回/在途/已打款 |
| 会议室预订 | 6 | 含 no_show/已签到 |
| 通知/审计 | 若干 | 非空 |
| 知识库文档 | 10 published + 2 review | RAG 可问 |

**验收**：新人 clone 后 10 分钟内能点完第 12 章每端至少 3 条主路径。

---

## 15. 生产挂点（F：缓存/并发/幂等/定时）

| # | 业务点 | 方案 | 回归 |
|---|---|---|---|
| P-1 | 会议室高峰抢订 | Redis Lua 占时段 + DB 乐观锁 | 50 并发同时段仅 1 成 |
| P-2 | 报销/请假重复提交 | `idempotency_key` 唯一 | 同键双击只一单 |
| P-3 | 热点知识库/服务目录 | Cache-Aside，key 含 `version` | 改发布即失效 |
| P-4 | SLA 扫描 | 每分钟 job：到期提醒/破线升级 | 单测时间旅行 |
| P-5 | 会议室超时释放 | job：过 `check_in_deadline` → no_show | 状态与信用正确 |
| P-6 | 保修到期提醒 | job：`warranty_end-30d` → 通知 it_admin | 通知去重 |
| P-7 | 超时自动关单 | job：resolved 超 3 天 → closed | reopen 计数 |
| P-8 | 预算占用 | 提交 hold / 打款/取消释放 | 并发不超占（行锁或 Redis） |
| P-9 | 登录 | 双 Token 轮换、同号踢旧、失败锁定 | 安全用例 |
| P-10 | 导出大页 | 异步 + 进度 | 千行不超时 |

**明确不做（防膨胀）**：真实支付、BPMN 拖拽、原生 App、多租户计费（单租户字段预留即可）。

---

## 16. 端到端故事（面试 3 条）

**A · 工单+SLA+AI**  
「VPN 急」→ AI 分诊 p1 conf0.84 → 建单计时 → 40min 首响 → 要截图进 waiting_user → 解决 → 用户确认 closed → CSAT4 → 破线单进大盘红灯 → 周报聚类。

**B · 请假+审批+配置**  
「下周三天」→ AI 草稿年假 10→7 → 确认提交 → 链快照 manager→hr（3 天）→ 两审通过 → 到岗 effective → 考勤 done；中途 HR 改链不影响本单。

**C · 报销+规则+幂等**  
拍照抽 3 票 → 用户改一处 → 双击提交被幂等键挡成一单 → 超住宿标准打标 → manager 同意 → finance 终审打款 paid → 若多付走 voided 冲销双人复核。

**D · 边界（加分）**  
已批年假提前销假 → 余额回滚流水；会议室开场前 1h 取消记 late_cancel 扣信用；资产丢失 → 赔偿审批 → 才回库。

---

## 17. 验收表

### P0（完整业务底线）

- [ ] 配置中心 11 类主数据可配，链快照生效  
- [ ] 请假/报销/工单/资产/会议室**状态全集**含边界态，单测锁迁移  
- [ ] 统一审批：串行/或签/驳回/撤回/加签  
- [ ] SLA 四级 + 定时扫描 + 破线通知  
- [ ] 三端第 12 章按钮**主路径全通**  
- [ ] 列表五件套 + 报销/资产/用户导入导出  
- [ ] 种子数据一键可演示  
- [ ] 幂等提交、会议室防超卖（至少 DB 级）、预算 hold  
- [ ] AI 六点位 + 拔掉 AI 业务可用  
- [ ] 业务大盘 8 指标 + 运维 6 指标  
- [ ] 审计全覆盖配置与终态操作  

### P1

- [ ] Lua 抢订、Cache-Aside、信用分、借款核销、离职 checklist  
- [ ] WS 通知、AI 质检、导出异步、保修/关单 job 全套  
- [ ] Locust：工单列表 P95、审批并发、预订并发  
- [ ] 问答/分诊金标进 CI  

### P2

- [ ] Prometheus、K8s 清单、prompt 影子流量、多租户字段预留  

---

## 18. 里程碑（与求职节奏）

| 期 | 内容 | 出口 |
|---|---|---|
| B1 | 配置+权限+审计+通知+审批中心 | 一条请假走通配置链 |
| B2 | 请假+报销+工单状态机+SLA job | 三单 E2E+规则单测绿 |
| B3 | 资产+会议室+采购+种子 | 冲突预订/丢失/离职 checklist E2E |
| B4 | 三端 UI 主路径 + 列表五件套 | 第 12 章按钮可点 |
| B5 | AI 六点位+降级+看板 | 拔 AI 仍可用；大盘有数 |
| B6 | 生产挂点 P-1~P-10 + 压测 + 面试化文档 | 简历 bullet 可填 |

---

## 19. 简历 bullet 预告

> - 设计**统一审批引擎**（串行/或签/加签/驳回策略/模板版本快照），请假报销资产工单共用，状态机与 Policy 规则单测覆盖  
> - 工单 **12 态含合并/升级/重开**，SLA 四级配置化 + 分钟级扫描破线升级；事件表可回放  
> - **配置中心**驱动假种/标准/审批链/SLA，改配置不改代码；在途单链快照防漂移  
> - 边界闭环：销假回滚余额、报销幂等与冲销、会议室改期/爽约信用、资产丢失赔偿  
> - **AI 六点位白名单**与业务降级路径，资金与终态禁止 AI 直写；种子数据 10 分钟可演示  

---

*变更：v2 相对 v1 补 A–F 六类密度（配置/边界态/三端按钮/列表规范/种子/生产挂点），域未改为交易外卖。P0 勾完前不扩 P2。*
