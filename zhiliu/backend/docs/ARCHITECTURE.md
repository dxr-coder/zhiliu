# SmartFlow 架构文档

> 时序图 + 状态机 + 关键设计决策。

---

## 1. 认证与 Token 管理

### 1.1 登录时序

```mermaid
sequenceDiagram
    participant FE as 前端
    participant MW as TraceMiddleware
    participant Auth as /auth/login
    participant DB as MySQL

    FE->>MW: POST /auth/login {username, password}
    MW->>MW: 生成 trace_id + IP 限流检查
    MW->>Auth: call_next()
    Auth->>DB: SELECT users WHERE username
    Auth->>Auth: bcrypt.verify(password, hash)
    Auth->>DB: SELECT r.name (role)
    Auth->>Auth: create_token(access) + create_token(refresh)
    Note over Auth: access: 30min, claims={user_id, username, role}<br/>refresh: 7d, claims={user_id, ver=token_version}
    Auth-->>FE: {access_token, refresh_token}
    MW-->>FE: X-Trace-ID + BackgroundTask(save_trace)
```

### 1.2 Refresh Token 轮换

```mermaid
sequenceDiagram
    participant FE as 前端
    participant Auth as /auth/refresh
    participant DB as MySQL

    FE->>Auth: POST /auth/refresh {refresh_token}
    Auth->>Auth: decode_token(refresh_token)
    Auth->>DB: SELECT token_version WHERE user_id
    Auth->>Auth: 比较 payload.ver == DB.token_version
    alt ver 匹配（正常刷新）
        Auth->>DB: UPDATE token_version += 1
        Auth-->>FE: {new access, new refresh}
    else ver 不匹配（refresh token 被复用）
        Auth->>DB: 强制吊销（token_version 已变）
        Auth-->>FE: 401 + 要求重新登录
    end
```

**设计要点**：
- access token 短期（30min）不查 DB，role 从 claims 读
- refresh token 轮换：每次刷新 `token_version += 1`，旧 refresh 立即失效
- 复用检测：被盗的 refresh token 再次使用时，ver 不匹配 → 强制所有会话重新登录

---

## 2. 聊天全链路

### 2.1 主时序

```mermaid
sequenceDiagram
    participant FE as 前端
    participant MW as TraceMiddleware
    participant Chat as /chat/stream
    participant Cache as Redis缓存
    participant Rewrite as QueryRewriter
    participant Graph as Agent图
    participant KB as kb_gate
    participant RAG as 混合检索
    participant MR as ModelRouter
    participant DB as MySQL
    participant LLM as MiMo API

    FE->>MW: GET /chat/stream?message=...
    MW->>MW: trace_id + rate_limit + token解析
    MW->>Chat: call_next()

    Chat->>Chat: is_conversation_owner (IDOR防护)

    rect rgb(230, 245, 255)
        Note over Chat,Cache: 缓存层
        Chat->>Cache: get_cached_answer(user_id, message)
        alt 精确命中
            Cache-->>Chat: cached answer
            Chat-->>FE: StreamingResponse (伪流式)
        else 未命中
            Chat->>Cache: get_semantic_cached(user_id, message)
            alt 语义命中（余弦>0.92）
                Cache-->>Chat: semantic answer
                Chat-->>FE: StreamingResponse
            else 未命中，继续
            end
        end
    end

    rect rgb(255, 245, 230)
        Note over Chat,Graph: 预处理
        Chat->>Chat: _build_history (超阈值自动摘要)
        Chat->>Chat: check_sensitive (敏感词拦截)
        Chat->>Rewrite: rewrite_query(message, history)
        Rewrite->>MR: call_llm_router(task=rewrite)
        MR->>LLM: fast档调用
        LLM-->>MR: rewritten query
        MR-->>Rewrite: rewritten text
    end

    rect rgb(230, 255, 230)
        Note over Graph,LLM: Agent 图
        Chat->>Graph: invoke_agent(history, rewritten)

        Graph->>Graph: sentiment_agent (情感分析)
        Graph->>KB: kb_gate (知识库先行)

        alt 知识库命中 (kb_missed=False)
            KB->>RAG: hybrid_search(rewritten)
            RAG->>RAG: BM25 + 向量 + RRF + rerank
            RAG-->>KB: top chunks + citations
            KB->>MR: call_llm_router(task=faq) with context
            MR->>LLM: smart档调用
            LLM-->>MR: answer with citations
            MR-->>Graph: response
            Graph-->>Chat: {response, agent_name="faq", source="kb"}
        else 知识库未命中 (kb_missed=True)
            Graph->>Graph: route_intent (意图分类)
            alt tool 意图
                Graph->>Graph: function_agent (7个工具)
                Graph-->>Chat: {response, agent_name="function", source="tool"}
            else 专科意图 (it/hr/finance)
                Graph->>MR: call_llm_router(task=answer)
                Graph-->>Chat: {response, agent_name="it", source="web"}
            end
        end
    end

    rect rgb(255, 230, 230)
        Note over Chat,DB: 落库 + 缓存
        Chat->>DB: save_message(user) + save_message(assistant)
        Chat->>Chat: mask_sensitive (脱敏)
        Chat->>Cache: set_cached_answer + set_semantic_cached
    end

    Chat-->>FE: SSE: metadata JSON + 逐字流式
    MW->>MW: 合并 token_usage + trace_info
    MW->>DB: BackgroundTask(save_trace)
    MW-->>FE: X-Trace-ID
```

