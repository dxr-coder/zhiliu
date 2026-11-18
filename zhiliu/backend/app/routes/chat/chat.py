"""聊天路由"""
import time
import json
from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse, JSONResponse
from app.deps import get_current_user
from app.services.shared.cache import get_cached_answer, set_cached_answer, get_semantic_cached, set_semantic_cached
from app.services.chat.graph import invoke_agent
from app.services.chat.conversation import (
    get_conversation_messages, generate_title, update_conversation_title,
    save_message, is_conversation_owner,
)
from app.services.chat.query_rewriter import rewrite_query
from app.services.chat.summarizer import maybe_summarize
from app.services.chat.agents.function_agent import set_request
from app.services.shared.masker import mask_sensitive
from app.services.observability.auditor import check_sensitive, log_audit
from app.services.quality.ab_test import get_variant
from app.services.rag.retriever import ab_variant_var
from app.logger import get_logger

log = get_logger(__name__)

router = APIRouter(prefix="/chat", tags=["聊天"])

# ========== 私有辅助函数 ==========

def _serve_cached(answer: str, agent_name: str) -> StreamingResponse:
    """缓存命中：用和正常流一致的格式返回，前端不用区分"""

    def gen():
        yield json.dumps({"agent_name": agent_name}) + "\n"
        yield answer

    return StreamingResponse(gen(), media_type="text/event-stream")

def _build_history(conversation_id: int | None, user_id: int) -> list[dict]:
    """拼历史消息；超过阈值自动压缩成摘要"""
    if not conversation_id:
        return []
    history = get_conversation_messages(conversation_id, user_id)
    if not history:
        return []
    raw = [{"role": m["role"], "content": m["content"]} for m in history]
    return maybe_summarize(conversation_id, raw)

def _pipeline(request: Request, user: dict, message: str,
              history_messages: list, conversation_id: int | None) -> dict:
    """
    核心管线：查询改写 → 敏感词拦截 → AB 分流 → 调 Agent 图 → 脱敏 → 落库 → 写缓存
    返回 {"response_text", "agent_name", "result", "assistant_msg_id"}
    """
    # 敏感词拦截（先拦，避免被拦截的消息还去调 LLM 改写）
    blocked = check_sensitive(message)
    if blocked:
        log_audit(user['user_id'], 'sensitive_blocked', f'命中敏感词「{blocked}」: {message[:50]}')
        return {"blocked": True}

    # 查询改写（后面调 Agent 都用改写后的问法）
    rewrite_result = rewrite_query(message, history_messages)
    rewritten = rewrite_result["rewritten"]

    # AB 分流：结果塞进 ContextVar，检索漏斗 hybrid_search 统一消费（B 组 top_k+2）
    ab_variant_var.set(get_variant(user['user_id']))

    # 调用多 Agent 图（user_id 传入 state，ticket_agent 建单时需要）
    set_request(request)
    result = invoke_agent(history_messages, rewritten, user_id=user['user_id'])
    response_text = mask_sensitive(result["response"])
    agent_name = result["agent_name"]

    # agent 结果挂到 request.state，中间件落库时取走
    request.state.trace_info = {
        "agent_name": agent_name,
        "sentiment": result.get("sentiment"),
        "intent": result.get("intent"),
    }

    # 落库
    assistant_msg_id = None
    if conversation_id:
        save_message(conversation_id, 'user', message)
        assistant_msg_id = save_message(conversation_id, "assistant", response_text,
                                        agent_name=agent_name,
                                        source=result.get("source"),
                                        citations=result.get("citations"))
        # 自动生成标题（仅首次对话时）
        try:
            msgs = get_conversation_messages(conversation_id, user['user_id'])
            if msgs is not None and len(msgs) <= 2:
                title = generate_title(message)
                update_conversation_title(conversation_id, user['user_id'], title)
        except Exception:
            pass

    # 写缓存
    set_cached_answer(user['user_id'], message, response_text)
    set_semantic_cached(user['user_id'], message, response_text)

    return {
        "response_text": response_text,
        "agent_name": agent_name,
        "result": result,
        "assistant_msg_id": assistant_msg_id,
    }

def _fake_stream(response_text: str, agent_name: str, assistant_msg_id: int | None,
                 result: dict) -> StreamingResponse:
    """伪流式：第一行 JSON 元数据，后面逐字吐回复"""

    def gen():
        yield json.dumps({
            "agent_name": agent_name,
            "message_id": assistant_msg_id,
            "source": result.get("source"),
            "citations": result.get("citations", []),
        }) + "\n"
        for char in response_text:
            yield char
            time.sleep(0.01)

    return StreamingResponse(gen(), media_type="text/event-stream")

# ========== 路由 ==========

@router.get('/stream')
def chat_stream_route(request: Request, message: str, conversation_id: int = None,
                      user=Depends(get_current_user)):
    """聊天主入口：查缓存 → 拼历史 → 调 Agent → 流式返回"""
    if conversation_id and not is_conversation_owner(conversation_id, user['user_id']):
        return JSONResponse(status_code=404, content={"detail": "对话不存在"})
    try:
        # 1. 查缓存（精确 → 语义）
        cached = get_cached_answer(user['user_id'], message)
        if cached:
            return _serve_cached(cached, "cache")
        semantic_answer = get_semantic_cached(user['user_id'], message)
        if semantic_answer:
            return _serve_cached(semantic_answer, "semantic_cache")

        # 2. 拼历史
        history_messages = _build_history(conversation_id, user['user_id'])

        # 3. 核心管线（改写/拦截/Agent/落库/缓存）
        out = _pipeline(request, user, message, history_messages, conversation_id)
        if out.get("blocked"):
            return _serve_cached(
                "您的消息包含敏感内容，已拦截。如有问题请联系管理员。", "sensitive")

        # 4. 伪流式输出
        return _fake_stream(out["response_text"], out["agent_name"],
                            out["assistant_msg_id"], out["result"])
    except Exception as e:
        log.error("聊天请求处理失败", extra={"err": str(e)[:200]})
        return _serve_cached("抱歉，系统繁忙，请稍后再试。", "error")
