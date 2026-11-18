"""
公共依赖

功能：跨路由复用的依赖函数，比如 get_current_user。
"""

from fastapi import Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from app.services.auth.auth import decode_token
from app.database import get_db
from starlette.requests import Request

security = HTTPBearer()


def verify_token(credentials: HTTPAuthorizationCredentials = Depends(security)):
    """只解 token 不查 DB——require_role 用这个，role 从 claims 读"""
    return decode_token(credentials.credentials)


def get_current_user(request: Request, credentials: HTTPAuthorizationCredentials = Depends(security)):
    # HTTPBearer 自动从请求头取出 token（已剥掉 "Bearer " 前缀）
    token = credentials.credentials
    # 解出 payload，token 是假的/过期的会直接抛 401
    payload = decode_token(token)

    # 挂到 request 上，中间件记 trace 日志时取 user_id 用
    request.state.user_id = payload["user_id"]

    # token 里没带角色，查一次数据库补齐
    db = get_db()
    try:
        with db.cursor() as cur:
            cur.execute(
                "SELECT r.name as role FROM users u JOIN roles r ON u.role_id = r.id WHERE u.id = %s",
                (payload["user_id"],)
            )
            row = cur.fetchone()
    finally:
        db.close()

    # row 为 None：用户被删了但 token 还没过期，或数据不一致
    # 显式 401（而不是裸 TypeError 变 500），前端收到 401 会跳登录页
    if not row:
        raise HTTPException(status_code=401, detail="账号不存在或已被注销")

    # 路由里用 user["user_id"] / user["role"] 直接取
    return {
        "user_id": payload["user_id"],
        "username": payload["username"],
        "role": row["role"],
    }


def require_role(required_role: str):
    def checker(payload: dict = Depends(verify_token)):
        # 角色以库为准（改 role_id / 修数据后旧 JWT 不再误判）；JWT 仅作身份
        role = "user"
        db = get_db()
        try:
            with db.cursor() as cur:
                cur.execute(
                    "SELECT r.name FROM users u JOIN roles r ON u.role_id=r.id WHERE u.id=%s",
                    (payload["user_id"],),
                )
                row = cur.fetchone()
        finally:
            db.close()
        if row and row.get("name"):
            role = row["name"]
        if role != required_role:
            raise HTTPException(status_code=403, detail=f"需要 {required_role} 权限")
        return {"user_id": payload["user_id"], "username": payload["username"], "role": role}

    return checker
