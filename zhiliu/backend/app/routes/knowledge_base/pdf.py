"""PDF 上传路由"""

import os
import uuid
from fastapi import APIRouter, Depends, UploadFile, File
from app.deps import get_current_user, require_role
from app.services.knowledge_base.pdf import extract_text_from_pdf
from fastapi.responses import JSONResponse

router = APIRouter(prefix="/pdf", tags=["PDF解析"])

UPLOAD_DIR = "var/uploads"

CHUNK_SIZE = 1024 * 1024   # 1MB 分块
MAX_UPLOAD_SIZE = 300 * 1024 * 1024   # 300MB 上限

@router.post('/upload')
async def upload_pdf(file: UploadFile = File(...), user=Depends(require_role('admin'))):
    """上传 PDF → 流式落盘（1MB 分块，边写边查大小，不把整个文件读进内存）"""
    if not file.filename.endswith('.pdf'):
        return {"error": "只支持 PDF 文件"}

    os.makedirs(UPLOAD_DIR, exist_ok=True)
    file_path = os.path.join(UPLOAD_DIR, f"{uuid.uuid4()}.pdf")

    # 流式落盘：每次读 1MB 写 1MB，峰值内存只有 1MB 而不是整个文件
    total = 0
    try:
        with open(file_path, 'wb') as f:
            while chunk := await file.read(CHUNK_SIZE):
                total += len(chunk)
                if total > MAX_UPLOAD_SIZE:
                    # 超限：删掉半成品文件，返回 413（Payload Too Large）
                    f.close()
                    os.remove(file_path)
                    return JSONResponse(status_code=413, content={"error": "文件超过 300MB 限制"})
                f.write(chunk)
    except Exception:
        # 写盘失败（磁盘满/权限等）：清理半成品
        if os.path.exists(file_path):
            os.remove(file_path)
        raise

    text = extract_text_from_pdf(file_path)
    return {"filename": file.filename, "text_length": len(text), "text_preview": text[:500]}

