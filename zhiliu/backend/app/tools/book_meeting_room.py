"""预订会议室工具"""

SCHEMA = {
    "description": "帮用户预订会议室",
    "parameters": {
        "type": "object",
        "properties": {
            "room_id": {"type": "integer", "description": "会议室ID"},
            "book_date": {"type": "string", "format": "date", "description": "预订日期 YYYY-MM-DD"},
            "start_time": {"type": "string", "description": "开始时间 HH:MM"},
            "end_time": {"type": "string", "description": "结束时间 HH:MM"},
            "purpose": {"type": "string", "description": "会议目的"}
        },
        "required": ["room_id", "book_date", "start_time", "end_time", "purpose"]
    }
}

def execute(room_id: int, book_date: str, start_time: str, end_time: str, purpose: str, request=None) -> dict:
    """预订会议室（只写 Java，冲突 409 透传）"""
    from app.services.bridge.java_bridge import bridge_room_book_to_java
    result = bridge_room_book_to_java(int(room_id), book_date, start_time,
                                      end_time, purpose, request=request)
    if result.get("error"):
        return {"error": result["error"]}
    data = result.get("data") or {}
    return {
        "booking_id": data.get("id"),
        "booking_no": data.get("bookingNo"),
        "status": data.get("status"),
        "message": f"已预订 {data.get('bookingNo')} 会议室#{room_id} {book_date} {start_time}-{end_time}",
    }
