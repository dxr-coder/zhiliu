"""数据库连接管理 — DBUtils 连接池版"""
import os
from dbutils.pooled_db import PooledDB
import pymysql
from contextlib import contextmanager

_pool = None

def _get_pool():
    """懒加载连接池单例（第一次用时创建，之后复用）"""
    global _pool
    if _pool is None:
        _pool = PooledDB(
            creator=pymysql,  # 底层用 pymysql 驱动
            maxconnections=20,  # 池里最多 20 个连接（压测并发别超过这个数）
            host=os.getenv("DB_HOST", "localhost"),
            user=os.getenv("DB_USER", "root"),
            password=os.getenv("MYSQL_PASSWORD", ""),
            database=os.getenv("DB_NAME", "mianshi"),
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            blocking=True,  # 池满时等待而不是直接报错
            maxusage=None,  # 连接可无限复用（不自动断开）
            ping=1,  # 取连接时先 SELECT 1 探活，断了自动重连
        )
    return _pool

def get_db():
    """从池里取连接；调用方必须 close() 归还（不是真断开，是还回池里）
    40+ 个调用点不用改——.cursor()/.commit()/.rollback()/.close() 接口完全兼容
    """
    return _get_pool().connection()

@contextmanager
def db_session():
    """with db_session() as cur: 自动拿连接、开 cursor、commit、close"""
    db = get_db()
    try:
        with db.cursor() as cur:
            yield cur
        db.commit()
    finally:
        db.close()
