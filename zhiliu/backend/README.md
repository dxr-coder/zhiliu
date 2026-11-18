# 知流 · AI 服务（Python）

企业内部 AI 智能客服系统。RAG 知识库问答、多 Agent 编排、Function Calling、工单 SLA 管理、全链路可观测。

## 功能

- **智能问答** — 基于企业知识库的 RAG 检索增强生成，混合检索（BM25 + 向量 + RRF + cross-encoder 精排）
- **多 Agent 编排** — LangGraph 状态机，意图分类 → 知识库先行 → 专科路由（HR / IT / 财务 / FAQ）
- **Function Calling** — 7 个内置工具：查假期余额、发起请假、查报销、预订会议室、查设备等
- **工单系统** — 创建 → 多级审批 → SLA 自动升级 → CSAT 满意度评分
- **模型路由** — 三档模型（fast / smart / backup），熔断器 + failover 链
- **缓存** — 精确缓存 + 语义缓存（L2 归一化 + 余弦相似度）
- **可观测** — Prometheus 指标、JSON 结构化日志、全链路 Trace ID
- **安全** — JWT 双 token + refresh 轮换、RBAC、IDOR 防护、IP 限流、敏感词拦截

## 架构

```
Vue 3 SPA ──SSE──▶ FastAPI
                     │
                     ├─ TraceMiddleware（trace_id / 限流 / 日志 / 指标）
                     ├─ 缓存层（精确 → 语义）
                     ├─ 敏感词拦截 → 查询改写
                     ├─ LangGraph Agent 图
                     │    ├─ sentiment → kb_gate → router
                     │    ├─ kb 命中 → 混合检索 → 生成（带引用）
                     │    ├─ 工具意图 → Function Agent（7 工具）
                     │    └─ 专科意图 → HR / IT / Finance Agent
                     ├─ 脱敏 → 落库 → 写缓存 → SSE 流式
                     └─ Model Router（三档 failover + 熔断器）
                          │
                     ┌────┴────┬──────────┐
                     ▼         ▼          ▼
                   MySQL    ChromaDB     Redis
                  (28表)     (向量)    (缓存/限流)
                                        │
                                   小米 MiMo API
```

## 快速启动

```bash
# 环境变量
cp .env.example .env   # 填 JWT_SECRET / DB 密码 / XIAOMI_API_KEY

# 启动依赖
docker compose up -d mysql redis

# 建表 + 种子数据
python scripts/seed_demo.py

# 启动后端
uvicorn app.main:app --reload --port 8000

# 启动前端
cd ../zhiliu/web && npm install && npm run dev
```

测试账号：`dxr / 123456`（管理员）、`testuser / 123456`（普通员工）。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | FastAPI · Python 3.11 |
| 前端 | Vue 3 · Vite · Pinia |
| 数据库 | MySQL 8 · DBUtils 连接池 |
| 缓存 | Redis |
| 向量库 | ChromaDB |
| LLM | 小米 MiMo (v2.6-flash / v2.6-pro) |
| 检索 | BM25 + 向量 + RRF + cross-encoder |
| 部署 | Docker · K8s (kind) · GitHub Actions |
| 可观测 | Prometheus · JSON 日志 · Trace ID |

## 项目结构

```
zhiliu/
├── app/
│   ├── main.py              # 入口 + lifespan
│   ├── middleware.py         # TraceMiddleware
│   ├── deps.py              # 认证依赖
│   ├── database.py          # DBUtils 连接池
│   ├── logger.py            # JSON 结构化日志
│   ├── routes/              # HTTP 路由
│   │   ├── auth.py          # 登录/注册/refresh
│   │   ├── chat.py          # 聊天主入口
│   │   ├── conversation.py  # 对话管理
│   │   ├── ticket.py        # 工单
│   │   ├── metrics.py       # /metrics
│   │   └── ...
│   └── services/            # 业务逻辑
│       ├── auth.py          # JWT 签发/验证/轮换
│       ├── cache.py         # 精确 + 语义缓存
│       ├── graph.py         # LangGraph Agent 图
│       ├── model_router.py  # 三档路由 + 熔断器
│       ├── retriever.py     # 混合检索
│       ├── rrf.py           # RRF 融合
│       ├── tool_registry.py # Function Calling 工具
│       ├── eval.py          # RAG 评测
│       └── ...
├── tests/                   # pytest（45 个用例，零外部依赖）
├── scripts/                 # 种子数据 + 工具脚本
├── docs/                    # 架构 / ERD / 可观测 / 取舍记录
└── deploy/                  # K8s manifests
```

## 文档

| 文档 | 内容 |
|------|------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 时序图、状态机、关键设计决策 |
| [docs/ERD.md](docs/ERD.md) | 28 张表全景 + 字段说明 |
| [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md) | 日志 / 指标 / Trace 三根支柱 |
| [docs/TRADEOFFS.md](docs/TRADEOFFS.md) | 10 项技术取舍记录 |
| [docs/STRESS_TEST.md](docs/STRESS_TEST.md) | 压力测试报告 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 版本演进路线 |

## 测试

```bash
cd tests && python -m pytest -q
```

45 个用例，覆盖：JWT refresh 轮换 / 限流边界 / 熔断三态 / RRF 公式 / 审批权限矩阵 / 缓存隔离。不需要 MySQL / Redis。

## License

MIT
