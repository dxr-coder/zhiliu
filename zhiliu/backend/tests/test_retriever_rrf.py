"""
RRF 融合公式测试（纯函数，零依赖——CI 不装 torch 也能跑）

锁的行为：
  ① 单路命中：score = 1/(60 + rank + 1)
  ② 双路命中同一篇（metadata 的 doc_id+chunk_index 相同）：两路分数叠加
  ③ 双路命中的排在单路命中的前面
  ④ 截断：最多返回 top_k*2 条
  ⑤ 合并键：metadata 优先，content hash 兜底（不是 content 前缀）
数据流：kw_results + vec_results → rrf_fuse → 按 rrf_score 降序的融合列表
公式背景：RRF (Cormack et al. 2009)，不依赖相关性分数量纲，只用名次——
  这正是选它做 BM25(0~n分) 与向量(余弦0~1分) 融合的原因。
"""
from app.services.rag.rrf import rrf_fuse, RRF_K

EPS = 1e-9


def hit(content, metadata=None):
    """构造最小检索结果结构"""
    h = {"content": content, "source": "kw"}
    if metadata:
        h["metadata"] = metadata
    return h


def test_single_source_score_formula():
    """rank 从 0 起：score = 1/(RRF_K + rank + 1)，名次越靠前分越高"""
    results = rrf_fuse([hit("甲"), hit("乙")], [], top_k=5)

    assert len(results) == 2
    assert results[0]["content"] == "甲"
    assert abs(results[0]["rrf_score"] - 1.0 / (RRF_K + 0 + 1)) < EPS
    assert abs(results[1]["rrf_score"] - 1.0 / (RRF_K + 1 + 1)) < EPS


def test_dual_hit_scores_stack():
    """同一篇（metadata 的 doc_id+chunk_index 相同）两路都命中 → 分数是两路之和"""
    meta = {"doc_id": 1, "chunk_index": 0}
    results = rrf_fuse([hit("同一篇文档", meta)], [hit("同一篇文档", meta)], top_k=5)

    assert len(results) == 1  # 合并成一条
    expected = 1.0 / (RRF_K + 1) + 1.0 / (RRF_K + 1)
    assert abs(results[0]["rrf_score"] - expected) < EPS


def test_dual_hit_outranks_single_hit():
    """排序核心：X 只在 kw 首位命中，Y 两路命中 → Y 总分高排第一"""
    x, y = "X只在关键词路", "Y两路都命中"
    results = rrf_fuse([hit(x), hit(y)], [hit(y)], top_k=5)

    assert results[0]["content"] == y
    assert results[0]["rrf_score"] > results[1]["rrf_score"]


def test_result_truncated_to_top_k_times_two():
    """截断窗口 = top_k*2（交给下游 cross-encoder 精排的候选量）"""
    kw = [hit(f"文档{i}" * 30) for i in range(10)]  # 10 个互不相同的候选
    results = rrf_fuse(kw, [], top_k=2)

    assert len(results) == 4  # top_k*2
    # 且按分数降序
    scores = [r["rrf_score"] for r in results]
    assert scores == sorted(scores, reverse=True)


def test_metadata_is_preferred_merge_key():
    """合并键优先用 metadata 的 doc_id+chunk_index：相同 metadata 视为同一篇"""
    meta = {"doc_id": 42, "chunk_index": 5}
    results = rrf_fuse(
        [hit("内容A", meta), hit("内容B", {"doc_id": 42, "chunk_index": 6})],
        [hit("内容A", meta)],
        top_k=5,
    )
    # 内容A 两路命中 → 合并；内容B 只 kw 一路 → 独立
    assert len(results) == 2
    assert results[0]["content"] == "内容A"  # 双路叠加排第一
    assert results[1]["content"] == "内容B"


def test_content_hash_fallback_when_no_metadata():
    """没有 metadata 时用 content 完整 hash 做键：相同内容合并"""
    results = rrf_fuse([hit("完全相同的文本")], [hit("完全相同的文本")], top_k=5)
    assert len(results) == 1  # 相同内容 → hash 相同 → 合并


def test_different_content_not_merged():
    """没有 metadata 且内容不同 → 不合并（即使前100字相同）"""
    prefix = "字" * 100
    long1, long2 = prefix + "版本A", prefix + "版本B"
    results = rrf_fuse([hit(long1), hit(long2)], [], top_k=5)

    assert len(results) == 2  # 内容不同 → hash 不同 → 不合并


def test_empty_inputs_return_empty():
    assert rrf_fuse([], [], top_k=5) == []
