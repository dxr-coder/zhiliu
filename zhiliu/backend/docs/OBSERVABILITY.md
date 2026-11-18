# SmartFlow 可观测性

> 三根支柱：结构化日志（logger）→ Prometheus 指标（/metrics）→ Trace 链路（X-Trace-ID）。

---

## 1. 结构化日志

### 是什么

所有日志输出为一行一行的 JSON，可被 Loki / ELK / 阿云 SLS 等日志系统直接解析、过滤、检索。

```json
{"ts":"2026-10-21T10:00:00.123+00:00","level":"WARNING","module":"app.services.model_router",
 "msg":"模型档位拉闸冷却","tier":"fast","cooldown_s":60,"trace_id":"8a1fcc7a-..."}
```

### 关键字段

| 字段 | 含义 |
|------|------|
| `ts` | UTC 时间戳（毫秒精度） |
| `level` | DEBUG / INFO / WARNING / ERROR |
| `module` | 打日志的模块（`__name__`） |
| `msg` | 人类可读的消息 |
| `trace_id` | 自动注入（来自 ContextVar），一次请求的所有日志共享同一个 |
| 业务字段 | 调用方通过 `extra={"tier": "fast", ...}` 传的自定义字段 |

### 怎么用

```python
from app.logger import get_logger
log = get_logger(__name__)

log.info("用户登录成功", extra={"user_id": 123, "ip": "1.2.3.4"})
log.error("模型调用失败", extra={"tier": "smart", "err": str(e)[:200]})
```

### trace_id 为什么不用手动传

中间件 `TraceMiddleware` 在每个请求进来时执行 `set_trace_id(trace_id)`，写入 `ContextVar`。
`logging` 是同步调用，`log.info(...)` 执行时当前线程的 ContextVar 还是这个请求的，所以 `JSONFormatter` 里调 `get_trace_id()` 就能拿到——业务代码零改动。

### LOG_LEVEL 环境变量

默认 `INFO`。设 `LOG_LEVEL=DEBUG` 可以看到更细的日志（如"注册工具"）。

---

## 2. Prometheus 指标

### 数据流

```
你的应用在关键节点 inc()/observe()/set() 更新指标
  → Prometheus server 每 15s GET /metrics 拉取
  → 存进自己的时序数据库
  → Grafana 画图 / Prometheus 告警规则查询
```

### 指标清单

| 指标 | 类型 | 标签 | 用途 |
|------|------|------|------|
| `http_requests_total` | counter | method, path, status | QPS / 错误率 |
| `http_request_duration_seconds` | histogram | method, path | P99 延迟 |
| `llm_calls_total` | counter | tier, task_type, status | 各档模型成功率 |
| `llm_call_duration_seconds` | histogram | tier | LLM 延迟分布 |
| `circuit_breaker_state` | gauge | tier | 熔断器状态（0闭合/1打开） |
| `cache_lookups_total` | counter | kind, result | 缓存命中率 |
| `rate_limit_rejected_total` | counter | layer | 哪层限流在拦截 |

### 本地验证

```bash
# 1. 启动后端
# 2. 看指标（应能看到上面定义的所有指标名）
curl http://localhost:8000/metrics | grep -E "http_requests_total|llm_calls"

# 3. 发一条聊天请求，再 curl，看数字涨了
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8000/chat/stream?message=你好"
curl http://localhost:8000/metrics | grep 'http_requests_total{'
```

### /metrics 为什么不鉴权

Prometheus server 的 scrape 请求走集群内网（K8s 里通过 Service / headless Service 访问），不暴露公网。
加鉴权（Basic Auth / mTLS）反而会破坏 scrape——Prometheus 配置复杂度上升，而内网本身已经是信任边界。

**生产注意事项**：
- K8s 里用 NetworkPolicy 限制只有 Prometheus 的 Pod 能访问 `/metrics` 所在端口
- 如果必须走公网（如托管 Prometheus），用 Ingress 层 IP 白名单，不要在应用层加鉴权

### 基数（Cardinality）注意事项

`http_requests_total` 的 `path` 标签用的是 `request.url.path`（原始路径），不是路由模板。
如果路径里有 ID（如 `/tickets/123`），每个不同 ID 都会创建一个新的指标时间序列——这叫"基数爆炸"，会拖垮 Prometheus。

**当前处理**：客服系统的路径相对固定（`/chat/stream`、`/tickets` 等），风险可控。
如果后续加了 `/documents/{id}` 这类路径，应在埋点时归一化：
```python
# 归一化：把数字 ID 替换成 :id，避免基数爆炸
import re
path = re.sub(r'/\d+', '/:id', request.url.path)
```

---

## 3. Trace 链路追踪

### 数据流

```
请求进来 → middleware 生成 trace_id（UUID 前 12 位）
  → 双写：request.state（HTTP 层用）+ ContextVar（深层业务用）
  → 响应头回传 X-Trace-ID
  → 请求结束 → 写 MySQL request_traces 表（含 token 用量、分段耗时）
```

### 怎么查

```bash
# 前端报错时，让用户/前端提供 X-Trace-ID 响应头的值
curl http://localhost:8000/stats/trace/<trace_id> -H "Authorization: Bearer <admin_token>"
```

返回：
```json
{
  "trace_id": "8a1fcc7a-...",
  "method": "POST",
  "path": "/chat/stream",
  "status_code": 200,
  "total_time": 2.35,
  "input_tokens": 856,
  "output_tokens": 312,
  "agent_name": "faq",
  "sentiment": "neutral",
  "intent": "faq"
}
```

### token 用量怎么来的

`model_router.call_llm_router` 每次成功后读 OpenAI SDK 的 `completion.usage`（`prompt_tokens` / `completion_tokens`），调 `add_token_usage()` 累加进 ContextVar。
middleware 收尾时调 `get_token_usage()` 读出来，合并进 `log_entry`，由 `save_trace()` 写库。

**同一个请求内多次调 LLM（rewrite + answer）会自动累加**，`set_trace_id` 时清零。

### 分段耗时

`routes/chat.py` 给 rewrite / 检索 / 生成三段加了 `perf_counter`，挂到 `request.state.trace_info`，最终合并进 trace 记录的 `rewrite_time` / `retrieve_time` / `generate_time` 字段。

---

## 4. 告警规则

见 [`examples/alerts.yml`](examples/alerts.yml)。三条核心规则：

| 告警 | 条件 | 含义 |
|------|------|------|
| `HighRequestLatency` | P99 > 2s 持续 5 分钟 | 用户体感明显变慢 |
| `CircuitBreakerOpen` | 熔断器打开 > 1 分钟 | 某档模型持续不可用 |
| `HighErrorRate` | 5xx 错误率 > 5% 持续 5 分钟 | 系统性故障 |

**为什么没有实际部署 Alertmanager/Grafana**：
单机 demo 场景下，部署一套完整的 Prometheus + Alertmanager + Grafana 栈（3 个服务 + 存储）的运维成本 > 收益。
规则代码本身是真实可用的（PromQL 语法正确），保留设计思路比展示一个跑着的 Grafana 面板更有长期价值。
生产环境接入时，把 `examples/alerts.yml` 加进 Prometheus 的 `rule_files` 即可。
