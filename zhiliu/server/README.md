# 知流 · Java 业务主干

> 功能：企业服务台 Spring Boot 业务侧（登录/RBAC/请假审批/审计/通知）；AI 不在此仓库。
> 业务设计：`D:\pythondata\mianshi\03-projects\zhiliu\docs\BUSINESS_DESIGN_v2.md`
> 数据流向：HTTP → Interceptor(JWT) → Service → MyBatis-Plus → MySQL `mianshi.sf_*`
> 数据形状：统一 `Result{code,msg,data}`；登录返回 `{token,user,perms}`。

## 怎么打开

1. IDEA → **Open** → 选本目录 `D:\pythondata\mianshi\03-projects\zhiliu\server`（父 pom）
2. 等 Maven 导入完成（JDK 21）
3. 配置已在 `smartflow-server/src/main/resources/application.yml`（MySQL root/123456，库 mianshi）
4. 运行 `SmartFlowApplication`（内部类名） → 端口 **8081**

## 建表 / 种子

库已建好则跳过：

```powershell
D:\MySql\MySqll\bin\mysql -uroot -p123456 --default-character-set=utf8mb4 < sql\schema_b1.sql
```

演示账号密码均为 `123456`：`zhangsan` 员工 / `liming` 主管 / `wangwu` HR / `zhaoliu` 财务 / `admin` 管理员。

## 模块

| 模块 | 职责 |
|---|---|
| smartflow-common | Result / 异常 / JWT / 分页 |
| smartflow-pojo | 实体（sf_* 表映射） |
| smartflow-server | Controller / Service / 审批引擎 / 拦截器 |

## 命令行启动

```powershell
cd D:\pythondata\mianshi\03-projects\zhiliu\server
$env:JAVA_HOME="D:\java\jdk21"
$env:Path="D:\java\jdk21\bin;D:\java\maven\apache-maven-3.10.0\bin;$env:Path"
mvn -DskipTests install
cd zhiliu-server
mvn -DskipTests spring-boot:run
```

## 已通接口（B1+B2 冒烟 2026-11-16）

### B1 基础 + 请假
| 接口 | 说明 |
|---|---|
| POST /api/auth/login · GET /api/auth/me | JWT 登录 / 当前用户+perms |
| POST /api/leaves · GET /my · /{id}/approve · /{id}/withdraw | 请假主链 |
| GET /api/approvals/todo · /{id}/agree · /{id}/reject | 审批待办 |
| GET /api/notices · unread-count · POST read-all | 通知 |
| GET /api/audits | 审计（admin） |

### B2 报销 + 工单
| 接口 | 说明 |
|---|---|
| POST /api/expenses | 提交（**幂等键**、发票唯一、预算 hold、打标） |
| GET /api/expenses/my | 我的报销 |
| POST /{id}/approve · /pay · /void | 审批→打款→冲销 |
| POST /api/tickets | 建单（读 SLA 算 deadline） |
| POST /{id}/transition | 状态机（非法迁移 409） |
| GET /{id}/events | 事件轨迹 |

**已验证（脚本）**：
- 请假：提交→主管同意→`approved`；无 token 401
- 报销：提交→幂等重放同 id→发票重复 409→主管→财务→打款→冲销；预算 hold/释放
- 工单：triage→assigned（首响）→in_progress→resolved；`resolved→triage` 409；events 4 条
- 审计：`expense.submit/approve/pay/void`、`ticket.*` 全落库

## B3 已通（配置中心 + SLA 扫描）

| 接口 | 说明 |
|---|---|
| GET/POST /api/config/leave-types | 假种列表/保存（写= admin，403 否则） |
| GET/POST /api/config/sla-policies | SLA 四级策略可配 |
| GET/POST /api/config/approval-templates | 审批模板（新版本 version+1） |
| GET/POST /api/config/depts | 部门树 |
| POST /api/admin/sla-scan | **手动触发** SLA 扫描（admin） |
| （自动）@Scheduled 每 60s | 临期 remind + 破线 breach → escalated + 站内信 |

