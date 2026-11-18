"""部门 Agent 工厂"""
from app.services.rag.retriever import hybrid_search
from app.services.chat.chat import call_llm
from app.services.chat.agents.function_agent import function_agent

def build_department_agent(department: str, tool_keywords: list[str], system_prompt: str, fallback_msg: str):
    """生成一个部门 agent 函数。参数即该部门的差异化配置，逻辑复用。"""

    def agent(state: dict) -> dict:
        user_message = state["user_message"]

        # 1. 命中工具关键词 → 走 function_agent（LLM 决定调工具还是追问）
        if any(kw in user_message for kw in tool_keywords):
            result = function_agent(state)
            if result.get("response"):
                return {"response": result["response"], "agent_name": department}

        # 2. 查部门知识库（hybrid_search 内部已 rerank）
        hits = hybrid_search(user_message, top_k=3, department=department)
        context = '\n\n'.join([h['content'] for h in hits]) if hits else fallback_msg

        chat_messages = [
            {"role": "system", "content": f"{system_prompt}\n\n参考资料：\n{context}"},
            {"role": "user", "content": user_message}
        ]
        response = call_llm(chat_messages, max_tokens=500, fallback=fallback_msg)
        return {"response": response, "agent_name": department}

    return agent
