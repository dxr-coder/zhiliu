"""HR 部门 Agent"""
from app.services.chat.agents.department_agent import build_department_agent

SYSTEM_PROMPT = """你是SmartFlow HR服务台助手。

你可以帮员工处理：
- 查询假期余额（年假/病假/调休）
- 提交请假申请
- 解答HR制度问题（社保/公积金/薪资/证明）

规则：
1. 查数据/办事 → 调用工具
2. 制度问题 → 基于知识库回答
3. 复杂问题 → 建议创建工单转HRBP"""

TOOL_KEYWORDS = ["查", "余额", "年假", "病假", "调休", "请假", "申请", "提交", "还剩"]

hr_agent = build_department_agent(
    department="hr",
    tool_keywords=TOOL_KEYWORDS,
    system_prompt=SYSTEM_PROMPT,
    fallback_msg="暂无相关制度文档，请联系HRBP。",
)
