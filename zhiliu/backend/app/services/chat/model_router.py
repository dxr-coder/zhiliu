"""模型路由"""
import time
from collections import deque
from threading import RLock

from openai import OpenAI

from app.config import (
    ROUTER_FAST_MODEL,
    ROUTER_SMART_MODEL,
    ROUTER_BACKUP_MODEL,
    ROUTER_FAST_BASE_URL,
    ROUTER_SMART_BASE_URL,
    ROUTER_BACKUP_BASE_URL,
    ROUTER_FAST_API_KEY,
    ROUTER_SMART_API_KEY,
    ROUTER_BACKUP_API_KEY,
)
from app.logger import get_logger
from app.routes.chat.metrics import llm_calls_total, llm_call_duration, circuit_breaker_state
from app.services.observability.trace_context import add_token_usage

# ========== 任务类型 → 档位映射 ==========
# fast：短平快任务（标题/改写/意图分类/情感/JSON抽取/摘要）
# smart：主回答、长文、工具调用（默认档）
TASK_TIER = {
    "title": "fast",
    "rewrite": "fast",
    "intent": "fast",
    "sentiment": "fast",
    "json_extract": "fast",
    "summarize": "fast",
    "chitchat": "fast",
    "answer": "smart",
    "faq": "smart",
    "agent": "smart",
    "function_calling": "smart",
    "admin": "smart",
}

# ========== 档位注册表 ==========
MODELS = {
    "fast": {
        "model": ROUTER_FAST_MODEL,
        "base_url": ROUTER_FAST_BASE_URL,
        "api_key": ROUTER_FAST_API_KEY,
        "timeout": 10,
        "cost_weight": 1,  # 成本权重：fast:smart = 1:3
    },
    "smart": {
        "model": ROUTER_SMART_MODEL,
        "base_url": ROUTER_SMART_BASE_URL,
        "api_key": ROUTER_SMART_API_KEY,
        "timeout": 15,
        "cost_weight": 3,
    },
    "backup": {
        "model": ROUTER_BACKUP_MODEL,
        "base_url": ROUTER_BACKUP_BASE_URL,
        "api_key": ROUTER_BACKUP_API_KEY,
        "timeout": 20,
        "cost_weight": 2,
    },
}

# ========== failover 链 ==========
# fast 挂了升到 smart（宁可贵一点也要答上）；smart 挂了走 backup
FAILOVER_CHAIN = {
    "fast": ["fast", "smart", "backup"],
    "smart": ["smart", "backup"],
    "backup": ["backup", "smart"],
}

# ========== 健康检查：滑动窗口 + 熔断器 ==========
WINDOW_SIZE = 20  # 每档记录最近 20 次调用结果
FAIL_THRESHOLD = 0.5  # 窗口内失败率 > 50% 判定不健康
COOLDOWN_SECONDS = 60  # 判不健康后冷却 60 秒，期间直接跳过该档

_health: dict[str, deque] = {tier: deque(maxlen=WINDOW_SIZE) for tier in MODELS}
_down_until: dict[str, float] = {tier: 0.0 for tier in MODELS}  # 拉闸到几点
_cooldown_seen: dict[str, float] = {tier: 0.0 for tier in MODELS}  # 已处理过的拉闸时刻（防反复清账）
_clients: dict[str, OpenAI] = {}
_lock = RLock()
log = get_logger(__name__)

def get_client(tier: str = "smart") -> OpenAI:
    """获取指定档位的 OpenAI client（懒创建 + 缓存）"""
    with _lock:
        if tier not in _clients:
            cfg = MODELS[tier]
            _clients[tier] = OpenAI(api_key=cfg["api_key"], base_url=cfg["base_url"])
        return _clients[tier]

def is_healthy(tier: str) -> bool:
    """该档位当前是否可用：冷却期未过 → 不健康；到期 → 清旧账，给试探机会"""
    with _lock:
        now = time.time()
        # ① 还在拉闸期内，别试
        if now < _down_until[tier]:
            return False
        # ② 刚到期（这次拉闸还没处理过）→ 清掉旧账，下一个请求自动当探子
        if _down_until[tier] > _cooldown_seen[tier]:
            _health[tier].clear()
            _cooldown_seen[tier] = _down_until[tier]
        # ③ 查账：空账本没得查先信任；有账按失败率判
        window = list(_health[tier])
        if not window:
            return True
        fail_rate = window.count(0) / len(window)
        return fail_rate <= FAIL_THRESHOLD

def mark_result(tier: str, ok: bool):
    """记录一次调用结果；失败率超标时启动冷却"""
    with _lock:
        _health[tier].append(1 if ok else 0)
        healthy = is_healthy(tier)
        circuit_breaker_state.labels(tier).set(0 if healthy else 1)  # 0闭合 1打开
        if not healthy:
            _down_until[tier] = time.time() + COOLDOWN_SECONDS
            log.warning("模型档位拉闸冷却", extra={
                "tier": tier, "cooldown_s": COOLDOWN_SECONDS,
            })