### 2.2 关键设计决策

| 决策 | 说明 |
|------|------|
| 缓存 user_id 隔离 | key 格式 `chat:{user_id}:{question}`，防止跨用户缓存泄漏 |
| kb_gate 先行 | 知识库能接住就不走 LLM 路由，节省成本 |
| 混合检索四层漏斗 | BM25 关键词 → 向量语义 → RRF 名次融合 → cross-encoder 精排 |
| 伪流式 | 全量生成后逐字吐，首 token 延迟 = 全量生成时间（真流式需 async 全链路改造） |
| token 回填 | 每次 LLM 调用后 `add_token_usage()` 累加到 ContextVar，middleware 合并后写 DB |

---

## 3. 工单审批流

### 3.1 状态机

```mermaid
stateDiagram-v2
    [*] --> pending: 创建工单

    state "低优先级 (low/normal)" as low {
        pending --> processing: 自动通过
    }

    state "高优先级 (high)" as high {
        pending --> pending_L1: 创建审批链
        pending_L1 --> processing: agent 审批通过
        pending_L1 --> rejected: agent 驳回
    }

    state "紧急 (urgent)" as urgent {
        pending --> pending_L1: 创建审批链
        pending_L1 --> pending_L2: agent 通过
        pending_L1 --> rejected: agent 驳回
        pending_L2 --> processing: admin 通过
        pending_L2 --> rejected: admin 驳回
    }

    processing --> resolved: 客服标记解决
    resolved --> [*]
    rejected --> [*]
```

### 3.2 审批 + SLA 时序

```mermaid
sequenceDiagram
    participant User as 员工
    participant API as /tickets
    participant Approve as /tickets/{id}/approve
    participant Agent as 客服
    participant Admin as 管理员
    participant SLA as SLA引擎
    participant DB as MySQL

    User->>API: 创建工单 (priority=urgent)
    API->>DB: INSERT tickets
    API->>DB: INSERT ticket_approvals (level=1, role=agent, status=pending)
    API->>DB: INSERT ticket_approvals (level=2, role=admin, status=pending)
    API->>DB: INSERT audit_logs (ticket_created)

    Note over SLA,DB: --- SLA 后台线程每 60s 扫描 ---

    SLA->>DB: SELECT tickets WHERE status='processing'
    SLA->>SLA: 计算剩余时间 vs sla_deadline
    alt 剩余 < 25%
        SLA->>DB: INSERT sla_events (warning)
        SLA->>Redis: SET sla_warned:{id} (SETNX, 7d TTL)
    else 已超时
        SLA->>DB: UPDATE tickets SET priority=升级, escalated=1
        SLA->>DB: INSERT sla_events (escalated)
    end

    Note over Agent,DB: --- level 1 审批 ---

    Agent->>Approve: POST /tickets/6/approve {decision: "approved"}
    Approve->>DB: SELECT ticket_approvals WHERE ticket_id=6 AND level=1
    Approve->>Approve: 校验 approver_role 匹配当前用户 role
    Approve->>DB: UPDATE ticket_approvals SET status='approved'
    Approve->>DB: UPDATE tickets SET status='processing' (全部审批通过时)
    Approve->>DB: INSERT audit_logs (ticket_approved)

    Note over Admin,DB: --- level 2 审批 (urgent) ---

    Admin->>Approve: POST /tickets/6/approve {decision: "approved"}
    Approve->>DB: SELECT ticket_approvals WHERE ticket_id=6 AND level=2
    Approve->>DB: UPDATE ticket_approvals SET status='approved'
    Approve->>DB: UPDATE tickets SET status='processing'
```

**设计要点**：
- 审批链按 priority 预生成：high=1级，urgent=2级
- 每级审批校验 `approver_role` 匹配当前用户 role
- SLA 引擎：后台线程 60s 扫描，Redis SETNX 防重复预警
- 超时自动升优先级：low→normal→high→urgent

---

## 4. 模型熔断器

### 4.1 状态机

