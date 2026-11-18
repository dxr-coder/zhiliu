# SmartFlow 业务设计 v1 · Java 传统业务主干 + AI 增强

> 功能：把 SmartFlow 从「AI 聊天 demo 加若干工具」重构成**可上线的企业服务台业务系统**；
>   业务闭环用 Java（Spring Boot）做深，AI 只嵌在明确的增强点上，不反客为主。
> 数据流向：业务事件（请假/报销/工单/资产…）→ 状态机推进 → 审批/SLA → 落库；
>   AI 只在「理解、起草、路由、质检」四类点位读写业务，不直接改钱、改状态终态。
> 数据形状：每域给出核心表字段示例 + 一条完整状态流（见各章「实例」）。
> 定位：简历/面试用的「完整业务」蓝图——先定业务，再谈技术选型与排期。

---

## 0. 一句话定位（改口用）

> **企业内部一站式服务台**：员工办事（请假/报销/IT/行政/会议室）+ 智能问答 + 工单/SLA/审批，
> 业务状态机和权限是主干；AI 负责听懂人话、填单辅助、路由和质检——**AI 是副驾驶，业务是方向盘**。

与旧版差异（面试可讲）：

| 旧 SmartFlow | 新 SmartFlow |
|---|---|
| 聊天是主入口，工单是附属 | **办事流程是主干**，聊天是入口之一 |
| 工具查几张表演示 | **请假/报销/资产/会议室全状态机 + 审批链** |
| 权限三级粗粒度 | **RBAC + 数据权限（本部门/本人/授权）+ 操作审计** |
| AI 直接给答案 | **AI 建议 → 人确认 → 系统落库**（钱和终态永远人签） |
| 单机 demo | **Java 业务服务 + AI 服务拆分**，可独立扩缩 |

---

## 1. 业务全景（八大域）

```
                    ┌─────────────────────────────────────┐
                    │           组织与权限（地基）          │
                    │  租户/部门/角色/用户/数据权限/审计     │
                    └──────────────┬──────────────────────┘
                                   │
     ┌─────────────┬───────────────┼───────────────┬─────────────┐
     ▼             ▼               ▼               ▼             ▼
┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ ① 服务台 │  │ ② 人事    │  │ ③ 财务    │  │ ④ IT资产 │  │ ⑤ 行政    │
│ 工单+SLA │  │ 请假/加班 │  │ 报销/借款 │  │ 领用/归还│  │ 会议室    │
│ 转人工   │  │ 调休/证明 │  │ 对公付款 │  │ 故障报修 │  │ 用车/采购 │
└────┬────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │            │             │             │             │
     └────────────┴──────┬──────┴─────────────┴─────────────┘
                         ▼
              ┌─────────────────────┐
              │ ⑥ 审批中心（统一）   │  所有单据共用审批引擎
              │ 会签/或签/加签/转办  │
              └──────────┬──────────┘
                         ▼
              ┌─────────────────────┐
              │ ⑦ 知识库 + 智能问答  │  RAG / Agent（AI 域）
              │ ⑧ 数据与运营        │  看板/报表/SLA 达标/满意度
              └─────────────────────┘
```

**原则**：①–⑥ 是 Java 传统业务（必须状态机完整、可审计）；⑦–⑧ 里 AI 按第 8 章点位嵌入。

---

## 2. 角色与权限（地基，先于一切功能）

### 2.1 角色

| 角色 | 干什么 | 数据范围 |
|---|---|---|
| `employee` 普通员工 | 发起自己的单、问答、查进度 | 仅本人 |
| `manager` 部门主管 | 审批本部门单、看本部门看板 | 本部门及下级 |
| `agent` 服务台坐席 | 处理工单、转派、代建单 | 被分配的工单 + 授权代建 |
| `finance` 财务 | 报销/付款终审 | 财务域单据 |
| `hr` 人事 | 请假/考勤/证明终审 | 人事域单据 |
| `it_admin` IT 管理员 | 资产台账、故障单 | IT 域 |
| `admin` 系统管理员 | 用户/角色/配置/知识库 | 全局（操作全审计） |

> 面试点：角色是**岗位**，不是「用户等级」；一人可多角色（`user_roles` 多对多）。

### 2.2 数据权限三件套

1. **行级**：`dept_id` + `created_by`——列表 SQL 必带范围条件  
2. **列级**：薪酬/成本类字段仅 finance/hr 可见（DTO 裁剪，不是前端隐藏）  
3. **操作级**：`@RequirePermission("reimbursement:approve")` 注解，缺权限 403  

### 2.3 审计（每条必记）

