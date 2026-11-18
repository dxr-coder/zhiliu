"""
模型路由熔断器测试（三态：闭合 → 打开 → 半开 → 闭合）

锁的行为（对应真实事故：2026-10 端点下架 mimo-v2.5，熔断器按设计逐档冷却）：
  ① 健康档：窗口失败率 ≤50% 可用，route 按 failover 链选档
  ② 打开（拉闸）：失败率 >50% → 冷却 60s，期间 route 跳过该档
  ③ 半开（试探）：冷却到期清旧账 → 放一个探子请求；探子成功=恢复，失败=重新拉闸
数据流：mark_result 写滑动窗口 → is_healthy 查账（含冷却期判断）→ route 选健康档
状态隔离：clean_router fixture 每条用例前后清空模块级全局状态。
"""
import time

from app.services.chat.model_router import COOLDOWN_SECONDS


# ========== 健康判断 ==========

def test_fresh_tier_is_healthy_and_routed(clean_router):
    assert clean_router.is_healthy("fast") is True
    assert clean_router.route("rewrite") == "fast"   # rewrite 任务首选 fast


def test_below_threshold_window_stays_healthy(clean_router):
    """失败率恰好 50%（1胜1负）仍算健康：阈值是 >50% 才拉闸"""
    clean_router.mark_result("fast", True)
    clean_router.mark_result("fast", False)
    assert clean_router.is_healthy("fast") is True


def test_failure_rate_over_threshold_trips_cooldown(clean_router):
    """空窗口连续失败 → 失败率 100% → 拉闸冷却"""
    clean_router.mark_result("fast", False)
    assert clean_router.is_healthy("fast") is False
    status = clean_router.router_status()["fast"]
    assert 0 < status["cooldown_left"] <= COOLDOWN_SECONDS + 1


# ========== 半开：冷却到期 → 探子 ==========

def test_cooldown_expiry_enters_half_open(clean_router):
    """把拉闸时刻改成过去 = 冷却已到期 → 清旧账，重新放行（半开）"""
    clean_router.mark_result("fast", False)          # 拉闸
    assert clean_router.is_healthy("fast") is False
    clean_router._down_until["fast"] = time.time() - 1  # 手动快进：冷却过期
    assert clean_router.is_healthy("fast") is True       # 半开：给试探机会


def test_probe_success_keeps_closed_circuit_open(clean_router):
    """探子成功 → 窗口干净记 1 笔成功 → 持续健康（恢复正常服务）"""
    clean_router.mark_result("fast", False)
    clean_router._down_until["fast"] = time.time() - 1
    clean_router.is_healthy("fast")            # 到期清账（route 选档时会先走到这）
    clean_router.mark_result("fast", True)     # 探子成功
    assert clean_router.is_healthy("fast") is True


def test_probe_failure_reopens_circuit(clean_router):
    """探子失败 → 窗口失败率超阈值 → 重新拉闸（宁可不试也不雪崩）"""
    clean_router.mark_result("fast", False)
    clean_router._down_until["fast"] = time.time() - 1
    clean_router.is_healthy("fast")            # 到期清账
    clean_router.mark_result("fast", False)    # 探子失败
    assert clean_router.is_healthy("fast") is False
    assert clean_router.router_status()["fast"]["cooldown_left"] > 0


# ========== failover 链选档 ==========

def test_route_fails_over_to_next_tier(clean_router):
    """fast 拉闸 → rewrite 任务自动切 smart；smart 也拉闸 → 切 backup"""
    clean_router.mark_result("fast", False)
    assert clean_router.route("rewrite") == "smart"

    clean_router.mark_result("smart", False)
    assert clean_router.route("rewrite") == "backup"


def test_smart_task_skips_to_backup(clean_router):
    """answer 任务首选 smart，smart 拉闸 → backup"""
    clean_router.mark_result("smart", False)
    assert clean_router.route("answer") == "backup"


def test_all_tiers_down_returns_preferred(clean_router):
    """三档全拉闸：返回首选档硬试（总比不发请求强），不抛异常"""
    for tier in ("fast", "smart", "backup"):
        clean_router.mark_result(tier, False)
    assert clean_router.route("rewrite") == "fast"
