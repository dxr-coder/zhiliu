"""AI 六点位白名单实现（A1 分诊 / A2 起草 / A4 坐席副驾 + 降级）。"""
from __future__ import annotations

import logging
import os
import re
from typing import Any

logger = logging.getLogger(__name__)

# 全局降级开关（内存；与 Java sf_sys_config ai.enabled 演示时可分别拨）
_state = {"enabled": os.getenv("AI_POINTS_ENABLED", "true").lower() != "false",
          "triage_min_conf": float(os.getenv("AI_TRIAGE_MIN_CONF", "0.6"))}

# A1 关键词表：命中即给 category/priority/conf
_TRIAGE_RULES: list[tuple[list[str], str, str, float]] = [
    (["vpn", "断网", "连不上", "网络", "wifi", "ping"], "it.network", "p1", 0.88),
    (["蓝屏", "开不了机", "键盘", "显示器", "风扇", "硬件"], "it.hardware", "p2", 0.82),
    (["报销", "发票", "打款", "预算"], "finance", "p2", 0.90),
    (["请假", "年假", "病假", "调休", "入职", "离职"], "hr", "p2", 0.87),
    (["会议室", "预订", "签到"], "admin", "p3", 0.80),
    (["邮件", "outlook", "收不到"], "mail", "p2", 0.84),
    (["bug", "报错", "异常", "崩溃"], "bug", "p1", 0.78),
]

_CATEGORY_FALLBACK = "admin"
_PRIORITY_FALLBACK = "p2"

def set_enabled(flag: bool) -> None:
    _state["enabled"] = bool(flag)

def is_enabled() -> bool:
    return bool(_state["enabled"])

def _llm_json(prompt: str, fallback_obj: dict, max_tokens: int = 400) -> dict | None:
    """调 LLM 解析 JSON；失败/开关关返回 None（调用方回退规则）。"""
    if not is_enabled():
        return None
    try:
        from app.services.chat.chat import call_llm
        raw = call_llm(
            [{"role": "user", "content": prompt}],
            max_tokens=max_tokens,
            fallback="",
            max_retries=1,
            task_type="json_extract",
        )
        if not raw or not raw.strip():
            return None
        # 容错：截取首个 { ... }
        s, e = raw.find("{"), raw.rfind("}")
        if s < 0 or e <= s:
            return None
        import json as _json
        obj = _json.loads(raw[s:e + 1])
        if not isinstance(obj, dict):
            return None
        return obj
    except Exception as ex:
        logger.warning("ai points llm json fail: %s", ex)
        return None

def _llm_text(prompt: str, max_tokens: int = 500) -> str | None:
    if not is_enabled():
        return None
    try:
        from app.services.chat.chat import call_llm
        raw = call_llm(
            [{"role": "user", "content": prompt}],
            max_tokens=max_tokens,
            fallback="",
            max_retries=1,
            task_type="answer",
        )
        return raw.strip() if raw and raw.strip() else None
    except Exception as ex:
        logger.warning("ai points llm text fail: %s", ex)
        return None

def triage(text: str) -> dict[str, Any]:
    """A1 分诊：LLM 优先，失败回退关键词；conf<min 或降级 -> 人工队列。"""
    if not is_enabled():
        return {"degraded": True, "category": None, "priority": None,
                "conf": 0.0, "reason": "ai_disabled", "human_queue": True}
    # LLM 分诊
    llm = _llm_json(
        "对工单标题分类。可选 category: it.network|it.hardware|hr|finance|admin|mail|bug；"
        "priority: p0|p1|p2|p3。只输出 JSON: {\"category\":\"...\",\"priority\":\"...\",\"conf\":0.0-1.0}"
        f"标题：{text}",
        {},
        max_tokens=120,
    )
    if llm and llm.get("category") in (
        "it.network", "it.hardware", "hr", "finance", "admin", "mail", "bug"
    ):
        cat = llm["category"]
        pri = llm.get("priority") if llm.get("priority") in ("p0", "p1", "p2", "p3") else "p2"
        try:
            conf = float(llm.get("conf") or 0.7)
        except Exception:
            conf = 0.7
        conf = max(0.0, min(1.0, conf))
        human = conf < _state["triage_min_conf"]
        return {"degraded": False, "category": cat, "priority": pri,
                "conf": conf, "reason": "llm", "human_queue": human}
    t = (text or "").lower()
    best = (_CATEGORY_FALLBACK, _PRIORITY_FALLBACK, 0.3, "no_keyword")
    for kws, cat, pri, conf in _TRIAGE_RULES:
        if any(k in t for k in kws):
            best = (cat, pri, conf, "keyword:" + kws[0])
            break
    cat, pri, conf, reason = best
    min_conf = _state["triage_min_conf"]
    human = conf < min_conf
    return {"degraded": False, "category": cat, "priority": pri,
            "conf": conf, "reason": reason, "human_queue": human}

