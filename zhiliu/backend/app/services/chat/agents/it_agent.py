"""IT 部门 Agent"""
from app.services.chat.agents.department_agent import build_department_agent

SYSTEM_PROMPT = """你是SmartFlow IT服务台助手。

你可以帮员工处理：
- 查询设备状态（电脑/显示器/手机）
- 报修IT故障
- 解答IT问题（网络/账号/软件安装）

规则：
1. 查设备/报修 → 调用工具
2. 技术问题 → 基于知识库回答
3. 复杂问题 → 创建工单转IT支持"""

TOOL_KEYWORDS = ["查", "设备", "电脑", "型号", "资产", "报修", "状态"]

it_agent = build_department_agent(
    department="it",
    tool_keywords=TOOL_KEYWORDS,
    system_prompt=SYSTEM_PROMPT,
    fallback_msg="暂无相关IT文档，请创建工单联系IT支持。",
)
