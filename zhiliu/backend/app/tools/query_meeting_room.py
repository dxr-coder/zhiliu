"""查询会议室状态工具"""

SCHEMA = {
    "description": "查询指定日期的会议室空闲状态",
    "parameters": {
        "type": "object",
        "properties": {
            "date": {
                "type": "string",
                "format": "date",
                "description": "查询日期，格式 YYYY-MM-DD，默认今天"
            }
        },
        "required": []
    }
}

def execute(date: str = None, request=None) -> dict:
    import time
    if not date:
        date = time.strftime("%Y-%m-%d")
    from app.services.bridge.java_bridge import bridge_rooms
    result = bridge_rooms(request=request)
    if result.get("error"):
        return {"error": result["error"]}
    rooms = result.get("data") or []
    items = [{
        "id": r.get("id"),
        "code": r.get("code"),
        "name": r.get("name"),
        "capacity": r.get("capacity"),
        "status": r.get("status"),
    } for r in rooms]
    return {
        "date": date,
        "rooms": items,
        "message": f"{date} 共 {len(items)} 间可用会议室（预订请指定 room_id）",
    }