def route(task_type: str = "answer") -> str:
    """按任务类型返回可用档位（首选挂了返回链上第一个健康档）"""
    preferred = TASK_TIER.get(task_type, "smart")
    for tier in FAILOVER_CHAIN[preferred]:
        if is_healthy(tier):
            return tier
    # 全部不健康：返回首选档让它再试（总比不发请求强）
    return preferred

def _chain_for(task_type: str) -> list[str]:
    return FAILOVER_CHAIN[TASK_TIER.get(task_type, "smart")]

def call_llm_router(
        messages: list,
        task_type: str = "answer",
        max_tokens: int = 500,
        fallback: str = None,
        max_retries: int = 2,
        timeout: int = None,
) -> str:
    """
    带分档 + failover 的 LLM 调用。
    首选档重试 max_retries+1 次；切到 failover 档后每档只试 1 次（快速失败）。
    """
    chain = _chain_for(task_type)
    for idx, tier in enumerate(chain):
        if not is_healthy(tier):
            continue
        attempts = (max_retries + 1) if idx == 0 else 1
        for attempt in range(attempts):
            try:
                _t_llm = time.time()
                completion = get_client(tier).chat.completions.create(
                    model=MODELS[tier]["model"],
                    messages=messages,
                    max_tokens=max_tokens,
                    timeout=timeout or MODELS[tier]["timeout"],
                )
                mark_result(tier, True)
                llm_calls_total.labels(tier, task_type, "ok").inc()
                llm_call_duration.labels(tier).observe(time.time() - _t_llm)
                # 把本次 LLM 的 token 用量累加进请求上下文
                if completion.usage:
                    add_token_usage(completion.usage.prompt_tokens,
                                    completion.usage.completion_tokens)
                return completion.choices[0].message.content

            except Exception as e:
                mark_result(tier, False)
                llm_calls_total.labels(tier, task_type, "error").inc()
                log.error("模型调用失败", extra={
                    "tier": tier, "task": task_type,
                    "attempt": attempt + 1, "err": str(e)[:200],
                })
                if attempt < attempts - 1:
                    time.sleep(1)
                    continue  # 还有机会，重试当前档
                break  # 本档机会用完，换 failover 下一档

    return fallback or "抱歉，系统暂时繁忙，请稍后再试。"

def chat_stream_router(messages: list, task_type: str = "answer"):
    """
    流式分档调用。开始输出前失败 → 自动切下一档；
    已吐出部分内容后失败 → 直接停（切档重播会导致内容重复）。
    """
    chain = _chain_for(task_type)
    for tier in chain:
        if not is_healthy(tier):
            continue
        got_any = False
        try:
            resp = get_client(tier).chat.completions.create(
                model=MODELS[tier]["model"],
                messages=messages,
                stream=True,
                timeout=MODELS[tier]["timeout"],
            )
            for chunk in resp:
                if chunk.choices and chunk.choices[0].delta.content:
                    got_any = True
                    yield chunk.choices[0].delta.content
            mark_result(tier, True)
            return
        except Exception as e:
            mark_result(tier, False)
            log.error("流式调用失败", extra={
                "tier": tier, "task": task_type, "err": str(e)[:200],
            })
            if got_any:
                return  # 已输出部分内容，不切档
            continue  # 未输出任何内容，尝试下一档

    yield "抱歉，系统暂时繁忙，请稍后再试。"

def call_llm_with_tools_router(
        messages: list,
        tools: list,
        task_type: str = "function_calling",
        max_tokens: int = 500,
        timeout: int = 15,
):
    """
    带工具调用的请求，返回原始 completion 对象
    （调用方要读 choices[0].message.tool_calls，不能只拿文本）。
    全档失败抛 RuntimeError，由上层 agent 的 try/except 兜底。
    """
    chain = _chain_for(task_type)
    last_err = None
    for tier in chain:
        if not is_healthy(tier):
            continue
        try:
            completion = get_client(tier).chat.completions.create(
                model=MODELS[tier]["model"],
                messages=messages,
                tools=tools,
                max_tokens=max_tokens,
                timeout=timeout or MODELS[tier]["timeout"],
            )
            mark_result(tier, True)
            return completion
        except Exception as e:
            mark_result(tier, False)
            last_err = e
            log.error("工具调用失败", extra={"tier": tier, "err": str(e)[:200]})

    raise RuntimeError(f"所有模型档位均不可用: {last_err}")

def router_status() -> dict:
    """各档位当前状态（调试 / 以后接管理后台监控用）"""
    with _lock:
        return {
            tier: {
                "model": MODELS[tier]["model"],
                "healthy": is_healthy(tier),
                "window": list(_health[tier]),
                "cooldown_left": max(0, round(_down_until[tier] - time.time())),
            }
            for tier in MODELS
        }
