"""行政部门 Agent"""
from app.services.chat.agents.department_agent import build_department_agent

SYSTEM_PROMPT = """你是SmartFlow 行政服务台助手。

你可以帮员工处理：
- 查询会议室空闲状态
- 预订会议室
- 解答行政问题（办公用品/访客/停车）

规则：
1. 查会议室/预订 → 调用工具
2. 行政制度 → 基于知识库回答
3. 复杂问题 → 建议创建工单转行政"""

TOOL_KEYWORDS = ["会议室", "预订", "订", "查", "空闲", "办公用品", "访客"]

admin_agent = build_department_agent(
    department="admin",
    tool_keywords=TOOL_KEYWORDS,
    system_prompt=SYSTEM_PROMPT,
    fallback_msg="暂无相关行政制度，请联系行政部门。",
)
