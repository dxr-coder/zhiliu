"""
缓存服务（升级：精确匹配 + 语义缓存）

功能：精确匹配直接返回，语义相似问题复用缓存答案。
安全：所有 key 带 user_id 前缀，用户之间缓存互相隔离（防跨用户答案泄漏）。
"""
import redis
from app.config import REDIS_HOST, REDIS_PORT, REDIS_DB, REDIS_PASSWORD
from app.routes.chat.metrics import cache_lookups_total
import math

r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB, password=REDIS_PASSWORD or None, decode_responses=True, protocol=2)

EXACT_TTL = 3600  # 精确匹配缓存 1 小时
SEMANTIC_TTL = 7200  # 语义缓存 2 小时
SEMANTIC_THRESHOLD = 0.92  # 归一化后是余弦相似度，0.92 比裸点积的 0.95 更合理


def _l2_normalize(vec: list[float]) -> list[float]:
    """L2 归一化：除以模长，归一化后点积 = 余弦相似度"""
    norm = math.sqrt(sum(x * x for x in vec))
    if norm == 0:
        return vec
    return [x / norm for x in vec]


def get_cached_answer(user_id: int, question: str):
    """精确匹配缓存（按用户隔离）"""
    result = r.get(f"chat:{user_id}:{question}")
    cache_lookups_total.labels("exact", "hit" if result else "miss").inc()
    return result


def set_cached_answer(user_id: int, question: str, answer: str):
    """精确匹配缓存（按用户隔离）"""
    r.set(f"chat:{user_id}:{question}", answer, ex=EXACT_TTL)


def get_semantic_cached(user_id: int, question: str) -> str | None:
    """语义缓存：只在当前用户的缓存里找相似问题"""
    from app.services.knowledge_base.embedding import embed_text
    import json

    query_vec = embed_text(question)
    query_vec = _l2_normalize(query_vec)

    best_score = 0
    best_answer = None

    # 只扫当前用户的 semantic key，扫描范围从全库缩到单用户
    for key in r.scan_iter(match=f'semantic:{user_id}:*', count=100):
        val = r.get(key)
        if not val:
            continue
        data = json.loads(val)
        cached_vec = data['embedding']
        cached_vec = _l2_normalize(cached_vec)

        score = sum(a * b for a, b in zip(query_vec, cached_vec))
        if score > best_score:
            best_score = score
            best_answer = data['answer']

    if best_score >= SEMANTIC_THRESHOLD:
        cache_lookups_total.labels("semantic", "hit").inc()
        return best_answer
    cache_lookups_total.labels("semantic", "miss").inc()
    return None


def set_semantic_cached(user_id: int, question: str, answer: str):
    """语义缓存：存储问题向量 + 回答（按用户隔离）"""
    from app.services.knowledge_base.embedding import embed_text
    import json

    key = f'semantic:{user_id}:{question[:50]}'
    r.set(key, json.dumps({
        'question': question,
        'embedding': _l2_normalize(embed_text(question)),
        'answer': answer,
    }), ex=SEMANTIC_TTL)