```text
audit_log {
  id, operator_id, action,          // 如 reimbursement.approve
  target_type, target_id,           // 如 reimbursement / R-2026-00123
  before_json, after_json,          // 状态变更前后
  trace_id, ip, created_at
}
```

**实例**：张三（manager）批准报销 R-2026-00123  
→ `action=reimbursement.approve`，`before={status:pending_manager}`，`after={status:pending_finance}`，带 trace_id。

---

## 3. ① 服务台（工单 + SLA + 转人工）——门面业务

### 3.1 工单状态机（完整版，禁止只写 open/closed）

```text
                ┌────────── reopen ──────────┐
                ▼                            │
[新建] → triage(分诊中) → assigned(已受理) → in_progress(处理中)
              │                │                  │
              │                │                  ├→ waiting_user(等用户补充)
              │                │                  │        │
              │                │                  │        └→ in_progress
              │                │                  ├→ waiting_third(等三方/厂商)
              │                ▼                  │
              │           escalated(升级) ────────┤
              │                │                  │
              └─ rejected(不予受理)               ▼
                                         resolved(已解决)
                                                  │
                              user_confirm ────────┤
                                                  ▼
                                             closed(已关闭)
                                                  │
                                              reopen → assigned
```

| 状态 | 谁能推进 | 出口条件 |
|---|---|---|
| `triage` | agent | 分类 + 优先级 + 指派人 |
| `assigned` | agent | 领取/接单 |
| `in_progress` | agent | 处理动作写日志 |
| `waiting_user` | agent 标记 | 用户回复自动回 `in_progress` |
| `escalated` | 系统 SLA 或人工 | 升到上级组/主管 |
| `resolved` | agent | 填解决方案 |
| `closed` | 用户确认或超时 N 天自动关 | 终态（可 reopen） |
| `rejected` | agent+理由 | 终态 |

**实例**（一条工单的完整流）：

```json
{
  "ticket_no": "T-2026-1115-0042",
  "title": "VPN 连不上公司内网",
  "category": "it.network",
  "priority": "high",
  "status": "in_progress",
  "reporter_id": 10086,
  "assignee_id": 2001,
  "dept_id": 301,
  "sla_deadline": "2026-11-15T20:00:00+08:00",
  "source": "ai_chat",          // ai_chat | portal | phone | watch
  "ai_confidence": 0.82,        // AI 分诊置信度，低于阈值强制人工
  "tags": ["vpn", "network"]
}
```

流转轨迹（`ticket_events` 追加式，面试必讲）：

```text
created → triaged(cat=it.network, pri=high) → assigned(2001)
  → in_progress → waiting_user(要截图) → in_progress
  → resolved(重置策略组) → closed(用户点确认)
```

### 3.2 SLA 引擎（业务硬核）

| 优先级 | 首响 | 解决 | 超时动作 |
|---|---|---|---|
| `p0 紧急` | 15min | 2h | 升级主管 + 站内/IM 通知 |
| `p1 高` | 1h | 8h | 升级 |
| `p2 中` | 4h | 24h | 临期 25% 提醒 |
| `p3 低` | 1d | 72h | 仅记录 |

```text
sla_policies { priority, first_response_min, resolve_min, escalate_to_role }
sla_events   { ticket_id, type: first_response|resolve|breach, at, notifier }
```

**实例**：p1 单 10:00 创建 → 10:40 首响（记 `first_response`）→ 17:00 解决（未破 8h）→ 达标。  
若 18:00 仍未解决 → `breach` 事件 + 工单 `escalated`。

### 3.3 转人工（AI 退场协议）

```text
触发：用户说「转人工」 | AI 连续 2 次 confidence<0.6 | 用户点踩 | 涉钱/涉合规单
动作：conversation.assigned_agent_id = 坐席；AI 停写；坐席可看 AI 摘要与建议回复
结束：坐席关闭 transfer_session；对话可回 AI（显式按钮，不静默抢回）
```

---

## 4. ② 人事：请假（状态机做到审批中心级）

### 4.1 假种与余额

| 假种 | 扣减 | 附件 | 审批链 |
|---|---|---|---|
| 年假 | 是（按天） | 无 | 直属主管 →（>3天）HR |
| 病假 | 否（时长计） | >2天需证明 | 主管 → HR |
| 事假 | 否 | 无 | 主管 |
| 调休 | 是（按小时） | 加班单关联 | 主管 |
| 陪产/婚/产假 | 规则另计 | 证明 | 主管 → HR |

### 4.2 状态机

```text
draft(草稿) → submitted(已提交) → pending_manager(主管审)
    → pending_hr(HR审，按规则) → approved(通过)
    → rejected(驳回, 可改再提)
    → cancelled(申请人撤回, 仅 submitted/pending_* 可撤)
approved → effective(已生效, 到岗日) → done(考勤核销)
```

