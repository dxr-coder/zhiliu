"""
限流与配额测试（锁 v6 Phase 1.3）

锁的行为：
  ① check_rate_limit：分钟限流边界、日配额边界、remaining 计算、admin 放行
  ② check_login_rate_limit：每 IP 分钟/天两次拦截、IP 之间互不影响
数据流：调用 → fakeredis INCR 计数 → 超限返回 allowed=False + reason
边界处理：阈值用 monkeypatch 临时改小（3/4），不等真实 10/50 跑满，也不受真实时间桶影响。
"""
from app.services.shared.rate_limiter import check_rate_limit, check_login_rate_limit


# ========== 用户级限流 ==========

def test_minute_boundary(fake_redis, monkeypatch):
    """1..N 次放行，第 N+1 次以 reason=minute 拦下"""
    monkeypatch.setattr("app.services.shared.rate_limiter.RATE_LIMIT_PER_MINUTE", 3)

    results = [check_rate_limit(1, "user") for _ in range(3)]
    assert all(r["allowed"] for r in results)

    blocked = check_rate_limit(1, "user")
    assert blocked["allowed"] is False
    assert blocked["reason"] == "minute"


def test_daily_quota_boundary(fake_redis, monkeypatch):
    """分钟限额抬高到够用，验证第 N+1 次被日配额拦下（reason=daily）"""
    monkeypatch.setattr("app.services.shared.rate_limiter.RATE_LIMIT_PER_MINUTE", 10 ** 9)
    monkeypatch.setattr("app.services.shared.rate_limiter.DAILY_QUOTA", 4)

    results = [check_rate_limit(1, "user") for _ in range(4)]
    assert all(r["allowed"] for r in results)

    blocked = check_rate_limit(1, "user")
    assert blocked["allowed"] is False
    assert blocked["reason"] == "daily"


def test_remaining_is_min_of_two_buckets(fake_redis, monkeypatch):
    """remaining = min(分钟剩余, 日剩余)，取较小的先耗尽"""
    monkeypatch.setattr("app.services.shared.rate_limiter.RATE_LIMIT_PER_MINUTE", 5)
    monkeypatch.setattr("app.services.shared.rate_limiter.DAILY_QUOTA", 100)

    r = check_rate_limit(1, "user")
    assert r["allowed"] is True
    assert r["remaining"] == 4  # min(5-1, 100-1)


def test_admin_and_cs_unlimited(fake_redis):
    """admin/customer_service 不限流，且完全不碰 Redis"""
    for role in ("admin", "agent"):
        r = check_rate_limit(999, role)
        assert r["allowed"] is True
        assert r["remaining"] == 999
    assert fake_redis.keys() == []  # 一次 INCR 都没发生


def test_rate_limit_isolated_between_users(fake_redis, monkeypatch):
    """A 被限流不牵连 B（计数按 user_id 隔离）"""
    monkeypatch.setattr("app.services.shared.rate_limiter.RATE_LIMIT_PER_MINUTE", 1)
    check_rate_limit(1, "user")
    assert check_rate_limit(1, "user")["allowed"] is False
    assert check_rate_limit(2, "user")["allowed"] is True


# ========== 登录/注册 IP 限流 ==========

def test_login_minute_limit(fake_redis, monkeypatch):
    monkeypatch.setattr("app.services.shared.rate_limiter.LOGIN_LIMIT_PER_MINUTE", 2)
    monkeypatch.setattr("app.services.shared.rate_limiter.LOGIN_DAILY_LIMIT", 10 ** 9)

    ip = "10.0.0.1"
    assert check_login_rate_limit(ip) == (False, "")
    assert check_login_rate_limit(ip) == (False, "")
    blocked, msg = check_login_rate_limit(ip)
    assert blocked is True
    assert "频繁" in msg


def test_login_daily_limit(fake_redis, monkeypatch):
    monkeypatch.setattr("app.services.shared.rate_limiter.LOGIN_LIMIT_PER_MINUTE", 10 ** 9)
    monkeypatch.setattr("app.services.shared.rate_limiter.LOGIN_DAILY_LIMIT", 2)

    ip = "10.0.0.2"
    assert check_login_rate_limit(ip)[0] is False
    assert check_login_rate_limit(ip)[0] is False
    blocked, msg = check_login_rate_limit(ip)
    assert blocked is True
    assert "明天" in msg


def test_login_limit_isolated_between_ips(fake_redis, monkeypatch):
    """A IP 被封不影响 B IP（撞库封的是源 IP 不是全局）"""
    monkeypatch.setattr("app.services.shared.rate_limiter.LOGIN_LIMIT_PER_MINUTE", 1)

    ip_a, ip_b = "10.0.0.3", "10.0.0.4"
    check_login_rate_limit(ip_a)
    assert check_login_rate_limit(ip_a)[0] is True
    assert check_login_rate_limit(ip_b)[0] is False
