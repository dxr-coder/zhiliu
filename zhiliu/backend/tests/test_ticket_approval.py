"""
工单审批权限矩阵测试

锁的行为（decide_approval 四条规则）：
  ① decision 只能 approve/reject → 400
  ② 该级必须还是 pending（不存在 404 / 已处理 400）
  ③ 审批人角色必须匹配 approver_role → 403
  ④ reject 打工单终态；approve 需链走完才推进 processing
数据流：审批请求 → 查审批行+工单 → 规则链校验 → 更新审批行 →（reject 终态 / approve 看链）
断言风格：只断参数化值（params），不断言 SQL 字符串（防重构误报）。
"""
import pytest
from fastapi import HTTPException

from app.services.tickets.ticket_approval import decide_approval, APPROVAL_CHAIN

TICKET_ID = 5
LEVEL = 1
APPROVER_ID = 10

AGENT = {"user_id": APPROVER_ID, "role": "agent"}
ADMIN = {"user_id": 11, "role": "admin"}


def approval_row(status="pending", approver_role="agent", row_id=99):
    return {
        "id": row_id, "ticket_id": TICKET_ID, "level": LEVEL,
        "approver_role": approver_role, "status": status,
        "title": "测试工单", "ticket_status": "pending",
    }


def call(fake_db, user, decision, rows, comment=""):
    db = fake_db(rows=rows, module="app.services.tickets.ticket_approval")
    result = decide_approval(TICKET_ID, LEVEL, user, decision, comment)
    return db, result


# ========== 规则①：非法 decision ==========

def test_illegal_decision_rejected(fake_db, audit_log):
    db = fake_db(rows=[approval_row()], module="app.services.tickets.ticket_approval")
    with pytest.raises(HTTPException) as e:
        decide_approval(TICKET_ID, LEVEL, AGENT, "maybe")
    assert e.value.status_code == 400
    assert db.params_seen() == []   # 校验在最前面，一查库都不发生


# ========== 规则②：层级不存在 / 已处理 ==========

def test_missing_approval_level_404(fake_db, audit_log):
    db = fake_db(rows=[], module="app.services.tickets.ticket_approval")  # fetchone → None
    with pytest.raises(HTTPException) as e:
        decide_approval(TICKET_ID, LEVEL, AGENT, "approve")
    assert e.value.status_code == 404


def test_already_decided_400(fake_db, audit_log):
    db = fake_db(rows=[approval_row(status="approved")], module="app.services.tickets.ticket_approval")
    with pytest.raises(HTTPException) as e:
        decide_approval(TICKET_ID, LEVEL, ADMIN, "approve")
    assert e.value.status_code == 400
    assert db.committed is False   # 没走到写库


# ========== 规则③：角色不匹配 ==========

def test_role_mismatch_403(fake_db, audit_log):
    """admin 想批 agent 级 → 403（审批权按角色分级，不能越级）"""
    db = fake_db(rows=[approval_row(approver_role="agent")], module="app.services.tickets.ticket_approval")
    with pytest.raises(HTTPException) as e:
        decide_approval(TICKET_ID, LEVEL, ADMIN, "approve")
    assert e.value.status_code == 403
    assert "agent" in e.value.detail
    assert db.committed is False


# ========== 规则④a：reject 打终态 ==========

def test_reject_sets_ticket_rejected(fake_db, audit_log):
    db, result = call(fake_db, AGENT, "reject", [approval_row()], comment="信息不全")

    assert result["result"] == "rejected"
    # 审批行 UPDATE 参数：(新状态, 备注, 审批人id, 时间, 审批行id)
    reject_updates = [p for p in db.params_seen() if p and p[0] == "rejected"]
    assert reject_updates, "审批行没有落 rejected"
    assert reject_updates[0][2] == APPROVER_ID        # decided_by = 当前用户
    assert (TICKET_ID,) in db.params_seen()           # 工单终态 UPDATE 带 ticket_id
    assert db.committed
    # 审计记录：action 是 ticket_rejected
    assert audit_log and audit_log[0][0][1] == "ticket_rejected"


# ========== 规则④b：approve 看链走没走完 ==========

def test_approve_with_pending_level_waits(fake_db, audit_log):
    """urgent 单还有上级没批：通过本级但不推进工单"""
    db, result = call(fake_db, AGENT, "approve",
                      [approval_row(approver_role="agent"), {"c": 1}])  # 还有 pending

    assert "等待上级" in result["msg"]
    # (TICKET_ID,) 出现 1 次 = 只有 has_pending 的 SELECT COUNT，没有推进工单的 UPDATE
    # （UPDATE 和 COUNT 都只带 ticket_id 一个参数，靠出现次数区分，不断言 SQL 原文）
    assert db.params_seen().count((TICKET_ID,)) == 1


def test_approve_final_level_advances_ticket(fake_db, audit_log):
    """链上没有 pending 了：工单自动进入 processing"""
    db, result = call(fake_db, AGENT, "approve",
                      [approval_row(approver_role="agent"), {"c": 0}])

    assert "自动进入处理中" in result["msg"]
    assert result["result"] == "approved"
    # (TICKET_ID,) 出现 2 次 = SELECT COUNT + 推进 processing 的 UPDATE
    assert db.params_seen().count((TICKET_ID,)) == 2


def test_urgent_chain_config_shape():
    """审批链配置本身：urgent 两级（agent→admin），high 一级——改配置要有测试兜底"""
    assert APPROVAL_CHAIN["high"] == [(1, "agent")]
    assert APPROVAL_CHAIN["urgent"] == [(1, "agent"), (2, "admin")]
    assert "normal" not in APPROVAL_CHAIN and "low" not in APPROVAL_CHAIN
