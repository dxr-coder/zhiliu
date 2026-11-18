"""链路追踪上下文"""
import json
import threading
from contextvars import ContextVar

_trace_id_var: ContextVar[str] = ContextVar("trace_id", default="-")

def set_trace_id(trace_id: str):
    _trace_id_var.set(trace_id)

def get_trace_id() -> str:
    """任意业务代码取当前请求的 trace_id；不在请求上下文时返回 '-'"""
    return _trace_id_var.get()

def trace_log(stage: str, **fields):
    """
    打一条带 trace_id 的结构化日志。
    用法：trace_log("llm_call", elapsed=1.2, tier="fast")
    """
    entry = {"trace_id": get_trace_id(), "stage": stage, **fields}
    print(json.dumps(entry, ensure_ascii=False))

# ========== Token 用量累加 ==========
# 以 trace_id 为 key 的 dict（不用 ContextVar，原因见文件头注释）
# threading.Lock 保证多线程并发写安全
_token_usage_store: dict[str, dict] = {}
_token_usage_lock = threading.Lock()

def add_token_usage(input_tokens: int, output_tokens: int):
    """某次 LLM 调用后，把 usage 累加进当前请求的统计（按 trace_id 隔离）"""
    tid = get_trace_id()
    with _token_usage_lock:
        if tid not in _token_usage_store:
            _token_usage_store[tid] = {"input_tokens": 0, "output_tokens": 0}
        _token_usage_store[tid]["input_tokens"] += input_tokens or 0
        _token_usage_store[tid]["output_tokens"] += output_tokens or 0

def get_token_usage() -> dict:
    """取当前请求累计的 token 用量（middleware 收尾时调），取完即删防泄漏"""
    tid = get_trace_id()
    with _token_usage_lock:
        return _token_usage_store.pop(tid, {"input_tokens": 0, "output_tokens": 0})
