"""Embedding 服务"""
import torch
from sentence_transformers import SentenceTransformer
from app.config import EMBEDDING_MODEL

_model = None
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"  # 3050 走 GPU，没卡自动回退

# bge 检索指令前缀（官方要求，query 用、doc 不用）
BGE_QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："

def get_model():
    global _model
    if _model is None:
        _model = SentenceTransformer(EMBEDDING_MODEL, device=DEVICE)
    return _model

def embed_text(text: str) -> list[float]:
    """单条文本向量化（文档入库用，不加前缀）"""
    model = get_model()
    return model.encode(text, normalize_embeddings=True).tolist()

def embed_texts(texts: list[str]) -> list[list[float]]:
    """批量文本向量化——批量推理是 GPU 的主场（文档入库用，不加前缀）"""
    model = get_model()
    return model.encode(texts, normalize_embeddings=True).tolist()

def embed_query(text: str) -> list[float]:
    """查询向量化（检索用，加 bge 检索指令前缀）"""
    model = get_model()
    return model.encode(BGE_QUERY_INSTRUCTION + text, normalize_embeddings=True).tolist()
