"""AI 六点位 HTTP 接口（接口冻结：/ai/v1/*）。"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Depends
from app.deps import get_current_user
from pydantic import BaseModel

from app.services.ai import points as ai_points

router = APIRouter(prefix="/ai/v1", tags=["ai-points"])

class TriageReq(BaseModel):
    text: str

class DraftReq(BaseModel):
    biz_type: str  # leave | expense
    utterance: str = ""

class AssistReq(BaseModel):
    title: str = ""
    body: str = ""

class SwitchReq(BaseModel):
    enabled: bool

@router.post("/triage")
def api_triage(req: TriageReq):
    """A1：category/priority/conf；conf 低 -> human_queue=true。"""
    return ai_points.triage(req.text)

@router.post("/draft")
def api_draft(req: DraftReq):
    """A2：字段建议；missing 空字段人工补；调用方确认才提交。"""
    return ai_points.draft(req.biz_type, req.utterance)

@router.post("/assist")
def api_assist(req: AssistReq):
    """A4：回复草稿 + 建议动作；auto_send 恒 false。"""
    return ai_points.assist(req.title, req.body)

@router.get("/switch")
def get_switch():
    return {"enabled": ai_points.is_enabled()}

@router.post("/switch")
def set_switch(req: SwitchReq, user=Depends(get_current_user)):
    """全局降级：仅 admin；false 时点位全部 degraded，业务纯表单可用。"""
    if (user or {}).get("role") != "admin":
        raise HTTPException(status_code=403, detail="需要 admin 权限")
    ai_points.set_enabled(req.enabled)
    return {"enabled": ai_points.is_enabled()}

@router.post("/answer")
def answer(body: dict):
    """A3 RAG 问答（带 citations）；无引用 source=none，可建议建单。"""
    from app.services.ai.points import answer_rag
    q = (body or {}).get("question") or (body or {}).get("text") or ""
    return answer_rag(q)

@router.post("/qc_sample")
def qc_sample(body: dict, user=Depends(get_current_user)):
    """A5 质检抽检：对 closed/resolved 工单规则打分，只出报告不改单。"""
    if (user or {}).get("role") != "admin":
        raise HTTPException(status_code=403, detail="需要 admin 权限")
    from app.services.ai.points import qc_sample_report
    return qc_sample_report((body or {}).get("limit", 20))

@router.post("/insight")
def insight(body: dict, user=Depends(get_current_user)):
    """A6 运营洞察：只读周报聚类（工单分类/请假/报销摘要），不改单。"""
    if (user or {}).get("role") != "admin":
        raise HTTPException(status_code=403, detail="需要 admin 权限")
    from app.services.ai.points import insight_report
    return insight_report(int((body or {}).get("days", 7)))
