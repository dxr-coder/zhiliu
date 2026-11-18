"""提交报销工具"""

SCHEMA = {
    "description": "帮用户提交报销申请",
    "parameters": {
        "type": "object",
        "properties": {
            "amount": {"type": "number", "description": "报销金额（元）"},
            "category": {
                "type": "string",
                "enum": ["travel", "meal", "office", "training", "other"],
                "description": "报销类别：travel=差旅, meal=餐饮, office=办公, training=培训, other=其他"
            },
            "description": {"type": "string", "description": "报销说明"}
        },
        "required": ["amount", "category", "description"]
    }
}

def execute(amount: float, category: str, description: str, request=None) -> dict:
    """提交报销（只写 Java）"""
    if amount is None or float(amount) <= 0:
        return {"error": "金额必须大于0"}
    from app.services.bridge.java_bridge import bridge_expense_to_java
    result = bridge_expense_to_java(float(amount), category, description, request=request)
    if result.get("error"):
        return {"error": result["error"]}
    data = result.get("data") or {}
    return {
        "reimburse_id": data.get("id"),
        "expense_no": data.get("expenseNo"),
        "amount": data.get("amountTotal", amount),
        "status": data.get("status"),
        "message": f"已提交 {data.get('expenseNo')}（{amount}元），状态 {data.get('status')}",
    }
