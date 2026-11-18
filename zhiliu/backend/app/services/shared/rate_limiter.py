"""限流与配额服务"""
import time
import redis
from app.config import REDIS_HOST, REDIS_PORT, REDIS_DB, REDIS_PASSWORD, RATE_LIMIT_PER_MINUTE, DAILY_QUOTA

# 短超时：Redis 挂了快速失败，避免拖死线程池（可用性 > 严格限流）
r = redis.Redis(
    host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB,
    password=REDIS_PASSWORD or None, decode_responses=True,
    protocol=2,
    socket_timeout=0.5,
    socket_connect_timeout=0.5,
)

UNLIMITED_ROLES = ("admin", "agent")  # 不限次数的角色

def check_rate_limit(user_id: int, role: str) -> dict:
    """
        检查用户是否超过限流/配额。

        返回：
            {"allowed": True, "remaining": 8}   → 正常，剩余8次
            {"allowed": False, "remaining": 0}  → 超限
        """
    # admin / customer_service 完全不限流
    if role in UNLIMITED_ROLES:
        return {"allowed": True, "remaining": 999}

    try:
        return _rate_limit_inner(user_id, role)
    except Exception:
        # Redis 故障 fail-open，不挡业务
        return {"allowed": True, "remaining": 999, "fail_open": True}

def _rate_limit_inner(user_id: int, role: str) -> dict:
    now = time.time()
    minute_key = f"rate:{user_id}:{int(now // 60)}"  # 每分钟一个key
    day_key = f"quota:{user_id}:{time.strftime('%Y%m%d')}"  # 每天一个key

    # 1. 分钟限流
    minute_count = r.incr(minute_key)
    r.expire(minute_key, 60)  # 60秒后自动过期
    if minute_count > RATE_LIMIT_PER_MINUTE:
        return {"allowed": False, "remaining": 0, "reason": "minute"}

    # 2. 日配额
    day_count = r.incr(day_key)
    r.expire(day_key, 86400)  # 24小时后过期
    if day_count > DAILY_QUOTA:
        return {"allowed": False, "remaining": 0, "reason": "daily"}

    remaining = min(
        RATE_LIMIT_PER_MINUTE - minute_count,
        DAILY_QUOTA - day_count
    )

    return {"allowed": True, "remaining": max(remaining, 0)}

LOGIN_LIMIT_PER_MINUTE = 5  # 每 IP 每分钟最多登录尝试
LOGIN_DAILY_LIMIT = 20  # 每 IP 每天最多登录尝试

def check_login_rate_limit(ip: str) -> tuple[bool, str]:
    """
    登录/注册 IP 限流：防撞库暴破。
    返回 (是否拦截, 提示语)。
    """
    import time
    try:
        return _login_rate_inner(ip)
    except Exception:
        return False, ""  # fail-open：Redis 挂了不拦登录

def _login_rate_inner(ip: str) -> tuple:
    import time
    minute_bucket = int(time.time() // 60)
    day_bucket = time.strftime("%Y%m%d")

    minute_key = f"login:{ip}:{minute_bucket}"
    day_key = f"login:{ip}:daily:{day_bucket}"

    minute_count = r.incr(minute_key)
    if minute_count == 1:
        r.expire(minute_key, 120)

    day_count = r.incr(day_key)
    if day_count == 1:
        r.expire(day_key, 86400)

    if minute_count > LOGIN_LIMIT_PER_MINUTE:
        return True, "登录过于频繁，请稍后再试"
    if day_count > LOGIN_DAILY_LIMIT:
        return True, "今日登录次数已达上限，请明天再试"
    return False, ""
