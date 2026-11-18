"""Prometheus 指标暴露"""
from fastapi import APIRouter, Response
from prometheus_client import (
    Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST,
)

router = APIRouter(tags=["可观测性"])

# ========== 1. HTTP 层（middleware 里埋点） ==========

http_requests_total = Counter(
    "http_requests_total", "HTTP 请求总数",
    ["method", "path", "status"],
)
http_request_duration = Histogram(
    "http_request_duration_seconds", "HTTP 请求耗时（秒）",
    ["method", "path"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0),  # 常用桶，覆盖客服场景
)

# ========== 2. LLM 调用（model_router 里埋点） ==========

llm_calls_total = Counter(
    "llm_calls_total", "LLM 调用总数",
    ["tier", "task_type", "status"],   # status: ok / error / timeout
)
llm_call_duration = Histogram(
    "llm_call_duration_seconds", "LLM 调用耗时（秒）",
    ["tier"],
    buckets=(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0),  # LLM 延迟大，桶放宽
)

# ========== 3. 熔断器（model_router.mark_result 里埋点） ==========

circuit_breaker_state = Gauge(
    "circuit_breaker_state", "熔断器状态：0=闭合(健康) 1=打开(拉闸)",
    ["tier"],
)

# ========== 4. 缓存（cache.py 里埋点） ==========

cache_lookups_total = Counter(
    "cache_lookups_total", "缓存查询总数",
    ["kind", "result"],   # kind: exact/semantic; result: hit/miss
)

# ========== 5. 限流（middleware / rate_limiter 里埋点） ==========

rate_limit_rejected_total = Counter(
    "rate_limit_rejected_total", "限流拦截总数",
    ["layer"],   # layer: login_ip / user_token
)

@router.get("/metrics")
def metrics():
    """
    Prometheus scrape 入口。返回所有指标的最新快照（纯文本格式）。
    不鉴权：内部 scrape 标准做法，见模块 docstring。
    """
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST,
    )
