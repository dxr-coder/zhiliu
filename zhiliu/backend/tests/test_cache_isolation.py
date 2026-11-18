"""
缓存跨用户隔离测试（锁 v6 Phase 1.4 修复）

锁的行为：
  用户 A 写入的缓存，用户 B 无论精确查询还是语义查询都拿不到；
  A 自己能正常命中自己的缓存。
数据流：A set(user_id=1) → Redis key: chat:1:{q} / semantic:1:{q...}
  → B get(user_id=2) → 扫的是 chat:2:* / semantic:2:* → 空 → None
回归意义：这组用例红了 = 缓存泄漏回归（安全问题，必须挡住合并）。
"""
from app.services.shared.cache import (
    get_cached_answer,
    set_cached_answer,
    get_semantic_cached,
    set_semantic_cached,
)

USER_A = 1
USER_B = 2
Q = "年假天数怎么查"


# ========== 精确缓存隔离 ==========

def test_exact_cache_hit_own_user(fake_redis):
    set_cached_answer(USER_A, Q, "A的答案")
    assert get_cached_answer(USER_A, Q) == "A的答案"


def test_exact_cache_isolated_between_users(fake_redis):
    """核心回归：B 不能读到 A 的缓存答案（修复前 key 无 user_id，B 会直接命中）"""
    set_cached_answer(USER_A, Q, "A的答案")
    assert get_cached_answer(USER_B, Q) is None


def test_exact_cache_different_question_same_user(fake_redis):
    set_cached_answer(USER_A, Q, "答案")
    assert get_cached_answer(USER_A, "另一个问题") is None


def test_exact_cache_key_shape(fake_redis):
    """key 必须带 user_id 前缀——形状变了说明隔离实现被改坏"""
    set_cached_answer(USER_A, Q, "答案")
    assert fake_redis.keys() == [f"chat:{USER_A}:{Q}"]


# ========== 语义缓存隔离 ==========

def test_semantic_cache_hit_own_user(fake_redis, fake_embedding):
    set_semantic_cached(USER_A, "年假多少", "A的语义答案")
    assert get_semantic_cached(USER_A, "年假多少") == "A的语义答案"


def test_semantic_cache_isolated_between_users(fake_redis, fake_embedding):
    """核心回归：B 的语义扫描只扫 semantic:2:*，看不到 A 的 semantic:1:* """
    set_semantic_cached(USER_A, "年假多少", "A的语义答案")
    assert get_semantic_cached(USER_B, "年假多少") is None


def test_semantic_cache_below_threshold_not_hit(fake_redis, fake_embedding):
    """相似度 0.9 < 阈值 0.95 → 不复用（同用户内也不是什么都能撞上）"""
    set_semantic_cached(USER_A, "年假多少", "基准答案")
    assert get_semantic_cached(USER_A, "别的问题") is None


def test_semantic_cache_key_shape(fake_redis, fake_embedding):
    set_semantic_cached(USER_A, "年假多少", "答案")
    keys = fake_redis.keys()
    assert len(keys) == 1
    assert keys[0].startswith(f"semantic:{USER_A}:")
