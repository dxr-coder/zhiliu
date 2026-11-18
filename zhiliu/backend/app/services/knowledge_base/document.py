"""
知识库文档服务

功能：管理员管理知识库文档。
"""
from app.database import get_db
from fastapi import UploadFile
from app.services.knowledge_base.chunk import split_text
from app.services.knowledge_base.cleaner import clean_text
from app.services.knowledge_base.parser import parse_file
from app.services.rag.vector_store import add_documents, delete_by_doc_id


def get_documents():
    # 文档列表按上传时间倒序，最新的排最前
    db = get_db()
    try:
        with db.cursor() as cur:
            cur.execute("""
                        SELECT id,
                               filename,
                               upload_time,
                               status,
                               chunk_count,
                               department,
                               version,
                               is_latest
                        FROM documents
                        ORDER BY upload_time DESC
                        """)

            return cur.fetchall()
    finally:
        db.close()


def delete_document(doc_id: int):
    """
    删除文档：MySQL → Chroma。
    顺序说明：先删 MySQL（事务保证原子性），再删 Chroma。
    如果 Chroma 删除失败，MySQL 已经删了，残留向量不会被 is_latest 过滤（因为 metadata 里 doc_id 对应的 MySQL 记录已不存在）。
    最坏情况：Chroma 里留几条孤儿向量，占用空间但不影响检索结果（hybrid_search 会 JOIN documents 过滤）。
    """
    # 1. 先删 MySQL（ON DELETE CASCADE 会自动删 document_chunks）
    db = get_db()
    try:
        with db.cursor() as cur:
            cur.execute("DELETE FROM documents WHERE id = %s", (doc_id,))
        db.commit()
    finally:
        db.close()

    # 2. 再删 ChromaDB 向量（失败不影响主流程，记日志即可）
    try:
        delete_by_doc_id(doc_id)
    except Exception as e:
        # 孤儿向量不影响正确性（检索时会 JOIN documents 过滤），只占空间
        print(f"[WARN] Chroma delete failed for doc_id={doc_id}: {e}")

    return {"msg": "文档已删除"}


def upload_document(file: UploadFile, department: str = None):
    """上传文档：解析 → 清洗 → 切片 → 向量化 → 存储"""
    content = file.file.read()

    # 大小限制 300MB
    if len(content) > 300 * 1024 * 1024:
        return {"error": "文件大小不能超过 300MB"}

    # 1.解析（支持多格式）
    try:
        text = parse_file(file.filename, content)
    except ValueError as e:
        return {"error": str(e)}
    except Exception as e:
        import traceback
        traceback.print_exc()
        return {"error": f"文件解析失败：{str(e)}"}

    if not text.strip():
        return {"error": "文档内容为空"}

    # 2.数据清洗
    text = clean_text(text)

    # 3.智能切片
    chunks = split_text(text)
    if not chunks:
        return {"error": "切片后内容为空"}

    # 4. 存 MySQL + ChromaDB
    db = get_db()
    try:
        with db.cursor() as cur:
            # 查询同名文档的最新版本号（DictCursor 按键取值；MAX 为 NULL 时回落 "0"）
            cur.execute("SELECT MAX(version) AS v FROM documents WHERE filename = %s", (file.filename,))
            row = cur.fetchone()
            old_version = (row["v"] if row and row["v"] else None) or "0"
            # 版本号 +1（支持 "1.0" → "1.1" 这种格式）
            try:
                major, minor = old_version.split(".")
                new_version = f"{major}.{int(minor) + 1}"
            except ValueError:
                new_version = "1.0"

            # 收集旧版本 doc_id：置非最新后，对应向量也要从 Chroma 删除，
            # 否则旧版本文本会带着 is_latest=true 继续参与语义检索（版本污染）
            cur.execute("SELECT id FROM documents WHERE filename = %s AND is_latest = 1", (file.filename,))
            old_doc_ids = [r["id"] for r in cur.fetchall()]
            cur.execute("UPDATE documents SET is_latest = 0 WHERE filename = %s", (file.filename,))

            # 插入新版
            cur.execute(
                "INSERT INTO documents (filename, status, chunk_count, department, version, is_latest) VALUES (%s, 'processing', %s, %s, %s, 1)",
                (file.filename, len(chunks), department or 'all', new_version),
            )
            doc_id = cur.lastrowid

            # executemany：100 个 chunk 从 100 次网络往返压成 1 次
            cur.executemany(
                "INSERT INTO document_chunks (document_id, chunk_index, content) VALUES (%s, %s, %s)",
                [(doc_id, i, chunk) for i, chunk in enumerate(chunks)],
            )

            cur.execute("UPDATE documents SET status = 'active' WHERE id = %s", (doc_id,))
        db.commit()
    finally:
        db.close()

    # MySQL 落定后再动 Chroma：先清旧版本向量，再写入新版向量
    # 双写一致性保护：Chroma 写入失败时，把文档标记为 failed，避免"看起来 active 但检索不到"
    try:
        for old_id in old_doc_ids:
            delete_by_doc_id(old_id)
        add_documents(doc_id, chunks, department=department)
    except Exception as chroma_err:
        # Chroma 写入失败 → 标记为 failed（而不是留着 active 状态假装成功）
        # 运维看到 failed 状态可以手动重试或删除
        try:
            db = get_db()
            try:
                with db.cursor() as cur:
                    cur.execute("UPDATE documents SET status = 'failed' WHERE id = %s", (doc_id,))
                db.commit()
            finally:
                db.close()
        except Exception:
            pass  # 标记失败也失败了，就让它保持 processing 状态，人工排查
        return {"error": f"向量写入失败，文档已标记为 failed：{str(chroma_err)}", "doc_id": doc_id}

    return {"msg": "上传成功", "doc_id": doc_id, "chunk_count": len(chunks)}