def draft(biz_type: str, utterance: str) -> dict[str, Any]:
    """A2 单据起草：话术 -> 字段建议；空字段人工补；确认才提交。"""
    if not is_enabled():
        return {"degraded": True, "fields": {}, "missing": [], "conf": 0.0,
                "reason": "ai_disabled"}
    u = utterance or ""
    fields: dict[str, Any] = {}
    missing: list[str] = []
    conf = 0.5

    # LLM 抽字段（失败回退规则）
    if biz_type in ("leave", "expense"):
        llm = _llm_json(
            f"从话术抽取{biz_type}字段，输出 JSON。"
            + ("leave: type(annual/sick/personal/comp), startDate, endDate, reason(YYYY-MM-DD)"
               if biz_type == "leave"
               else "expense: type(travel/meal/office/train/other), amountTotal(number), reason")
            + " 话术: " + str(u),
            {},
            max_tokens=250,
        )
        if llm and (llm.get("startDate") or llm.get("amountTotal") is not None):
            fields = {k: v for k, v in llm.items() if v is not None and k != "conf"}
            conf = 0.85
            if biz_type == "leave":
                for k in ("startDate", "endDate"):
                    if k not in fields:
                        missing.append(k)
                fields.setdefault("type", "annual")
            else:
                if "amountTotal" not in fields:
                    missing.append("amountTotal")
                fields.setdefault("type", "other")
                if "items" not in fields:
                    fields["items"] = [{"name": str(fields.get("reason") or "明细")[:40],
                                        "amount": fields.get("amountTotal")}]
            return {"degraded": False, "fields": fields, "missing": missing,
                    "conf": conf, "reason": "llm"}

    if biz_type == "leave":
        # 日期：明天/后天 / YYYY-MM-DD / M月D日
        if "明天" in u:
            import datetime
            fields["startDate"] = (datetime.date.today() + datetime.timedelta(days=1)).isoformat()
            fields["endDate"] = fields["startDate"]
            conf = 0.75
        m = re.search(r"(\d{4}-\d{2}-\d{2})\s*到\s*(\d{4}-\d{2}-\d{2})", u)
        if m:
            fields["startDate"], fields["endDate"] = m.group(1), m.group(2)
            conf = 0.88
        if "年假" in u:
            fields["type"] = "annual"
        elif "病假" in u:
            fields["type"] = "sick"
        elif "事假" in u:
            fields["type"] = "personal"
        elif "调休" in u:
            fields["type"] = "comp"
        else:
            fields.setdefault("type", "annual")
        if "原因" in u or "因为" in u:
            fields["reason"] = u
        for k in ("startDate", "endDate"):
            if k not in fields:
                missing.append(k)
    elif biz_type == "expense":
        m = re.search(r"(\d+(?:\.\d+)?)\s*(?:元|块)", u)
        if m:
            fields["amountTotal"] = float(m.group(1))
            conf = 0.8
        if "差旅" in u or "机票" in u or "高铁" in u:
            fields["type"] = "travel"
        elif "餐" in u:
            fields["type"] = "meal"
        elif "办公" in u:
            fields["type"] = "office"
        else:
            fields.setdefault("type", "other")
        fields.setdefault("items", [{"name": u[:40] or "明细", "amount": fields.get("amountTotal")}])
        if "amountTotal" not in fields:
            missing.append("amountTotal")
    else:
        missing.append("biz_type_unsupported")

    return {"degraded": False, "fields": fields, "missing": missing,
            "conf": conf, "reason": "rule_extract"}

