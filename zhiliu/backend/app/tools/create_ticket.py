"""创建工单工具"""

SCHEMA = {
    "description": "创建工单，当AI无法解决问题需要转人工时使用",
    "parameters": {
        "type": "object",
        "properties": {
            "title": {"type": "string", "description": "工单标题"},
            "description": {"type": "string", "description": "问题描述"},
            "category": {
                "type": "string",
                "enum": ["it", "hr", "finance", "admin"],
                "description": "所属部门：it=IT, hr=人事, finance=财务, admin=行政"
            },
            "priority": {
                "type": "string",
                "enum": ["low", "normal", "high", "urgent"],
                "description": "优先级，默认normal"
            }
        },
        "required": ["title", "description", "category"]
    }
}

def execute(title: str, description: str, category: str, priority: str = "normal", request=None) -> dict:
    """创建工单（只写 Java）"""
    from app.services.bridge.java_bridge import bridge_ticket_to_java
    data = bridge_ticket_to_java(title, description, category=category,
                                 priority=priority, request=request)
    if not data:
        return {"error": "工单创建失败，请稍后重试或到工作台手动创建"}
    return {
        "ticket_id": data.get("id"),
        "ticket_no": data.get("ticketNo"),
        "priority": data.get("priority", priority),
        "status": data.get("status"),
        "message": f"已创建工单 {data.get('ticketNo')}（{category}），状态 {data.get('status')}",
    }
