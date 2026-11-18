"""认证路由"""
from fastapi import APIRouter, Depends, HTTPException
from app.services.auth.auth import register, login, refresh_access
from app.deps import get_current_user
from app.database import get_db
from pydantic import BaseModel, Field

class LoginRequest(BaseModel):
    username: str = Field(min_length=2, max_length=64)
    password: str = Field(min_length=6, max_length=128)

class RegisterRequest(BaseModel):
    username: str = Field(min_length=2, max_length=64)
    password: str = Field(min_length=6, max_length=128)

router = APIRouter(prefix="/auth", tags=["认证"])

@router.post("/register")
def register_route(payload: RegisterRequest):
    """
    注册新员工账号（默认角色 customer）。
    安全：密码走 JSON body，不出现在 URL/日志里。
    """
    return register(payload.username, payload.password)

@router.post("/login")
def login_route(payload: LoginRequest):
    """
    登录，返回 JWT token 对。
    安全：密码走 JSON body，不出现在 URL/日志里。
    """
    return login(payload.username, payload.password)

@router.post("/refresh")
def refresh_route(payload: dict):
    """用 refresh token 换新的 access + refresh（无感续期）"""
    refresh_token = payload.get("refresh_token")
    if not refresh_token:
        raise HTTPException(status_code=400, detail="缺少 refresh_token")
    return refresh_access(refresh_token)

@router.get("/me")
def get_me(user=Depends(get_current_user)):
    """查当前登录用户是谁"""
    db = get_db()  # 拿一个数据库连接
    try:
        with db.cursor() as cur:
            cur.execute("""
                        SELECT u.id, u.username, r.name as role_name
                        FROM users u
                                 LEFT JOIN roles r ON u.role_id = r.id
                        WHERE u.id = %s
                        """, (user['user_id'],))  # user_id 从 token 里解析出来，不是前端传的
            return cur.fetchone()  # 返回一行：id / 用户名 / 角色名
    finally:
        db.close()  # 不管成功失败都把连接还回去
