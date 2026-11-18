"""查询假期余额工具"""

SCHEMA = {
    "description": "查询用户的年假、病假、调休余额",
    "parameters": {"type": "object", "properties": {}, "required": []}
}

def execute(request=None) -> dict:
    from app.services.bridge.java_bridge import bridge_leave_balance
    result = bridge_leave_balance(request=request)
    if result.get("error"):
        return {"error": result["error"]}
    data = result.get("data") or {}
    return {
        "annual_leave": data.get("annual_leave"),
        "sick_leave": data.get("sick_leave"),
        "comp_leave": data.get("comp_leave"),
        "note": data.get("note"),
        "message": f"年假余额 {data.get('annual_leave')} 天（{data.get('note', '')}）",
    }
