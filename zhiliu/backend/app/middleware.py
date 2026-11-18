"""可观测性中间件"""
import os
import uuid
import time
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.background import BackgroundTask
from fastapi.responses import JSONResponse
from app.services.shared.rate_limiter import check_rate_limit
from app.services.auth.auth import decode_token
from app.services.observability.trace_context import set_trace_id
from app.logger import get_logger
from app.routes.chat.metrics import http_requests_total, http_request_duration, rate_limit_rejected_total
from app.services.observability.trace_context import get_token_usage

SLOW_THRESHOLD = 2.0  # 超过 2 秒标记慢请求
log = get_logger(__name__)

class TraceMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        # 1. 生成 trace_id，双写：request.state（HTTP 层用）+ ContextVar（深层业务用）
        trace_id = str(uuid.uuid4())[:12]
        request.state.trace_id = trace_id
        request.state.start_time = time.time()
        set_trace_id(trace_id)

        _metrics_start = time.time()

        # ---- 登录/注册 IP 限流（在 token 限流之前，因为这两个接口没有 token）----
        if request.url.path in ("/auth/login", "/auth/register") and request.method == "POST":
            from app.services.shared.rate_limiter import check_login_rate_limit
            client_ip = request.client.host if request.client else "unknown"
            # 只在明确配置了可信代理层数时才取 XFF，防止客户端伪造 IP 绕过限流
            forwarded = request.headers.get("x-forwarded-for")
            trusted_hops = int(os.getenv("TRUSTED_PROXY_HOPS", "0"))
            if forwarded and trusted_hops > 0:
                parts = [h.strip() for h in forwarded.split(",")]
                # 取倒数第 trusted_hops 个（nginx 追加的是最后一个）
                client_ip = parts[-trusted_hops] if len(parts) >= trusted_hops else parts[0]
            login_blocked, login_msg = check_login_rate_limit(client_ip)
            if login_blocked:
                response = JSONResponse(status_code=429, content={"detail": login_msg})
                response.headers["X-Trace-ID"] = trace_id
                http_requests_total.labels(request.method, request.url.path, "429").inc()
                rate_limit_rejected_total.labels("login_ip").inc()
                return response

        # ===== 限流检查=====
        auth_header = request.headers.get("Authorization")
        if auth_header:
            try:
                payload = decode_token(auth_header.replace("Bearer ", ""))
                user_id = payload["user_id"]
                role = payload.get("role") or "user"
                request.state.user_id = user_id  # 管理接口只走 require_role 不走 get_current_user，这里补上

                limit_result = check_rate_limit(user_id, role)
                if not limit_result["allowed"]:
                    http_requests_total.labels(request.method, request.url.path, "429").inc()
                    rate_limit_rejected_total.labels("user_token").inc()
                    return JSONResponse(
                        status_code=429,
                        content={"detail": "请求过于频繁，请稍后再试"},
                        headers={"X-RateLimit-Remaining": "0", "X-Trace-ID": trace_id},
                    )
                request.state.rate_remaining = limit_result["remaining"]
            except Exception as e:
                # 预期：token 解析失败（假token/过期），跳过限流让 auth 依赖报 401
                # 非预期：Redis/MySQL 故障也会走到这——打日志，否则限流静默失效都无人知晓
                # fail-open：限流挂了不挡正常登录（可用性 > 严格限流）
                log.warning("限流检查跳过", extra={
                    "err_type": type(e).__name__, "err": str(e)[:200],
                })

        # 2. 执行请求 —— 异常也必须记 trace（失败请求最需要排查）
        # 字段名要对齐 request_traces 表：status_code / total_time / error_msg
        try:
            response = await call_next(request)
        except Exception as e:
            elapsed = round(time.time() - request.state.start_time, 3)
            log_entry = {
                "trace_id": trace_id,
                "method": request.method,
                "path": request.url.path,
                "status_code": 500,
                "total_time": elapsed,
                "user_id": getattr(request.state, "user_id", None),
                "error_msg": str(e)[:200],
            }
            log.error("请求处理异常", extra={
                "path": request.url.path, "method": request.method,
                "status": 500, "elapsed": elapsed,
                "err": str(e)[:200],
            })
            from app.services.observability.trace_store import save_trace
            save_trace(log_entry)  # 异常路径没有 response，直接同步写
            http_requests_total.labels(request.method, request.url.path, "500").inc()
            http_request_duration.labels(request.method, request.url.path).observe(
                time.time() - _metrics_start)
            raise  # 继续抛给 FastAPI 转 500

        # 3. 耗时 + 慢请求标记
        total_time = time.time() - request.state.start_time
        log_entry = {
            "trace_id": trace_id,
            "method": request.method,
            "path": request.url.path,
            "status_code": response.status_code,
            "total_time": round(total_time, 3),
            "user_id": getattr(request.state, "user_id", None),
        }
        if total_time > SLOW_THRESHOLD:
            log_entry["slow"] = True  # 超时标签只打在控制台，不入库
        # 业务层挂上来的 agent/sentiment/intent，一起入账
        log_entry.update(getattr(request.state, "trace_info", {}))
        # 合并 token 用量（模型路由里 LLM 调用时累加的）
        usage = get_token_usage()
        log_entry["input_tokens"] = usage["input_tokens"]
        log_entry["output_tokens"] = usage["output_tokens"]

        # 业务层挂上来的 agent/sentiment/intent 等，一起记进日志
        extra = {"path": request.url.path, "method": request.method,
                 "status": response.status_code, "elapsed": round(total_time, 3)}
        if total_time > SLOW_THRESHOLD:
            extra["slow"] = True
        extra.update(getattr(request.state, "trace_info", {}))
        log.info("请求完成", extra=extra)

        # 4. 响应头回传 trace_id —— 前端报错时能提供这个 ID 来查
        response.headers["X-Trace-ID"] = trace_id

        # 5. 异步写 MySQL（不阻塞响应）
        from app.services.observability.trace_store import save_trace
        response.background = BackgroundTask(save_trace, log_entry)

        # 指标：请求计数 + 耗时（path 用原始路径，不用路由模板，避免基数爆炸）
        http_requests_total.labels(request.method, request.url.path, str(response.status_code)).inc()
        http_request_duration.labels(request.method, request.url.path).observe(
            time.time() - _metrics_start)

        return response
