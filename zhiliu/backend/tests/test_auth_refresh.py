"""
refresh token 轮换与复用检测测试

锁的行为：
  ① 正常轮换：ver 对得上 → token_version+1 → 发新 access+refresh（旧 refresh 从下一次起失效）
  ② 复用检测：拿着旧 ver 的 refresh 再来 → 401 + 再次递增（把在飞的 token 全吊销）
  ③ access 冒充 refresh → 401；伪造/损坏 token → 401
数据流：refresh_access(token)
  → decode 验签 → 查 users.token_version → 对比 payload.ver
  → 相等：UPDATE token_version=ver+1 → 发新对；不等：UPDATE token_version+1 → 401
安全意义：refresh 被盗后，攻击者用一次、合法用户用一次，必有一方触发复用检测全量吊销。
"""
import pytest
from fastapi import HTTPException

from app.services.auth.auth import create_token, refresh_access, decode_token

USER = {"id": 7, "username": "alice", "token_version": 3}


# ========== 正常轮换 ==========

def test_fresh_refresh_rotates_version(fake_db):
    db = fake_db(rows=[dict(USER)])
    old_refresh = create_token(USER["id"], USER["username"], token_type="refresh", ver=3)

    out = refresh_access(old_refresh)

    # 版本递增：UPDATE 参数化值 (new_ver=4, user_id=7)
    assert (4, USER["id"]) in db.params_seen()
    assert db.committed

    # 新 refresh 带新 ver=4；旧 refresh 还是 ver=3（下一次用它就撞复用检测）
    new_payload = decode_token(out["refresh_token"])
    assert new_payload["ver"] == 4
    assert new_payload["token_type"] == "refresh"
    assert out["access_token"]
    assert out["token_type"] == "bearer"


def test_rotation_invalidates_old_refresh(fake_db):
    """轮换后再拿旧 refresh 用 → 复用检测 401（这是防盗的核心）"""
    fresh = create_token(USER["id"], USER["username"], token_type="refresh", ver=3)
    fake_db(rows=[dict(USER)])
    refresh_access(fresh)  # 库里已推进到 ver=4

    # 模拟新一轮请求：库里 ver=4，攻击者/用户还拿着旧的 ver=3
    db = fake_db(rows=[{**USER, "token_version": 4}])
    with pytest.raises(HTTPException) as e:
        refresh_access(fresh)
    assert e.value.status_code == 401

    # 复用触发强制吊销：token_version 再 +1（参数化值 (7,) 的原地递增）
    assert (USER["id"],) in db.params_seen()


# ========== 复用 / 冒充 / 伪造 ==========

def test_reused_refresh_forces_revocation(fake_db):
    """库里 ver 已领先 token 一代 → 判定复用，401 + 递增吊销全部 token"""
    stale = create_token(USER["id"], USER["username"], token_type="refresh", ver=3)
    db = fake_db(rows=[{**USER, "token_version": 4}])

    with pytest.raises(HTTPException) as e:
        refresh_access(stale)
    assert e.value.status_code == 401
    assert "已失效" in e.value.detail
    assert (USER["id"],) in db.params_seen()  # token_version + 1
    assert db.committed


def test_access_token_cannot_be_used_as_refresh(fake_db):
    """access 冒充 refresh：token_type 对不上直接 401（连库都不查）"""
    access = create_token(USER["id"], USER["username"], token_type="access")
    with pytest.raises(HTTPException) as e:
        refresh_access(access)
    assert e.value.status_code == 401
    assert "refresh" in e.value.detail


def test_forged_token_rejected(fake_db):
    """签名不对 → decode_token 401（密钥不在攻击者手里就过不了验签）"""
    with pytest.raises(HTTPException) as e:
        refresh_access("not.a.real.token")
    assert e.value.status_code == 401


def test_deleted_user_rejected(fake_db):
    """用户已删但 token 还有效：查库 row=None → 401"""
    fake_db(rows=[])
    token = create_token(USER["id"], USER["username"], token_type="refresh", ver=3)
    with pytest.raises(HTTPException) as e:
        refresh_access(token)
    assert e.value.status_code == 401