def assist(ticket_title: str, ticket_body: str) -> dict[str, Any]:
    """A4 坐席副驾：回复草稿/建议动作；不自动发送。"""
    if not is_enabled():
        return {"degraded": True, "reply_draft": None, "actions_suggested": [],
                "reason": "ai_disabled"}
    title = ticket_title or ""
    body = ticket_body or ""
    # LLM 回复草稿
    llm_reply = _llm_text(
        "你是企业服务台坐席，根据工单写一条简短中文回复草稿（不超过120字），"
        "说明正在处理并请用户补充必要信息。只输出回复正文。 "
        + " 标题: " + str(title) + " 描述: " + str(body),
        max_tokens=200,
    )
    if llm_reply:
        return {"degraded": False, "reply_draft": llm_reply[:400],
                "actions_suggested": ["human_review"], "reason": "llm",
                "auto_send": False}
    blob = (title + " " + body).lower()
    actions: list[str] = []
    if any(k in blob for k in ("vpn", "连不上", "网络")):
        reply = ("您好，已收到网络类问题。请先确认：\n"
                 "1) 本机能否访问内网门户；\n"
                 "2) VPN 客户端是否提示具体错误码。\n"
                 "补充截图后我们继续排查，预计 30 分钟内首响。")
        actions = ["request_screenshot", "assign_network_agent"]
    elif any(k in blob for k in ("报销", "发票")):
        reply = ("您好，报销需包含：金额>0 明细、发票号唯一、总额与明细一致。\n"
                 "提交后走 主管→财务 审批；预算不足会打 budget_low 标。")
        actions = ["link_expense_faq"]
    elif any(k in blob for k in ("请假", "年假")):
        reply = ("您好，年假超过 3 天或病假需人事加签；余额不足会被拒。\n"
                 "可在「工作台-请假」新建，审批进度在「审批」页查看。")
        actions = ["link_leave_faq"]
    else:
        reply = ("您好，已收到您的问题，正在为您核实。\n"
                 "为加快处理，请补充：发生时间、影响范围、截图/报错原文。")
        actions = ["collect_context"]
    return {"degraded": False, "reply_draft": reply,
            "actions_suggested": actions, "reason": "rule_template",
            "auto_send": False}

def answer_rag(question: str) -> dict:
    """A3：关键词命中本地知识规则 → 带 citations；否则 source=none 建议建单。"""
    if not is_enabled():
        return {"degraded": True, "answer": None, "citations": [], "source": "none",
                "suggest_ticket": True, "reason": "ai_disabled"}
    q_raw = (question or "").strip()
    q = q_raw.lower()
    # LLM 答案（要求标注是否可信；无依据则 suggest_ticket）
    llm_ans = _llm_text(
        "你是企业知识库助手。根据常识用中文简答；若不确定，明确说需要人工核实。"
        "不要编造制度数字。 问题: " + str(q_raw),
        max_tokens=350,
    )
    if llm_ans and len(llm_ans) > 10:
        # 仍挂知识引用前缀（规则库命中则用 kb://，否则 none）
        cites = ["kb://general"]
        for kws, _a, c in [
            (["请假", "年假"], "", ["kb://hr/leave-policy"]),
            (["报销", "发票"], "", ["kb://finance/expense-policy"]),
            (["vpn", "网络"], "", ["kb://it/network"]),
        ]:
            if any(k in q for k in kws):
                cites = c
                break
        source = "llm+kb" if cites != ["kb://general"] else "llm"
        return {"degraded": False, "answer": llm_ans, "citations": cites,
                "source": source, "suggest_ticket": False, "conf": 0.7}
    # 规则知识卡回退（演示；生产接向量库 RAG）
    cards = [
        (["请假", "年假", "调休"], "年假>3 天或病假需人事加签；余额不足会被拒。",
         ["kb://hr/leave-policy"]),
        (["报销", "发票", "打款"], "报销需金额>0 明细、发票号唯一；主管→财务审批。",
         ["kb://finance/expense-policy"]),
        (["vpn", "网络", "连不上"], "先确认内网门户与 VPN 错误码，补截图后排查。",
         ["kb://it/network"]),
        (["会议室", "预订"], "开场 15 分钟内可签到；临期取消扣信用 1。",
         ["kb://admin/room"]),
        (["采购", "比价"], "采购需至少 2 家比价后提交审批，验收后可挂报销。",
         ["kb://admin/purchase"]),
    ]
    for kws, ans, cites in cards:
        if any(k in q for k in kws):
            return {"degraded": False, "answer": ans, "citations": cites,
                    "source": "kb", "suggest_ticket": False, "conf": 0.8}
    return {"degraded": False,
            "answer": "未找到可靠引用，已建议转人工/建单。",
            "citations": [], "source": "none", "suggest_ticket": True, "conf": 0.2}

