"""
pytest 全局配置与替身（fixtures）

功能：让所有单测在零外部依赖下运行——不连 MySQL/Redis、不加载 GPU 模型、不调 LLM。
数据流：pytest 启动 → 本文件最先加载
  ① 设 JWT_SECRET（必须在 import app.config 之前，CI 无 .env 也能跑）
  ② 把 zhiliu/ 塞进 sys.path（任意目录调 pytest 都能 import app）
  ③ 提供替身 fixtures：FakeDB（假数据库）、fakeredis（假 Redis）、假 embedding、熔断状态复位
"""
import os
import sys

# ---- ① 环境必须最先就位：config.py 要求 JWT_SECRET 存在，缺了 import 就崩（fail-fast）----
# setdefault：本地 .env 已配则不覆盖；CI 没有 .env 则用测试值
os.environ.setdefault("JWT_SECRET", "unit-test-secret-do-not-use-in-prod")

# ---- ② 项目根目录进 sys.path：保证从仓库任何位置执行 pytest 都能 import app ----
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402


# ========== FakeDB：模拟 pymysql 连接（DictCursor 行为）==========
class FakeCursor:
    def __init__(self, db: "FakeDB"):
        self.db = db

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, sql, params=None):
        # 只记录不执行：测试断言参数化值（params），不断言 SQL 字符串（防脆弱）
        self.db.executed.append({"sql": sql, "params": params})

    def fetchone(self):
        # SELECT 后取一行：按调用顺序从预设 rows 队列里弹出
        # （UPDATE/INSERT 不调 fetchone，不会误弹）
        if self.db.rows:
            return self.db.rows.pop(0)
        return None

    def fetchall(self):
        rows, self.db.rows = self.db.rows, []
        return rows

    @property
    def lastrowid(self):
        return self.db.lastrowid

    @property
    def rowcount(self):
        # 模拟 UPDATE/INSERT 影响行数；测试可预设 db.rowcount 覆盖（如并发冲突=0）
        return getattr(self.db, "rowcount", 1)


class FakeDB:
    """测试用假连接。rows = 预设的查询结果队列（按 SELECT 顺序消费）"""

    def __init__(self, rows=None, lastrowid: int = 1):
        self.rows = list(rows or [])
        self.lastrowid = lastrowid
        self.executed = []   # [{"sql":..., "params":...}, ...]
        self.committed = False
        self.closed = False

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        self.committed = True

    def rollback(self):
        self.committed = False

    def close(self):
        self.closed = True

    def params_seen(self):
        """所有 execute 的参数列表，断言参数化值用（不断言 SQL 原文）"""
        return [e["params"] for e in self.executed]


@pytest.fixture
def fake_db(monkeypatch):
    """
    用法：
        db = fake_db(rows=[{"id": 7, ...}], module="app.services.auth.auth")
        auth.refresh_access(token)   # 内部 get_db() 拿到的就是这个 FakeDB
    默认 patch auth 模块；其他模块传 module="app.services.tickets.ticket_approval" 等
    """
    def factory(rows=None, module: str = "app.services.auth.auth", lastrowid: int = 1):
        db = FakeDB(rows=rows, lastrowid=lastrowid)
        monkeypatch.setattr(f"{module}.get_db", lambda: db)
        return db
    return factory


@pytest.fixture
def fake_redis(monkeypatch):
    """真 Redis 的内存替身：cache 和 rate_limiter 两个模块的连接同时替换"""
    import fakeredis
    fr = fakeredis.FakeRedis(decode_responses=True)
    monkeypatch.setattr("app.services.shared.cache.r", fr)
    monkeypatch.setattr("app.services.shared.rate_limiter.r", fr)
    return fr


@pytest.fixture
def fake_embedding(monkeypatch):
    """
    假 embedding：拦截 cache.get/set_semantic_cached 内部的
    `from app.services.knowledge_base.embedding import embed_text`（函数内 import，靠 sys.modules 顶替），
    避免测试加载 sentence-transformers/torch。
    """
    import types
    vectors = {
        "年假多少": [1.0, 0.0],                       # 基准向量
        "别的问题": [0.9, 0.4359],                     # 与基准点积 0.9 < 阈值 0.95 → 不命中
    }
    mod = types.ModuleType("app.services.knowledge_base.embedding")
    mod.embed_text = lambda q: vectors.get(q, [1.0, 0.0])
    monkeypatch.setitem(sys.modules, "app.services.knowledge_base.embedding", mod)
    return mod


@pytest.fixture
def clean_router():
    """每条熔断测试前后把 model_router 全局状态清零（模块级可变状态必须隔离）"""
    import app.services.chat.model_router as mr
    def reset():
        for tier in mr.MODELS:
            mr._health[tier].clear()
            mr._down_until[tier] = 0.0
            mr._cooldown_seen[tier] = 0.0
    reset()
    yield mr
    reset()


@pytest.fixture
def audit_log(monkeypatch):
    """拦下 ticket_approval 里的 log_audit（它内部会开真数据库连接）"""
    import app.services.tickets.ticket_approval as ta
    calls = []
    monkeypatch.setattr(ta, "log_audit", lambda *a, **k: calls.append((a, k)))
    return calls
