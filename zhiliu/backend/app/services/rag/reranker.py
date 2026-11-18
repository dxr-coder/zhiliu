"""Reranking 服务"""
import torch
from sentence_transformers import CrossEncoder
from app.logger import get_logger

log = get_logger(__name__)

_model = None  # 模型单例，全局只加载一次
_model_failed = False  # 加载失败后不再重试，直接走 fallback
MODEL_NAME = 'BAAI/bge-reranker-base'
SCORE_THRESHOLD = 0.3  # 低于此分数的片段丢弃
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

def _get_model():
    """懒加载 cross-encoder 模型：首次慢，之后直接复用。加载失败返回 None。"""
    global _model, _model_failed
    if _model_failed:
        return None
    if _model is None:
        try:
            _model = CrossEncoder(MODEL_NAME, device=DEVICE)
        except Exception as e:
            _model_failed = True
            log.warning("reranker 模型加载失败，后续走 fallback", extra={"err": str(e)[:200]})
            return None
    return _model

def rerank(query: str, documents: list[dict], top_k: int = 5) -> list[dict]:
    """
        对检索结果精排。

        参数:
            query: 用户问题
            documents: hybrid_search 返回的 hit 列表
            top_k: 返回前几个结果

        返回:
            精排后的 hit 列表，每个 hit 新增 rerank_score 字段
            模型不可用时按原始顺序返回前 top_k 条（不阻断链路）
    """
    if not documents:
        return []

    model = _get_model()
    if model is None:
        # fallback：不精排，保留原始顺序，rerank_score 置 0 供下游判断
        for doc in documents[:top_k]:
            doc['rerank_score'] = 0.0
        return documents[:top_k]

    # 构造 query-document 对
    pairs = [(query, doc['content']) for doc in documents]

    try:
        # cross-encoder 打分（GPU 上批量推理）
        scores = model.predict(pairs)
    except Exception as e:
        log.warning("reranker 推理失败，走 fallback", extra={"err": str(e)[:200]})
        for doc in documents[:top_k]:
            doc['rerank_score'] = 0.0
        return documents[:top_k]

    # 附加分数并排序
    for doc, score in zip(documents, scores):
        doc['rerank_score'] = float(score)

    # 按 rerank_score 降序排序，过滤低分
    reranked = sorted(documents, key=lambda doc: doc['rerank_score'], reverse=True)
    reranked = [d for d in reranked if d["rerank_score"] >= SCORE_THRESHOLD]

    return reranked[:top_k]
