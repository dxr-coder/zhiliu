"""结构化日志"""
import json
import logging
import os
import sys
from datetime import datetime, timezone

from app.services.observability.trace_context import get_trace_id

# 额外字段白名单：Python logging 的 LogRecord 会把 extra 键直接挂到记录上，
# 但内置 formatter 不认识；这里收集出来拼进 JSON。
# 用 set 而非 dict，因为只需要知道"哪些 key 是业务额外传的"。
_STD_ATTRS = frozenset(
    logging.LogRecord("", 0, "", 0, "", (), None).__dict__.keys()
) | {"message", "asctime", "taskName"}

class JSONFormatter(logging.Formatter):
    """把 LogRecord 序列化成一行 JSON，自动注入 trace_id"""

    def format(self, record: logging.LogRecord) -> str:
        # 1. 基础字段
        log_entry = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc)
                          .isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "module": record.name,
            "msg": record.getMessage(),
        }

        # 2. 自动注入当前请求的 trace_id（不在请求上下文时返回 '-'）
        trace_id = get_trace_id()
        if trace_id and trace_id != "-":
            log_entry["trace_id"] = trace_id

        # 3. 收集业务通过 extra={} 传的自定义字段（排除 LogRecord 内置属性）
        for key, value in record.__dict__.items():
            if key not in _STD_ATTRS and not key.startswith("_"):
                log_entry[key] = value

        # 4. 异常信息（log.exception 或 exc_info=True 时自动带上）
        if record.exc_info and record.exc_info[0] is not None:
            log_entry["exc"] = self.formatException(record.exc_info)

        return json.dumps(log_entry, ensure_ascii=False, default=str)

_configured = False

def _ensure_config():
    """首次使用时配置 root handler（幂等，多模块并发导入安全）"""
    global _configured
    if _configured:
        return
    root = logging.getLogger()
    # 清掉可能已有的 handler（uvicorn 默认有），避免重复输出
    for h in list(root.handlers):
        root.removeHandler(h)
    # Windows 控制台默认 GBK 会把中文日志打花；强制 UTF-8（Linux/Docker 无影响）
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JSONFormatter())
    root.addHandler(handler)
    root.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())
    _configured = True

def get_logger(name: str) -> logging.Logger:
    """获取带 JSON formatter 的 logger。传 __name__ 即可，模块名自动出现在日志里"""
    _ensure_config()
    return logging.getLogger(name)
