"""审计日志路由"""
from fastapi import APIRouter, Depends
from app.deps import require_role
from app.services.observability.auditor import get_audit_logs

router = APIRouter(prefix="/audit", tags=["审计日志"])

@router.get("/logs")
def get_logs_route(page: int = 1, user=Depends(require_role('admin'))):
    """分页看审计日志"""
    return get_audit_logs(page=page)  # 只传页码，每页多少条 service 里定