**实例**（数据形状）：

```json
{
  "leave_no": "L-2026-00451",
  "user_id": 10086,
  "type": "annual",
  "start": "2026-11-20",
  "end": "2026-11-22",
  "days": 3.0,
  "reason": "回家办事",
  "balance_after": 7.0,
  "status": "pending_manager",
  "approver_chain": [{"step":1,"role":"manager","user_id":3001},
                     {"step":2,"role":"hr","user_id":4001,"skip":false}],
  "ai_draft": false
}
```

**规则引擎（Java 写死可测，不靠 LLM）**：

- 余额不足 → 提交即拒（`balance_insufficient`）  
- 重叠假期 → 拒  
- 跨年假段 → 拆单或按公司规则  
- >3 天自动插入 HR 节点  

**AI 只做**：把「下周回家待三天」起草成单（`ai_draft=true`），**用户点确认才 `submitted`**。

---

## 5. ③ 财务：报销（钱相关，最保守）

### 5.1 状态机

```text
draft → submitted → pending_manager → pending_finance → approved
                                              │
                         paid(出纳打款) ◄─────┘
                         rejected ──────────► (可改再提)
approved → paid → archived
         ↘ voided(作废, 仅 finance+admin)
```

### 5.2 单据与发票

```json
{
  "expense_no": "E-2026-00891",
  "user_id": 10086,
  "type": "travel",                 // travel|meal|office|train|other
  "amount_total": 1286.50,
  "currency": "CNY",
  "items": [
    {"name": "高铁票", "amount": 553.0, "invoice_no": "04412...", "date": "2026-11-10"},
    {"name": "住宿",   "amount": 733.5, "invoice_no": "04413...", "date": "2026-11-11"}
  ],
  "cost_center": "CC-RD-01",
  "status": "pending_finance",
  "policy_flags": ["over_night_limit"],   // 规则引擎打标，不直接拒
  "ai_extract": {"source": "photo_upload", "confidence": 0.91}
}
```

**规则（Java Policy，可单测）**：

| 规则 | 动作 |
|---|---|
| 单张发票 > 住宿标准 | `policy_flags` 打标，finance 必审 |
| 发票号重复 | 提交拒绝 |
| 金额合计 ≠ 明细和 | 拒绝 |
| 月度部门预算剩余 < 单据额 | 打标 + 通知 manager |

**AI 嵌入**：OCR/LLM 从发票照片抽字段 → 填 `ai_extract` → **人工核对确认**；禁止 AI 自动 `approved`。

---

## 6. ④ IT 资产 + ⑤ 行政（补齐「传统业务完整度」）

### 6.1 资产

```text
入库 stock → 领用申请 apply → 审批 → assigned(绑定人)
  → 维修 repair → 归还 return → 折旧/报废 dispose
资产台账：asset { sn, name, category, status, holder_id, dept_id, buy_date, warranty_end }
```

- 转岗/离职：批量 `transfer` / `return` 流程（离职 checklist 一项）  
- 故障：自动生成 `ticket category=it.hardware`，资产状态 → `repair`  

### 6.2 会议室

```text
可用 → booked(时段锁) → checked_in(开场 15min 内签到)
  → no_show(超时释放) → done / cancelled
冲突：同 room + 时间段重叠 → 拒绝（DB 条件唯一或内存校验 + 乐观锁）
```

```json
{
  "booking_no": "M-2026-00211",
  "room_id": 12,
  "start": "2026-11-18T14:00:00+08:00",
  "end": "2026-11-18T15:00:00+08:00",
  "organizer_id": 10086,
  "attendees": [10086, 10087, 2001],
  "status": "booked",
  "check_in_deadline": "2026-11-18T14:15:00+08:00"
}
```

### 6.3 行政采购（简版闭环）

```text
需求单 → 比价(≥2家) → 审批 → 下单 → 到货验收 → 入库/报销关联
```

---

## 7. ⑥ 统一审批中心（所有域共用，Java 业务的心脏）

**不要给每个域写一套审批。** 引擎统一：

```text
approval_instances {
  id, biz_type, biz_id,          // leave / expense / asset / ticket_escalate...
  template_id,                   // 审批流模板
  current_step, status           // running|approved|rejected|cancelled
}
approval_tasks {
  id, instance_id, step, approver_id,
  action: pending|agree|reject|transfer|add_sign,
  comment, acted_at
}
```

**引擎能力（面试深挖点）**：

