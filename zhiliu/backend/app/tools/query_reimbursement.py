"""查询报销状态工具"""

SCHEMA = {
    "description": "查询用户的报销单列表和状态",
    "parameters": {"type": "object", "properties": {}, "required": []}
}

def execute(request=None) -> dict:
    from app.services.bridge.java_bridge import bridge_my_expenses
    result = bridge_my_expenses(request=request)
    if result.get("error"):
        return {"error": result["error"]}
    rows = result.get("data") or []
    items = [{
        "id": r.get("id"),
        "expense_no": r.get("expenseNo"),
        "amount": float(r.get("amountTotal") or 0),
        "status": r.get("status"),
        "type": r.get("type"),
    } for r in rows[:10]]
    return {
        "count": len(items),
        "items": items,
        "message": f"共 {len(items)} 条报销（最近）",
    }