def qc_sample_report(limit: int = 20) -> dict:
    """A5：抽最近已关工单，规则打分（标题完整/有解决说明/非秒关），只读报告。"""
    if not is_enabled():
        return {"degraded": True, "samples": [], "avg_score": None, "reason": "ai_disabled"}
    try:
        from app.database import get_db
        db = get_db()
        try:
            with db.cursor() as cur:
                cur.execute(
                    """SELECT ticket_no, title, status, resolution, created_at, updated_at
                       FROM sf_ticket WHERE status IN ('closed','resolved')
                       ORDER BY id DESC LIMIT %s""", (int(limit),))
                rows = cur.fetchall()
        finally:
            db.close()
    except Exception as e:
        return {"degraded": True, "samples": [], "avg_score": None, "reason": str(e)[:120]}
    samples = []
    scores = []
    for r in rows:
        score = 50
        title = (r.get("title") or "").strip()
        if len(title) >= 6:
            score += 20
        if r.get("resolution"):
            score += 20
        # 秒关（创建到更新 < 1min）扣分
        try:
            if r.get("created_at") and r.get("updated_at"):
                delta = (r["updated_at"] - r["created_at"]).total_seconds()
                if delta < 60:
                    score -= 15
        except Exception:
            pass
        score = max(0, min(100, score))
        scores.append(score)
        samples.append({"ticketNo": r.get("ticket_no"), "title": title[:40],
                        "score": score, "status": r.get("status")})
    avg = round(sum(scores) / len(scores), 1) if scores else None
    return {"degraded": False, "samples": samples, "avg_score": avg,
            "n": len(samples), "reason": "rule_qc", "auto_send": False}

def insight_report(days: int = 7) -> dict:
    """A6：近 N 天工单/请假/报销聚合洞察（只读）。"""
    if not is_enabled():
        return {"degraded": True, "period_days": days, "sections": [], "reason": "ai_disabled"}
    try:
        from app.database import get_db
        db = get_db()
        try:
            with db.cursor() as cur:
                cur.execute(
                    """SELECT category, COUNT(*) n FROM sf_ticket
                       WHERE created_at >= DATE_SUB(NOW(), INTERVAL %s DAY)
                       GROUP BY category ORDER BY n DESC LIMIT 8""", (int(days),))
                cats = [{"category": r["category"], "count": int(r["n"])} for r in cur.fetchall()]
                cur.execute(
                    """SELECT status, COUNT(*) n FROM sf_leave
                       WHERE created_at >= DATE_SUB(NOW(), INTERVAL %s DAY)
                       GROUP BY status""", (int(days),))
                leaves = [{"status": r["status"], "count": int(r["n"])} for r in cur.fetchall()]
                cur.execute(
                    """SELECT status, COUNT(*) n, SUM(amount_total) amt FROM sf_expense
                       WHERE created_at >= DATE_SUB(NOW(), INTERVAL %s DAY)
                       GROUP BY status""", (int(days),))
                exps = [{"status": r["status"], "count": int(r["n"]),
                         "amount": float(r["amt"] or 0)} for r in cur.fetchall()]
        finally:
            db.close()
    except Exception as e:
        return {"degraded": True, "period_days": days, "sections": [], "reason": str(e)[:160]}

    top = cats[0]["category"] if cats else "-"
    sections = [
        {"title": "工单分类 Top", "items": cats,
         "insight": f"近 {days} 天最多：{top}" if cats else "无新工单"},
        {"title": "请假状态", "items": leaves,
         "insight": "在途看 pending_*，已结看 approved/effective"},
        {"title": "报销状态与金额", "items": exps,
         "insight": "关注 pending 金额与 paid 合计"},
    ]
    return {"degraded": False, "period_days": days, "sections": sections,
            "auto_send": False, "reason": "sql_aggregate"}