| 能力 | 说明 |
|---|---|
| 串行链 | 主管 → HR → 财务 |
| 或签 | 同级任一人同意即过 |
| 会签 | 同级全同意 |
| 加签/转办 | 临时插入节点、转交他人 |
| 驳回 | 回上一步 / 回发起人（两种策略配置） |
| 撤回 | 发起人在首节点同意前可撤 |
| 超时 | 自动催办 / 代理（配置项，默认不自动同意——**钱和假不自动过**） |

**与请假/报销的关系**：业务单只写 `biz_type+biz_id`；审批进度从引擎查，不复制状态。

---

## 8. AI 增强点位（白名单：只在这 6 处出现）

| # | 点位 | AI 做什么 | 人做什么 | 失败/低置信 |
|---|---|---|---|---|
| A1 | **意图理解与分诊** | 工单分类、优先级建议、路由坐席组 | agent 可改分类后接单 | conf<0.6 → 强制人工分诊 |
| A2 | **单据起草** | 从自然语言/照片填请假、报销草稿 | **用户确认提交** | 置信低字段留空人工补 |
| A3 | **知识问答 (RAG)** | 带引用回答制度/IT 问题 | 点踩→转人工；引用可点开 | 无引用 → `source=none` 拒答 |
| A4 | **坐席副驾** | 起草回复、建议解决方案、摘要历史 | 坐席一键采纳/修改 | 不自动发送给用户 |
| A5 | **质检抽检** | 对 closed 工单抽样打分（解决质量/礼貌） | 质检员复核 | 只出报告不改单 |
| A6 | **运营洞察** | 周报：高频问题、SLA 破线原因聚类 | 管理员看板 | 只读 |

**禁止（写进架构约束）**：

- AI 不直接改 `status` 到 `approved/paid/closed`  
- AI 不绕过审批中心  
- AI 不写 `audit_log` 以外的越权数据  
- 所有 AI 写库字段带 `ai_generated=true` 与 `model/prompt_version`  

**AI 与业务的接口形状**（Java ↔ AI 服务）：

```text
POST /ai/v1/triage     {text, history} → {category, priority, conf, reason}
POST /ai/v1/draft      {biz_type, utterance|image} → {fields, conf, missing[]}
POST /ai/v1/answer     {question, kb} → {answer, citations[], source}
POST /ai/v1/assist     {ticket_id} → {reply_draft, actions_suggested[]}
```

超时/熔断：AI 挂了业务照走（分诊进人工队列，草稿变纯表单）——**业务可用性 > AI 可用性**。

---

## 9. ⑦ 知识库业务（管理侧也要「传统业务化」）

```text
文档: draft → reviewing → published → archived
  版本: 同文档多 version, 仅 is_latest=true 参与检索
  权限: 部门可见范围 (dept_id / all)
  变更: published 必须审核人；下线要原因
```

- 上传 → 切分 → 向量化（AI 域）  
- **发布/下线/权限是业务状态机**（Java）  
- 评测：金标集与发布内容版本绑定（可回放「上周答案为何变了」）

---

## 10. ⑧ 数据与运营（完整项目的「可看数字」）

### 10.1 看板指标（全部可 SQL，不靠 AI）

| 板块 | 指标 |
|---|---|
| 服务台 | 今日新单、在办、首响 P95、解决 P95、SLA 达标率、重开率 |
| 人事 | 在途请假、各部门占用年假天数 |
| 财务 | 在途报销额、平均审批时长、打款及时率 |
| IT | 资产在用/闲置/维修、故障 TOP 类别 |
| 满意度 | CSAT 均分、NPS 简版、点踩会话数 |
| AI | 问答解决率、转人工率、草稿采纳率、Token 成本 |

### 10.2 导出与合规

- 工单/报销/假期：Excel 导出（权限裁剪）  
- 审计日志：按人/时间/动作检索，保留 ≥180 天（配置）  

---

## 11. 端到端故事（面试 2 分钟版）

**故事 A · 智能入口到工单闭环**  
员工在助手说「VPN 又连不上，急」→ AI 分诊 `it.network/p1`（conf 0.84）→ 自动建单指派网络组 → 坐席收到通知（首响 SLA 计时）→ 处理中要截图进 `waiting_user` → 用户补图回 `in_progress` → 解决方案 `resolved` → 用户确认 `closed` → CSAT 4 分 → 周报聚类「VPN 类占比 18%」。

**故事 B · 请假全程**  
「下周回家待三天」→ AI 草稿（年假 11/20–22，balance 10→7）→ **用户确认提交** → 审批中心串行：主管同意 →（3 天）HR 同意 → `approved` → 到岗 `effective` → 考勤 `done`。余额不足在提交前被规则拒掉，根本进不了审批。

