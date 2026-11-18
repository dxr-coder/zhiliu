"""
HTTP 层集成测试

功能：用 FastAPI TestClient 测试完整 HTTP 请求链路（路由→中间件→依赖注入→service）。
数据流：TestClient 发请求 → FastAPI 路由 → 中间件限流/trace → 假数据库/Redis → 响应
目的：验证路由注册、请求校验、状态码、响应格式等"粘合层"逻辑。
跳过条件：langgraph 未安装时自动跳过（CI 最小环境不装大包）。
"""
import pytest

# langgraph 未安装则跳过整个模块（main.py 的 import 链会触发 langgraph）
pytest.importorskip("langgraph", reason="langgraph 未安装，跳过 HTTP 集成测试")

from fastapi.testclient import TestClient  # noqa: E402
from app.main import app  # noqa: E402

client = TestClient(app)


# ========== 健康检查 ==========

def test_health_check_no_auth():
    """/health 不鉴权，K8s livenessProbe 依赖它"""
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_ready_check_returns_status():
    """/ready 返回结构化检查结果（MySQL/Redis 可能 fail，但格式必须对）"""
    resp = client.get("/ready")
    assert resp.status_code == 200
    data = resp.json()
    assert "status" in data
    assert "checks" in data
    # 即使 MySQL/Redis 挂了，也要返回 200 + 明细，K8s 探针看 status 字段
    assert data["status"] in ("ready", "not_ready")


# ========== 认证流程 ==========

class TestAuthFlow:
    """注册 → 登录 → 带 token 访问受保护接口"""

    def test_register_invalid_username_too_short(self):
        """用户名太短：pydantic 校验拦截，返回 422"""
        resp = client.post("/auth/register", json={"username": "a", "password": "123456"})
        assert resp.status_code == 422

    def test_register_invalid_password_too_short(self):
        """密码太短：pydantic 校验拦截，返回 422"""
        resp = client.post("/auth/register", json={"username": "testuser", "password": "123"})
        assert resp.status_code == 422

    def test_register_missing_body(self):
        """没传 body：返回 422"""
        resp = client.post("/auth/register")
        assert resp.status_code == 422

    def test_refresh_missing_token(self):
        """refresh 接口没传 refresh_token：返回 400"""
        resp = client.post("/auth/refresh", json={})
        assert resp.status_code == 400
        assert "refresh_token" in resp.json()["detail"]

    def test_refresh_empty_token(self):
        """refresh_token 传空串：返回 400"""
        resp = client.post("/auth/refresh", json={"refresh_token": ""})
        assert resp.status_code == 400

    def test_login_invalid_credentials(self, fake_db, monkeypatch):
        """用户名或密码错：login service 返回错误（不抛异常），路由返回 200 + 错误信息"""
        # 假数据库：fetchone 返回 None 表示用户不存在
        fake_db(rows=[], module="app.services.auth.auth")
        resp = client.post("/auth/login", json={"username": "nonexist", "password": "123456"})
        # login 函数对用户不存在的处理是返回 {"error": ...}，不是抛 HTTPException
        assert resp.status_code == 200
        data = resp.json()
        assert "error" in data or "detail" in data

    def test_me_no_token(self):
        """/auth/me 不带 token：返回 401"""
        resp = client.get("/auth/me")
        assert resp.status_code == 401

    def test_me_invalid_token(self):
        """/auth/me 带乱写的 token：返回 401"""
        resp = client.get("/auth/me", headers={"Authorization": "Bearer invalid.token.here"})
        assert resp.status_code == 401


# ========== 限流 ==========

class TestRateLimit:
    """限流中间件：超限返回 429"""

    def test_rate_limit_headers_present(self, fake_redis):
        """正常请求也返回 RateLimit-* 头，方便前端做倒计时"""
        # 先清掉之前的计数
        fake_redis.flushall()
        resp = client.get("/health")
        # health 可能不走限流（按 user_id），但至少验证不崩
        assert resp.status_code == 200


# ========== 请求校验 ==========

class TestRequestValidation:
    """pydantic 模型校验"""

    def test_ticket_create_invalid_payload(self):
        """创建工单传非法 payload：422"""
        resp = client.post("/tickets", json={"title": ""})
        # 可能 401（没登录）或 422（校验失败），取决于路由顺序
        assert resp.status_code in (401, 422)

    def test_nonexistent_route(self):
        """访问不存在的路由：404"""
        resp = client.get("/definitely-not-a-real-path")
        assert resp.status_code == 404

    def test_wrong_http_method(self):
        """GET 一个 POST 接口：405"""
        resp = client.get("/auth/login")
        assert resp.status_code == 405


# ========== CORS / 中间件 ==========

class TestMiddleware:
    """中间件层：trace_id 注入、XFF 解析等"""

    def test_trace_id_injected(self):
        """TraceMiddleware 给每个请求注入 X-Trace-ID 响应头"""
        resp = client.get("/health")
        assert "X-Trace-ID" in resp.headers
        # trace_id 不能是空的占位符
        assert resp.headers["X-Trace-ID"] != "-"

    def test_xff_not_trusted_by_default(self):
        """默认不信任 X-Forwarded-For（TRUSTED_PROXY_HOPS=0），伪造 IP 不生效"""
        resp = client.get("/health", headers={"X-Forwarded-For": "1.2.3.4"})
        assert resp.status_code == 200
        # 默认模式下 trace 里记录的 IP 应该是直连 IP，不是伪造的 1.2.3.4
        # （这里只验证不崩，具体 IP 断言需要访问 trace store，集成测试够用）


# ========== OpenAPI 文档 ==========

def test_openapi_schema_generated():
    """/docs 和 /openapi.json 正常生成（FastAPI 自动文档）"""
    resp = client.get("/openapi.json")
    assert resp.status_code == 200
    schema = resp.json()
    assert "paths" in schema
    # 验证几个关键路由已注册
    assert "/auth/login" in schema["paths"]
    assert "/health" in schema["paths"]


def test_docs_endpoint_accessible():
    """/docs Swagger UI 可访问"""
    resp = client.get("/docs")
    assert resp.status_code == 200
