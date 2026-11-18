"""RRF 融合排序（纯函数）"""
import hashlib

RRF_K = 60  # RRF 常数，标准值（Cormack 2009 论文经验值）

def _hit_key(hit: dict) -> str:
    """生成 hit 的唯一键：优先用 metadata 的 doc_id+chunk_index，没有则用 content hash 兜底"""
    meta = hit.get("metadata") or {}
    doc_id = meta.get("doc_id")
    chunk_idx = meta.get("chunk_index")
    if doc_id is not None and chunk_idx is not None:
        return f"{doc_id}:{chunk_idx}"
    # 兜底：content 完整 hash（比前100字更可靠，短条款不会误合并）
    return hashlib.md5(hit["content"].encode()).hexdigest()

def rrf_fuse(kw_results: list, vec_results: list, top_k: int) -> list:
    """两路检索结果按 RRF 融合，返回按 rrf_score 降序、最多 top_k*2 条的列表（不含精排）"""
    rrf_scores = {}

    for rank, hit in enumerate(kw_results):
        key = _hit_key(hit)
        rrf_scores[key] = rrf_scores.get(key, 0) + 1.0 / (RRF_K + rank + 1)

    for rank, hit in enumerate(vec_results):
        key = _hit_key(hit)
        rrf_scores[key] = rrf_scores.get(key, 0) + 1.0 / (RRF_K + rank + 1)  # 两路都命中就叠加加分

    # 后出现的一路会覆盖先出现的同键 hit，但 rrf 分数已叠加，排序不受影响
    all_hits = {_hit_key(hit): hit for hit in kw_results + vec_results}
    sorted_keys = sorted(rrf_scores.keys(), key=lambda k: rrf_scores[k], reverse=True)

    results = []
    for key in sorted_keys[:top_k * 2]:
        hit = all_hits[key]
        hit['rrf_score'] = rrf_scores[key]
        results.append(hit)
    return results