**已验证**：员工改配置 403；admin 改假种/SLA 落审计；过期工单扫描后 `escalated` + `breach` 事件 + 通知。

## B4 已通（审批加签转办 + 会议室）

| 接口 | 说明 |
|---|---|
| POST /api/approvals/{id}/transfer | **转办** {toUserId, comment} |
| POST /api/approvals/{id}/add-sign | **加签**（并入当前节点或签） |
| GET /api/rooms | 会议室列表 |
| POST /api/rooms/bookings | 预订（**时段冲突 409**） |
| POST /bookings/{id}/check-in · /cancel | 签到 / 取消（≥2h 免费，临期 late_cancel 信用-1） |
| POST /api/admin/room-release | **爽约释放**：过截止仍 booked → no_show 信用-1 |

**已验证**：转办后 HR 待办出现并同意；加签成功；预订冲突 409；签到/取消/爽约释放全过。

## B5 已通（列表五件套 + 导出 + 单测）

| 接口 | 说明 |
|---|---|
| GET /api/tickets?page&size&keyword&status | 工单分页搜索（title/or 单号） |
| GET /api/expenses?... · /api/leaves?... | 报销/请假同款分页 |
| GET /api/tickets/export · /api/expenses/export | **Excel xlsx**（POI，PK 头） |
| 单测 TicketFlowTest | 状态机合法/非法/终态 + PageQuery 边界 **4/4 绿** |

分页参数：`page`≥1、`size` 默认 20 上限 200；关键字空白忽略；导出单次最多 500 行。

## B6 已通（资产生命周期 + 离职 + 单测）

| 接口 | 说明 |
|---|---|
| GET /api/assets?status= | 台账列表 |
| POST /api/assets | 入库（SN 唯一 409） |
| POST /{id}/apply · /return · /repair · /lost | 领用/归还/报修/丢失 |
| POST /{id}/compensate · /dispose | 赔偿确认（it/finance/admin）→ 报废终态 |
| GET /{id}/flows | 流转流水 |
| POST /api/assets/offboard | 开离职单 |
| POST /offboard/{id}/complete | **资产未清零 409**；须 accountsDisabled=true |

**状态机**：`stock→assigned→{stock|repair|lost}→lost→lost_pending_compensate→dispose→disposed`（单测锁表）。

**单测**：`TicketFlowTest` 4 + `AssetFlowTest` 6 = **10/10 绿**（状态机合法/非法、请假天数余额、报销金额和）。

**已实测**：领用→报修→归还→再领→丢失→赔偿→报废全链；离职在名下 1 台时 409，归还后 done。

## B7 已通（AI 点位 + 配置链 + 采购 + 导入导出 + 看板）

| 接口 | 说明 |
|---|---|
| GET/POST /api/purchases · /{id}/quotes\|submit\|approve\|ordered\|received\|accepted | 采购 demand→比价≥2→审批→下单→验收 |
| POST /api/assets/import · /export · /users/import · /export · /expenses/import | Excel 导入导出（POI） |
| POST /assets/offboard/{id}/tick \| complete | 离职五门槛（资产/账号/权限/门禁/借款） |
| GET /api/stats/dashboard | 业务 8 指标 + 运维 6 指标 |
| GET/POST /api/admin/sys-config | AI 全局降级开关 ai.enabled |
| Python POST /ai/v1/triage\|draft\|assist\|switch | AI 三点位 + 降级 |

- 审批链：请假/报销读 sf_approval_template，配置改链不改代码；病假强制 HR（去 skipWhen）
- 前端工作台：员工端/主管端/管理端；采购 Tab；导入导出；AI 预填
- 单测：RuleFlowTest 14 + Ticket 4 + Asset 6 = 24/24 绿

**已实测**：采购全链 demand→accepted；病假链 manager→hr；personal 仅 manager；AI 降级 human_queue=true。

## B8 已通（甲方整改 P0/P1）

