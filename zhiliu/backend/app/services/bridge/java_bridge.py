"""Python AI -> Java 业务桥（唯一业务写入口）。"""
from __future__ import annotations

import logging
import os

import requests

logger = logging.getLogger(__name__)

JAVA_BASE = os.getenv("JAVA_API_BASE", "http://127.0.0.1:8081")
# 演示环境业务账号统一密码；生产应换密钥/服务身份
DEFAULT_PASS = os.getenv("JAVA_USER_PASS", "123456")
# 兜底服务账号
SERVICE_USER = os.getenv("JAVA_SERVICE_USER", "zhangsan")
SERVICE_PASS = os.getenv("JAVA_SERVICE_PASS", DEFAULT_PASS)

_tokens: dict[str, str] = {}

def _username_for(user_id) -> str | None:
    """Python users.id -> username（与 Java sf_user.username 对齐的演示账号）。"""
    if not user_id:
        return None
    try:
        from app.database import get_db
        db = get_db()
        try:
            with db.cursor() as cur:
                cur.execute("SELECT username FROM users WHERE id = %s", (int(user_id),))
                row = cur.fetchone()
            return row["username"] if row else None
        finally:
            db.close()
    except Exception as e:
        logger.warning("[java-bridge] username lookup: %s", e)
        return None

def login_as(username: str | None = None, password: str | None = None) -> str | None:
    """按用户名取/换 Java token（按用户缓存）。"""
    username = username or SERVICE_USER
    password = password or DEFAULT_PASS
    if username in _tokens:
        return _tokens[username]
    try:
        r = requests.post(
            f"{JAVA_BASE}/api/auth/login",
            json={"username": username, "password": password},
            timeout=5,
        )
        data = r.json()
        if data.get("code") == 200:
            _tokens[username] = data["data"]["token"]
            return _tokens[username]
        logger.warning("[java-bridge] login %s failed: %s", username, data.get("msg"))
    except Exception as e:
        logger.warning("[java-bridge] login error: %s", e)
    return None

def _token_for_request(request) -> tuple[str | None, str | None]:
    """返回 (token, username)。"""
    user_id = getattr(request.state, "user_id", None) if request else None
    uname = _username_for(user_id)
    tok = login_as(uname)
    if not tok and uname:
        # 同名失败退回服务号（单据会挂服务号，仅兜底）
        tok = login_as(SERVICE_USER, SERVICE_PASS)
        uname = SERVICE_USER
    return tok, uname

def _call(method: str, path: str, token: str, json_body=None, params=None, timeout=8):
    """带 401 重登重试的统一调用。"""
    url = f"{JAVA_BASE}/api{path}"
    headers = {"Authorization": f"Bearer {token}"}
    try:
        r = requests.request(method, url, headers=headers, json=json_body,
                             params=params, timeout=timeout)
        body = r.json()
        if body.get("code") == 401:
            _tokens.clear()
            return None  # 调用方重登
        return body
    except Exception as e:
        logger.warning("[java-bridge] %s %s error: %s", method, path, e)
        return {"code": 500, "msg": str(e)}

def _call_as_user(request, method: str, path: str, json_body=None, params=None):
    token, uname = _token_for_request(request)
    if not token:
        return {"error": "无法连接业务系统（Java 未登录）"}
    body = _call(method, path, token, json_body, params)
    if body is None:
        token, uname = _token_for_request(request)
        if not token:
            return {"error": "业务登录失败"}
        body = _call(method, path, token, json_body, params)
    if not body:
        return {"error": "业务系统无响应"}
    if body.get("code") != 200:
        return {"error": body.get("msg") or "业务失败", "code": body.get("code")}
    return {"ok": True, "data": body.get("data"), "user": uname}

# ── 工单 ──
_PRI = {"urgent": "p0", "high": "p1", "normal": "p2", "low": "p3",
        "p0": "p0", "p1": "p1", "p2": "p2", "p3": "p3"}
_CAT = {"it": "it.network", "hr": "hr", "finance": "finance", "admin": "admin"}

def bridge_ticket_to_java(title: str, description: str, category: str = "it.network",
                          priority: str = "p2", request=None) -> dict | None:
    """AI 建单 -> 仅写 Java sf_ticket。"""
    # Ticket 无 description 列：正文放 tags（截断）
    payload = {
        "title": (title or "AI 建单")[:120],
        "category": _CAT.get(category, category),
        "priority": _PRI.get(priority, "p2"),
        "source": "ai_chat",
    }
    if description:
        payload["tags"] = description[:200]
    result = _call_as_user(request, "POST", "/tickets", payload)
    if result.get("ok"):
        logger.info("[java-bridge] ticket: %s", (result.get("data") or {}).get("ticketNo"))
        return result.get("data")
    logger.warning("[java-bridge] ticket fail: %s", result.get("error"))
    return None

# ── 请假 ──
def bridge_leave_to_java(leave_type: str, start_date: str, end_date: str,
                         reason: str, request=None) -> dict:
    """AI 请假 -> Java POST /leaves（走 Java 规则+审批链）。"""
    return _call_as_user(request, "POST", "/leaves", {
        "type": leave_type,
        "startDate": start_date,
        "endDate": end_date,
        "reason": reason,
        "aiDraft": True,
    })

# ── 报销 ──
def bridge_expense_to_java(amount: float, category: str, description: str,
                           request=None) -> dict:
    """AI 报销 -> Java POST /expenses（幂等键+明细）。"""
    cat = category if category in ("travel", "meal", "office", "train", "other") else "other"
    return _call_as_user(request, "POST", "/expenses", {
        "expense": {"type": cat, "amountTotal": float(amount), "costCenter": "ai"},
        "items": [{"name": description[:80] or cat, "amount": float(amount)}],
        "idempotencyKey": f"ai-{category}-{amount}-{description[:20]}",
    })

# ── 会议室 ──
def bridge_room_book_to_java(room_id: int, book_date: str, start_time: str,
                             end_time: str, purpose: str, request=None) -> dict:
    """AI 预订 -> Java POST /rooms/bookings（Java 做时段冲突校验）。"""
    start_at = f"{book_date}T{start_time}"
    end_at = f"{book_date}T{end_time}"
    return _call_as_user(request, "POST", "/rooms/bookings", {
        "roomId": int(room_id),
        "startAt": start_at,
        "endAt": end_at,
        "title": purpose,
    })

# ── 查询（只读 Java）──
def bridge_my_leaves(request=None) -> dict:
    return _call_as_user(request, "GET", "/leaves/my")

def bridge_my_expenses(request=None) -> dict:
    return _call_as_user(request, "GET", "/expenses/my")

def bridge_rooms(request=None) -> dict:
    return _call_as_user(request, "GET", "/rooms")

def bridge_leave_balance(request=None) -> dict:
    """演示余额：与 Java LeaveService 一致的占位 10 天（ledger 未接真余额）。"""
    return {"ok": True, "data": {
        "annual_leave": 10, "sick_leave": None, "comp_leave": 10,
        "note": "Java 演示余额 10 天（B1 占位）",
    }}
