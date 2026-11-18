"""
工单 Agent

功能：判断用户是否需要创建工单，自动提取标题和内容。
"""
import json
from app.services.chat.chat import call_llm


def ticket_agent(state: dict) -> dict:
    """让 LLM 判断要不要建工单，要就提取标题和描述写进 tickets 表"""
    user_message = state["user_message"]
    messages = state["messages"]
    sentiment = state["sentiment"]  # 取出但暂未使用

    # 让 LLM 判断是否需要创建工单，并提取信息
    prompt = f"""分析用户消息，判断是否需要创建工单。
    如果需要，返回JSON：{{"create": true, "title": "工单标题", "content": "问题描述"}}
    如果不需要，返回JSON：{{"create": false}}

    最近对话：
    {chr(10).join([m['content'] for m in messages[-5:]])}
    用户最新消息：{user_message}"""

    result_text = call_llm([{"role": "user", "content": prompt}], max_tokens=200, fallback='{"create": false}',task_type="json_extract")

    try:
        result = json.loads(result_text)
    except json.JSONDecodeError:
        result = {"create": False}  # 解析失败就当不用建单

    if result.get("create"):
        # 只写 Java（业务唯一真相），不再 INSERT Python tickets
        title = result.get("title", "用户反馈")
        content = result.get("content", user_message)
        try:
            from app.services.bridge.java_bridge import bridge_ticket_to_java

            class _Req:
                pass
            _req = _Req()
            _req.state = type("S", (), {"user_id": state.get("user_id")})()
            data = bridge_ticket_to_java(title, content, category="it",
                                         priority="normal", request=_req)
            if data:
                response = f"已为您创建工单「{data.get('ticketNo') or title}」，我们的客服团队会尽快处理。"
            else:
                response = "工单创建失败，请稍后到工作台重试。"
        except Exception as _je:
            import logging
            logging.getLogger(__name__).warning("java bridge skip: %s", _je)
            response = "工单创建失败，请稍后到工作台重试。"

    else:
        response = "请问还有什么可以帮您的？如果需要提交工单，请描述您的问题。"  # 不建单就引导用户描述

    return {"response": response, "agent_name": "ticket"}