**故事 C · 报销防呆**  
拍照上传 → AI 抽 3 张发票字段 → 用户改错一处 → 提交 → 规则引擎打标 `over_night_limit` → manager 同意 → finance 终审 → `paid`。发票号重复在提交时就被拒，不进审批流。

---

## 12. 功能清单（做到「极致完整」的验收表）

### P0 · 没有就不叫完整业务（第一期必须）

- [ ] 组织/用户/角色/数据权限 + 登录（JWT 双 Token）  
- [ ] 审批中心（串行/或签/驳回/撤回）+ 审计日志  
- [ ] 请假全状态机 + 余额规则 + 审批链  
- [ ] 报销全状态机 + 发票校验 + 规则打标  
- [ ] 工单全状态机 + SLA 四级 + 转人工  
- [ ] 会议室预订冲突 + 签到释放  
- [ ] 资产领用/归还最小闭环  
- [ ] 知识库发布状态机 + RAG 问答（带引用/拒答）  
- [ ] AI 六点位接口 + 低置信降级（AI 挂业务可用）  
- [ ] 员工端 + 坐席端 + 管理端三套界面主路径  
- [ ] 看板核心 10 指标  
- [ ] 单测覆盖状态机与规则（money/leave 规则必须测）  

### P1 · 加分完整度

- [ ] 加签/转办/超时催办  
- [ ] 离职资产/账号 checklist  
- [ ] 报销对公/借款核销  
- [ ] WS 通知（新单/审批/SLA 破线）  
- [ ] AI 质检抽检 + 坐席副驾  
- [ ] 金标评测（问答 + 分诊）进 CI  
- [ ] Locust：工单列表 P95、审批并发  
- [ ] Docker Compose 全栈 + 健康检查  

### P2 · 生产叙事（有则更硬）

- [ ] 多租户、操作二次确认（支付类）  
- [ ] Prometheus + 告警规则  
- [ ] K8s 清单（可单副本，文档写清演进）  
- [ ] 影子流量/金丝雀（AI prompt 版本）  

### 明确不做（防 scope 爆炸）

- 真实支付/银行对接  
- 复杂 BPMN 可视化编排（模板配置够用）  
- 移动原生 App（响应式 Web 即可）  

---

## 13. 技术形态建议（业务定完再定技术，一句话版）

```text
[web Vue3] → [Java Spring Boot 业务主干] → MySQL / Redis
                    │  （事务、状态机、审批、SLA 定时）
                    ▼ HTTP/gRPC
              [AI 服务 Python]  RAG · Agent · 起草 · 质检
                    │
              向量库 + LLM 网关
```

- **Java**：所有状态迁移、权限、审批、SLA、金额、审计——单测的主战场  
- **Python AI**：可替换组件，挂了降级，不承载资金与终态  
- 迁移策略：不推倒现有 Python 演示——**新 Java 业务库可先并行**，AI 接口协议按第 8 章冻结  

---

## 14. 里程碑（和求职节奏对齐）

| 期 | 内容 | 出口验收 |
|---|---|---|
| B1 地基 | 权限 + 审计 + 审批中心 + 用户端骨架 | 一条请假在审批中心走通 |
| B2 三单 | 请假 + 报销 + 工单状态机 + SLA | 三单 E2E + 规则单测绿 |
| B3 行政 IT | 会议室 + 资产 + 知识库发布态 | 冲突预订/领用归还 E2E |
| B4 AI 六点位 | 分诊/草稿/RAG/副驾接上，降级演练 | 拔掉 AI 业务仍可用 |
| B5 看板与压测 | 10 指标 + Locust + Compose | 一页数字可截图进简历 |
| B6 打磨 | WS 通知、评测 CI、文档面试化 | 简历 bullet 可改写 |

---

## 15. 简历 bullet 预告（业务做完就能填）

> - 设计并实现企业服务台**统一审批引擎**（串行/或签/驳回/加签），请假/报销/资产/工单共用，状态机与金额规则单测覆盖  
> - **SLA 四级策略** + 超时升级，工单全生命周期 12 态，事件溯源表可回放  
> - **AI 六点位白名单**（分诊/起草/RAG/副驾/质检/洞察），低置信降级人工，资金与终态禁止 AI 直写  
> - 请假余额/发票重复/预算打标等 **Policy 规则引擎**，业务规则不进 prompt  
> - 看板：SLA 达标率、审批时长、CSAT、AI 转人工率等 10+ 指标  

---

*业务冻结方式：本文 P0 清单勾完前不扩 P2；状态机图与 AI 白名单若改，须同步改第 3–8 章并记变更日志。*