```mermaid
stateDiagram-v2
    [*] --> Closed

    state "闭合 (Closed)" as Closed: 正常放行
    state "打开 (Open)" as Open: 拒绝请求 60s
    state "半开 (Half-Open)" as HalfOpen: 放一个试探请求

    Closed --> Open: 窗口20次失败率>50%
    Open --> HalfOpen: 冷却60s到期
    HalfOpen --> Closed: 试探成功
    HalfOpen --> Open: 试探失败

    note right of Closed
        滑动窗口 deque(maxlen=20)
        记录最近20次调用结果(1/0)
        count(0)/len > 0.5 → 拉闸
    end note

    note right of Open
        _down_until = now + 60s
        is_healthy() 返回 False
        跳过该档，直接走 failover 链下一档
    end note

    note right of HalfOpen
        到期后清空旧账 (_health.clear())
        下一个请求自动当探子
        成功 → Closed
        失败 → 重新 Open
    end note
```

### 4.2 三档模型 failover 链

| 档位 | 任务类型 | 模型 | timeout | cost_weight | failover 链 |
|------|---------|------|---------|-------------|------------|
| fast | rewrite/intent/sentiment/title/summarize | v2.6-flash | 10s | 1 | fast→smart→backup |
| smart | answer/faq/agent/function_calling | v2.6-pro | 15s | 3 | smart→backup |
| backup | 兜底 | v2.5-flash | 20s | 2 | backup→smart |

**故障案例**：mimo-v2.5 下架期间，fast 档连续失败 → 滑动窗口失败率超 50% → 熔断器拉闸 60s → failover 链自动切到 smart 档（成本 1:3，可用性优先）→ 60s 后半开试探 → v2.5 恢复后自动回切。

---

## 5. 可观测性

### 5.1 请求链路追踪

```
请求进入
  │
  ├─ TraceMiddleware
  │    ├─ 生成 trace_id (uuid 前12位)
  │    ├─ 双写: request.state + ContextVar
  │    ├─ 限流检查 (Redis)
  │    ├─ call_next()
  │    ├─ 合并 token_usage (ContextVar)
  │    ├─ 合并 trace_info (request.state)
  │    ├─ 后台写 request_traces 表
  │    ├─ 控制台 JSON 日志 (logger.py)
  │    └─ Prometheus 指标 (/metrics)
  │
  └─ 任意深度业务代码
       ├─ get_logger(__name__).info(...)  ← 自动带 trace_id
       ├─ get_trace_id()                  ← 显式获取
       └─ add_token_usage(in, out)        ← LLM 调用后累加
```

### 5.2 Prometheus 指标

| 指标 | 类型 | 标签 | 用途 |
|------|------|------|------|
| http_requests_total | Counter | method, path, status | QPS + 错误率 |
| http_request_duration_seconds | Histogram | method, path | P95 延迟 |
| llm_calls_total | Counter | tier, task_type, status | LLM 成功率 |
| llm_call_duration_seconds | Histogram | tier | LLM 延迟 |
| circuit_breaker_state | Gauge | tier | 熔断器状态 (0闭合/1打开) |
| cache_lookups_total | Counter | kind, result | 缓存命中率 |
| rate_limit_rejected_total | Counter | layer | 限流触发次数 |

**设计决策**：
- `/metrics` 不鉴权：Prometheus 内部 scrape 标准做法，K8s 里只走集群内 Service
- 指标基数控制：path 用原始路径不用路由模板，避免 `/chat/{id}` 基数爆炸
- token 回填链路：OpenAI `completion.usage` → ContextVar → middleware 合并 → DB

---

## 6. 文件上传流式落盘

```
旧版（内存问题）:
  UploadFile.read() → 300MB 全量读入内存 → 写临时文件 → 检查大小

新版（峰值内存 1MB）:
  while chunk = file.read(1MB):
    written += 1MB
    if written > MAX_UPLOAD_SIZE:  → 413 + 清理临时文件
    tmp.write(chunk)
```

`UploadFile` 底层是 SpooledTemporaryFile（超过阈值落盘），但 `.read()` 会把整个文件读进内存。改成 1MB 分块循环写，峰值内存从 300MB 降到 1MB。

---

## 7. 关键数据流汇总

| 场景 | 数据流 |
|------|--------|
| 登录 | HTTP body → bcrypt.verify → JWT(claims+role) → 前端存 localStorage |
| 聊天 | message → 缓存 → 历史 → 敏感词 → 改写 → Agent图 → 脱敏 → 落库 → 缓存 → SSE |
| RAG | query → BM25(MySQL) + 向量(ChromaDB) → RRF融合 → cross-encoder精排 → top chunks |
| 工单 | 创建 → 审批链 → 客服处理 → SLA扫描 → CSAT评分 |
| 熔断 | LLM调用 → mark_result → 滑动窗口 → 失败率>50% → 拉闸 → failover |
| Trace | 请求 → trace_id → ContextVar → 业务层 get_logger/token_usage → middleware合并 → MySQL |
