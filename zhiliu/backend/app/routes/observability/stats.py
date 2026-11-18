"""
数据统计路由

功能：提供管理后台看板的统计数据接口。
"""
from fastapi import APIRouter, Depends
from app.deps import require_role
from app.services.observability.stats import get_overview, get_trends
from app.services.observability.trace_store import get_trace

router = APIRouter(prefix="/stats", tags=["数据统计"])


@router.get("/overview")
def overview_route(user=Depends(require_role('admin'))):
    # 看板总览：工单量/解决率等汇总数字
    return get_overview()


@router.get("/trends")
def trends_route(days: int = 7, user=Depends(require_role('admin'))):
    # 趋势图数据，默认取最近 7 天
    return get_trends(days)


@router.get("/trace/{trace_id}")
def get_trace_route(trace_id: str, user=Depends(require_role('admin'))):
    # 按 trace_id 查一条请求链路（排查用，仅 admin 可见：含 user_id/路径/错误原文）
    return get_trace(trace_id)
