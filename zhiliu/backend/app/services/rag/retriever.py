"""
混合检索服务

功能：BM25 关键词检索 + 向量语义检索 + RRF 融合排序。
"""
from app.database import get_db
from app.services.rag.reranker import rerank
from app.services.rag.vector_store import search as semantic_search
from app.services.rag.rrf import rrf_fuse
import contextvars

ab_variant_var = contextvars.ContextVar('ab_variant', default='A')
# 消融开关：True=开精排（默认），False=关精排（只保留 RRF 原序）
rerank_enabled = contextvars.ContextVar('rerank_enabled', default=True)



def keyword_search(query: str, top_k: int = 10, department: str = None) -> list[dict]:
    """MySQL FULLTEXT 关键词检索（BM25 变体）
    注意：content 的全文索引必须用 ngram 解析器，否则中文整句匹配不上：
    ALTER TABLE document_chunks ADD FULLTEXT INDEX ft_content (content) WITH PARSER ngram;
    """
    db = get_db()
    try:
        with db.cursor() as cur:
            if department:
                # department 传了就只搜本部门 + 通用（all/NULL）文档
                cur.execute("""
                            SELECT dc.id,
                                   dc.document_id,
                                   dc.content, MATCH (dc.content) AGAINST(
                                   %s IN NATURAL LANGUAGE MODE) AS score
                            FROM document_chunks dc
                                JOIN documents d
                            ON dc.document_id = d.id
                            WHERE MATCH (dc.content) AGAINST(%s IN NATURAL LANGUAGE MODE)
                              AND d.is_latest = 1
                              AND (d.department = %s
                               OR d.department = 'all'
                               OR d.department IS NULL)
                            ORDER BY score DESC
                                LIMIT %s
                            """, (query, query, department, top_k))
            else:
                # 没传部门就搜全部最新文档
                cur.execute("""
                            SELECT dc.id,
                                   dc.document_id,
                                   dc.content, MATCH (dc.content) AGAINST(
                                   %s IN NATURAL LANGUAGE MODE) AS score
                            FROM document_chunks dc
                                JOIN documents d
                            ON dc.document_id = d.id
                            WHERE MATCH (dc.content) AGAINST(%s IN NATURAL LANGUAGE MODE)
                              AND d.is_latest = 1
                            ORDER BY score DESC
                                LIMIT %s
                            """, (query, query, top_k))
            rows = cur.fetchall()
        return [
            {
                'content': r['content'],
                "metadata": {"doc_id": r["document_id"], "chunk_id": r["id"]},
                "score": float(r["score"]),
                "source": "keyword",
            }
            for r in rows
        ]
    finally:
        db.close()


def hybrid_search(query: str, top_k: int = 5, department: str = None) -> list[dict]:
    """混合检索：BM25 + 向量 + RRF 融合"""
    # 两路串行检索（可优化为线程池并行），各取 top_k * 2 候选
    if ab_variant_var.get() == "B":
        top_k = top_k + 2  # 实验组：检索候选更广，所有 agent 走这一个漏斗
    candidate_count = top_k * 2
    kw_results = keyword_search(query, candidate_count, department=department)
    vec_results = semantic_search(query, candidate_count, department=department)

    # RRF 融合（公式在 rrf.py 纯函数，被 pytest 锁死）
    results = rrf_fuse(kw_results, vec_results, top_k)

    if rerank_enabled.get():
        results = rerank(query, results, top_k=top_k)
    else:
        results = results[:top_k]
        for d in results:
            d.setdefault('rerank_score', 0.0)

    # 批量查询文档名，用于引用溯源
    doc_ids = list(set(h['metadata'].get('doc_id') for h in results if h.get('metadata', {}).get('doc_id')))
    if doc_ids:
        db = get_db()
        # 一次查出所有 doc_id 对应的文件名，省得逐条查
        try:
            with db.cursor() as cur:
                placeholders = ','.join(['%s'] * len(doc_ids))
                cur.execute(f"SELECT id, filename FROM documents WHERE id IN ({placeholders})", doc_ids)
                name_map = {row['id']: row['filename'] for row in cur.fetchall()}
        finally:
            db.close()
        for h in results:
            doc_id = h.get('metadata', {}).get('doc_id')
            if doc_id in name_map:
                h['metadata']['document_name'] = name_map[doc_id]

    return results