| 项 | 说明 |
|---|---|
| 配置中心前端 | /admin/config：假种/SLA/模板/部门/报销标准/预算/角色 |
| 报销标准 | sf_expense_policy；提交打 over_daily_limit / missing_invoice |
| 三端按钮 | 转办/加签/请假报销撤回 已挂工作台 |
| AI 六点位 | triage/draft/assist/**answer(citations)**/**qc_sample** + switch |
| 单一工单源 | 前台只认 Java；/tickets 等旧路由 redirect /office |
| 运维指标 | draftAdoptRate 真算；其余标「未接入」 |
| 一键初始化 | sql/init.sql；actuator /actuator/health=UP |
| 单号防撞 | NumberGen 启动 seed 当日 max |

**已实测**：配置保存 200；请假 L-2027 撤回 cancelled；A3 citations；A5 avg_score；health UP。

## B9 已通（完整性收口）

| 项 | 说明 |
|---|---|
| 用户管理迁 Java | GET/PUT /api/users；管理端 Users.vue 走 javaApi |
| 注册进业务库 | POST /api/auth/register（白名单）→ sf_user+employee，可登录 |
| AI 六点位齐 | + A6 /ai/v1/insight 周报聚类（只读） |
| 死文件清理 | Tickets/Assets/Transfer/TicketDetail 已删；命令面板指 /office |
| 看板 CSAT | sf_ticket.csatScore 均分；KB 标注见管理端 |
| 一键启动 | start-all.ps1（Java+Python+Vite 三窗口） |
| 架构 | Python 工单/资产/转接路由已下线；AI 写业务只走 bridge |

**已实测**：register newbie03 200→登录 200→用户列表可见；insight 3 段；csat 4.5。

## 前端测试

```powershell
cd D:\pythondata\mianshi-projects\smartflow\web
npm test          # vitest run：3 文件 / 15 用例
```

覆盖：路由收口与 admin 守卫 · java_token 存取 · Office/Config/Login 关键不变量（三端、AI 挂点、无 prompt、配置 9 类）。

## 部署说明

- **本机一键**：`powershell -ExecutionPolicy Bypass -File start-all.ps1`（Redis→MySQL 检查→Java→Python→Vite）
- **建库**：`mysql -uroot -p mianshi < sql/init.sql`（含 b1–b9 + 种子，32 表）
- **docker-compose**（`03-projects/zhiliu/deploy/`）：**仅 Python AI 栈**（FastAPI+前端+MySQL+Redis）；Java 业务用 start-all 本机跑

## B10 配置中心补齐（P0 主数据）

| Tab | 接口 |
|---|---|
| 会议室 | GET/POST /api/config/meeting-rooms |
| 资产分类 | GET/POST /api/config/asset-categories（sf_asset_category） |

配置中心现 9 类：假种/SLA/模板/部门/报销标准/预算/角色/会议室/资产分类。schema_b9.sql。

## 里程碑（面试口述）

| 期 | 交付 |
|---|---|
| B1 | JWT+RBAC+请假+统一审批+审计通知 |
| B2 | 报销(幂等/发票/预算)+工单状态机+SLA |
| B3 | 配置中心 CRUD + SLA 定时扫描 |
| B4 | 审批转办/加签 + 会议室冲突签到爽约 |
| B5 | 列表分页搜索 + Excel 导出 + 状态机单测 |
| B6 | 资产生命周期 + 离职门槛 + 规则单测 10 项 |
| B7 | AI 三点位+降级 / 配置驱动审批链 / 采购闭环 / 导入导出 / 三端工作台 / 24 单测 |
| B8 | 配置中心 UI+报销标准 / 转办加签撤回 / AI A3A5 / 单一工单源 / init.sql+health |
| B9 | 用户管理+注册迁 Java / A6 insight / 死文件清理 / start-all |

## 后续可选

- 前端 Vue 对接 / Postman 集合  
- 加签/转办更多策略、预算月结  
- K8s/CI 接入
