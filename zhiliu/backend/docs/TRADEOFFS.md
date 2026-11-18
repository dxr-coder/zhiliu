# SmartFlow 技术取舍记录

> 每一项记录「现状 + 代价 + 为什么接受 + 什么时候改」。

---

## 1. 单副本部署

**现状**：K8s 只跑 1 个 replicas，SLA 引擎在进程内起线程扫描。

**代价**：
- SLA 线程重启后丢失扫描进度（从头扫一遍，SETNX 已预警的不会重复发）
- 无法水平扩容，压测上限 ~50 QPS
- 单点故障：进程挂了整个服务不可用

**为什么接受**：单副本演示够用，多副本需要分布式锁（Redis SETNX 做 SLA leader 选举）+ 共享 job 表（评测异步任务），复杂度不匹配当前规模。

**什么时候改**：真正上线前。方案：Redis 分布式锁选 SLA leader + 评测 job 落 Redis。

---

## 2. 伪流式 SSE

**现状**：`/chat/stream` 先全量生成回答，再 `time.sleep(0.01)` 逐字吐给前端。

**代价**：
- 首 token 延迟 = 全量生成时间（不是真流式）
- LLM 生成 3 秒 → 前端等 3 秒才开始看到第一个字
- 多占 1 个连接的时间

**为什么接受**：真流式需要 `async` 全链路改造（FastAPI async handler + async DB + async LLM client），当前代码库是同步的（`def` 路由 + pymysql），改造面太大。伪流式对用户体验的改善已经够用（不是"卡 3 秒再吐一大段"）。

**什么时候改**：v7。方案：路由改 `async def` + `AsyncOpenAI` + `aiomysql`，或者用 `sse-starlette` 库。

---

## 3. 语义缓存 O(N) 扫描

**现状**：`get_semantic_cached` 用 `scan_iter` 扫描 `semantic:{user_id}:*` 的所有 key，逐个算余弦相似度。

**代价**：单用户缓存条目多时（>1000），每次查询 O(N) 遍历。

**为什么接受**：已优化为按 user_id 前缀扫描，N 从全库缩到单用户。单用户日常对话量 <1000，1ms 内完成。

**什么时候改**：用户量上来后。方案：向量索引（HNSW / Annoy）替代线性扫描，或者上 Redis Stack 的 RediSearch 向量能力。

---

## 4. JWT role claim 30 分钟过渡期

**现状**：新签发的 JWT 在 claims 里带 `role` 字段，`require_role` 优先读 claims。旧 token（无 role）fallback 查 DB。

**代价**：旧 token 过渡期内 `require_role` 仍可能查 1 次 DB（30 分钟内自然过期）。

**为什么接受**：不能强制踢掉所有在线用户。30 分钟后旧 token 全部过期，claims 路径 100% 生效。

**迁移策略**：新字段渐进式上线，旧数据兼容过渡，不搞一刀切。

---

## 5. 内存 _GEN_JOBS 表

**现状**：RAG 评测异步任务的 job 状态存在进程内存 `_GEN_JOBS = {}` 字典里，1 小时自动清理。

**代价**：
- 进程重启丢失任务状态（前端显示"任务不存在"）
- 多副本时各副本状态不一致

**为什么接受**：评测是低频操作（开发/测试用），单副本够用。job 表落 Redis 需要额外的序列化 + TTL 管理，收益不大。

**什么时候改**：评测频率上来或多副本时。方案：Redis hash + TTL。

---

## 6. eval.py 863 行不拆

**现状**：`app/services/eval.py` 是 863 行的评测单文件，包含金标加载、检索评测、生成评审、统计显著性。

**代价**：可读性差，新人上手难。

**为什么接受**：评测功能还在演进中，此时拆文件会产生大量合并冲突。评测逻辑高度耦合（指标计算共享中间结果），强行拆反而增加理解成本。

**什么时候改**：评测功能稳定后。拆分方向：`eval_retrieval.py` + `eval_generation.py` + `eval_stats.py`。

---

## 7. 剩余 35 处 db_session 未迁移

**现状**：`database.py` 提供了 `db_session()` contextmanager，但只有 conversation/ticket_approval/stats 三处迁移了，其余 35 处仍用 `get_db()` + 手动 try/finally。

**代价**：代码风格不统一，样板代码多。

**为什么接受**：只做「外科手术式」优化，不动无关代码。35 处迁移是纯样板替换，无行为变更，单独一个 commit 做完就行。

**什么时候改**：下个迭代顺手做，或者等有 bug 需要 touch 那些文件时顺手改。

---

## 8. 指标基数控制的妥协

**现状**：`http_requests_total` 用 `request.url.path` 原始路径做标签，不用路由模板。

**代价**：`/conversations/123` 和 `/conversations/456` 是两个不同的标签值，基数随对话量增长。

**为什么接受**：Prometheus 默认 max_series=100k，单用户量级不会打爆。路由模板匹配需要额外的中间件（Starlette 的 `route.path` 在 `call_next` 后才可用）。

**什么时候改**：QPS >100 时。方案：在 middleware 里用正则把数字 ID 替换成 `{id}`。

---

## 9. 缓存 TTL 1 小时的泄漏窗口

**现状**：精确缓存和语义缓存 TTL 都是 3600 秒。如果管理员更新了知识库文档，已缓存的旧答案最多存活 1 小时。

**代价**：知识库更新后有短暂的「旧答案」窗口。

**为什么接受**：知识库更新是低频操作（每周几次）。主动失效需要 hook 到文档上传流程，复杂度不匹配收益。

**什么时候改**：知识库更新频繁时。方案：文档上传时 `scan_iter("chat:*")` 批量删除相关缓存。

---

## 10. 登录 IP 限流的代理信任

**现状**：中间件取 `X-Forwarded-For` 第一个 IP 做限流。注释标明「信任代理时才安全」。

**代价**：如果服务直接暴露公网（没有 nginx/ingress），攻击者可以伪造 `X-Forwarded-For` 绕过 IP 限流。

**为什么接受**：K8s 部署里 ingress-nginx 会正确设置 `X-Forwarded-For`，伪造的头会被 ingress 覆盖。本地开发没有代理，`X-Forwarded-For` 不存在，fallback 到 `request.client.host`。

**什么时候改**：如果要支持多层代理（nginx → envoy → app），需要用 `ProxyHeadersMiddleware` 或配置可信代理列表。
