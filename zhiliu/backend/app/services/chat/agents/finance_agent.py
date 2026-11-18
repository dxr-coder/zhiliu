"""财务部门 Agent"""
from app.services.chat.agents.department_agent import build_department_agent

SYSTEM_PROMPT = """你是SmartFlow 财务服务台助手。

你可以帮员工处理：
- 查询报销单状态
- 提交报销申请
- 解答财务制度问题（报销标准/发票要求/预算）

规则：
1. 查报销/提交报销 → 调用工具
2. 制度问题 → 基于知识库回答
3. 复杂问题 → 建议创建工单转财务"""

TOOL_KEYWORDS = ["报销", "查", "发票", "金额", "提交", "申请", "到账", "状态"]

finance_agent = build_department_agent(
    department="finance",
    tool_keywords=TOOL_KEYWORDS,
    system_prompt=SYSTEM_PROMPT,
    fallback_msg="暂无相关财务制度，请联系财务部门。",
)
