"""
知流 — 企业智能服务台（AI）

功能：FastAPI 应用入口，注册所有路由，启动应用。
"""

from dotenv import load_dotenv

# 先加载 .env 里的环境变量（API key、数据库密码），后面所有 import 都能读到
load_dotenv()

from fastapi import FastAPI
from app.routes.auth.auth import router as auth_router
from app.routes.tickets.ticket import router as ticket_router
from app.routes.chat.transfer import router as transfer_router
from app.routes.knowledge_base.pdf import router as pdf_router
from app.routes.chat.chat import router as chat_router
from app.routes.chat.conversation import router as conversation_router
from app.routes.auth.user import router as user_router
from app.routes.knowledge_base.document import router as document_router
from app.routes.observability.stats import router as stats_router
from app.middleware import TraceMiddleware
from contextlib import asynccontextmanager
from app.services.tickets.sla_engine import start_sla_scheduler, stop_sla_scheduler
from app.routes.quality.feedback import router as feedback_router
from app.routes.tickets.audit import router as audit_router
from app.routes.quality.ab_test import router as ab_router
from app.routes.business.asset import router as asset_router
from app.routes.quality.csat import router as csat_router
from app.routes.quality.eval import router as eval_router
from app.routes.chat.metrics import router as metrics_router
from app.routes.ai.points import router as ai_points_router

from app.config import JWT_SECRET


@asynccontextmanager
async def lifespan(app: FastAPI):
    # fail-fast：JWT_SECRET 没配就拒绝启动，防止用默认密钥签出可伪造的 token
    if not JWT_SECRET:
        raise RuntimeError("JWT_SECRET 环境变量未配置，拒绝启动。请在 .env 中设置一个足够长的随机密钥。")
    start_sla_scheduler()
    yield
    stop_sla_scheduler()


# 架构：Java(:8081)=登录+业务唯一真相；本服务(:8000)=仅 AI（对话/RAG/评测/六点位）
# 工单/资产/转接等业务路由已下线，AI 写业务只走 services/bridge/java_bridge

app = FastAPI(
    title="知流",
    description="AI 智能客服系统",
    version="2.0",
    lifespan=lifespan,
)

# 中间件：所有路由最外层包一圈，负责 trace_id / 限流 / 日志
app.add_middleware(TraceMiddleware)
# 注册路由：每个 router 自带 prefix，最终路径 = prefix + 接口路径
app.include_router(auth_router)
# app.include_router(ticket_router)  # LEGACY 下线：业务在 Java
# app.include_router(transfer_router)  # LEGACY 下线：业务在 Java
app.include_router(pdf_router)
app.include_router(chat_router)
app.include_router(conversation_router)
app.include_router(user_router)
app.include_router(document_router)
app.include_router(stats_router)
app.include_router(feedback_router)
app.include_router(audit_router)
app.include_router(ab_router)
# app.include_router(asset_router)  # LEGACY 下线：业务在 Java
app.include_router(csat_router)
app.include_router(eval_router)
# /metrics 不鉴权，Prometheus server 内网 scrape 用（K8s 里走 Service 不暴露公网）
app.include_router(metrics_router)
# AI 六点位白名单（A1/A2/A4 + 降级开关）
app.include_router(ai_points_router)


@app.get("/health")
def health_check():
    return {"status": "ok"}


@app.get("/ready")
def ready_check():
    """就绪探针：DB + Redis 都通才算 ready（K8s readinessProbe 用这个）"""
    import os
    checks = {}
    # 检 MySQL
    try:
        from app.database import get_db
        conn = get_db()
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
        conn.close()
        checks["mysql"] = "ok"
    except Exception as e:
        checks["mysql"] = f"fail: {e}"
    # 检 Redis
    try:
        import redis as _redis
        r = _redis.Redis(
            host=os.getenv("REDIS_HOST", "localhost"),
            port=int(os.getenv("REDIS_PORT", 6379)),
            password=os.getenv("REDIS_PASSWORD") or None,
            socket_connect_timeout=2,
            protocol=2,  # 兼容老版本 Redis 服务端
        )
        r.ping()
        checks["redis"] = "ok"
    except Exception as e:
        checks["redis"] = f"fail: {e}"
    ok = all(v == "ok" for v in checks.values())
    return {"status": "ready" if ok else "not_ready", "checks": checks}
