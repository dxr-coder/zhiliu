"""创建请假申请工具"""

SCHEMA = {
    "description": "帮用户提交请假申请",
    "parameters": {
        "type": "object",
        "properties": {
            "leave_type": {
                "type": "string",
                "enum": ["annual", "sick", "comp", "personal"],
                "description": "请假类型：annual=年假, sick=病假, comp=调休, personal=事假"
            },
            "start_date": {"type": "string", "format": "date", "description": "开始日期 YYYY-MM-DD"},
            "end_date": {"type": "string", "format": "date", "description": "结束日期 YYYY-MM-DD"},
            "reason": {"type": "string", "description": "请假原因"}
        },
        "required": ["leave_type", "start_date", "end_date", "reason"]
    }
}

def execute(leave_type: str, start_date: str, end_date: str, reason: str, request=None) -> dict:
    """提交请假（只写 Java）"""
    from datetime import datetime
    try:
        d1 = datetime.strptime(start_date, "%Y-%m-%d")
        d2 = datetime.strptime(end_date, "%Y-%m-%d")
    except ValueError:
        return {"error": "日期格式须为 YYYY-MM-DD"}
    if d2 < d1:
        return {"error": "结束日期必须大于等于开始日期"}
    days = (d2 - d1).days + 1

    from app.services.bridge.java_bridge import bridge_leave_to_java
    result = bridge_leave_to_java(leave_type, start_date, end_date, reason, request=request)
    if result.get("error"):
        return {"error": result["error"]}
    data = result.get("data") or {}
    return {
        "request_id": data.get("id"),
        "leave_no": data.get("leaveNo"),
        "days": data.get("days", days),
        "status": data.get("status"),
        "message": f"已提交 {data.get('leaveNo')}（{days}天{leave_type}），状态 {data.get('status')}，待审批",
    }