def get_document_versions(doc_id: int):
    """查询某个文档的所有历史版本"""
    db = get_db()
    try:
        with db.cursor() as cur:
            # 先查文件名
            cur.execute("SELECT filename FROM documents WHERE id = %s", (doc_id,))
            row = cur.fetchone()
            if not row:
                return []
            filename = row["filename"]
            # 查同名文档的所有版本
            cur.execute("""
                        SELECT id, filename, version, department, is_latest, upload_time
                        FROM documents
                        WHERE filename = %s
                        ORDER BY upload_time DESC
                        """, (filename,))
            return cur.fetchall()
    finally:
        db.close()


def check_consistency():
    """
    检查 MySQL 与 ChromaDB 双写一致性。
    返回三类不一致：
      1. failed 状态的文档（Chroma 写入失败，需要人工处理）
      2. MySQL 有记录但 Chroma 无向量（检索不到，需要重试向量化）
      3. Chroma 有向量但 MySQL 无记录（孤儿向量，占用空间但不影响功能）
    用途：运维巡检接口或定时任务调用。
    """
    from app.services.rag.vector_store import _get_collection

    issues = {"failed_documents": [], "missing_vectors": [], "orphan_vectors": []}

    # 1. 查 failed 状态的文档
    db = get_db()
    try:
        with db.cursor() as cur:
            cur.execute("SELECT id, filename FROM documents WHERE status = 'failed'")
            issues["failed_documents"] = cur.fetchall()

            # 2. 查 active 状态但可能缺向量的文档（简化版：查 chunk_count > 0 的 active 文档）
            cur.execute("""
                        SELECT d.id, d.filename, d.chunk_count
                        FROM documents d
                        WHERE d.status = 'active' AND d.is_latest = 1
                        """)
            active_docs = cur.fetchall()
    finally:
        db.close()

    # 检查每个 active 文档在 Chroma 里是否有向量
    collection = _get_collection()
    for doc in active_docs:
        results = collection.get(where={"doc_id": doc["id"]}, limit=1)
        if not results["ids"]:
            issues["missing_vectors"].append(doc)

    # 3. 检查孤儿向量（Chroma 有但 MySQL 无）
    all_chroma = collection.get()
    mysql_doc_ids = set()
    db = get_db()
    try:
        with db.cursor() as cur:
            cur.execute("SELECT id FROM documents")
            mysql_doc_ids = {row["id"] for row in cur.fetchall()}
    finally:
        db.close()

    chroma_doc_ids = set()
    for metadata in all_chroma.get("metadatas", []):
        if metadata and "doc_id" in metadata:
            chroma_doc_ids.add(metadata["doc_id"])

    orphan_ids = chroma_doc_ids - mysql_doc_ids
    issues["orphan_vectors"] = [{"doc_id": oid} for oid in orphan_ids]

    return issues


def cleanup_orphan_vectors():
    """
    清理孤儿向量（Chroma 有但 MySQL 无记录的向量）。
    返回删除的 doc_id 列表。
    """
    from app.services.rag.vector_store import _get_collection

    collection = _get_collection()
    all_chroma = collection.get()

    # 收集所有 Chroma 里的 doc_id
    chroma_doc_ids = set()
    for metadata in all_chroma.get("metadatas", []):
        if metadata and "doc_id" in metadata:
            chroma_doc_ids.add(metadata["doc_id"])

    # 查 MySQL 里存在的 doc_id
    mysql_doc_ids = set()
    db = get_db()
    try:
        with db.cursor() as cur:
            cur.execute("SELECT id FROM documents")
            mysql_doc_ids = {row["id"] for row in cur.fetchall()}
    finally:
        db.close()

    # 删除孤儿
    orphan_ids = chroma_doc_ids - mysql_doc_ids
    for doc_id in orphan_ids:
        delete_by_doc_id(doc_id)

    return list(orphan_ids)
