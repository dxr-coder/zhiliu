"""工具注册中心"""
from app.logger import get_logger

log = get_logger(__name__)

TOOLS = {}  # 工具名 → {"schema": {...}, "func": callable}

def register_tool(name: str, schema: dict, func):
    """
        注册一个工具。
        参数：
            name: 工具名
            schema: {"description": "...", "parameters": {...}}
            func: 执行函数
    """
    TOOLS[name] = {'schema': schema, 'func': func}
    log.debug("注册工具", extra={"tool": name})

def get_tools_schema() -> list:
    """
    返回 OpenAI Function Calling 格式的工具列表。
    格式：
    [
        {"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}},
        ...
    ]
    """
    schemas = []
    for name, info in TOOLS.items():
        schema = info['schema']
        schemas.append({
            "type": "function",
            "function": {
                "name": name,
                "description": schema.get("description", ""),
                "parameters": schema.get("parameters", {"type": "object", "properties": {}}),
            }
        })
    return schemas

def execute_tool(name: str, params: dict) -> dict:
    """
        统一执行入口。
        参数：
            name: 工具名
            params: 工具参数（字典）
        返回：
            成功：工具函数的返回值
            失败：{"error": "错误信息"}
    """
    if name not in TOOLS:
        return {"error": f"未知工具: {name}"}

    func = TOOLS[name]['func']
    try:
        result = func(**params)
        log.info("工具执行成功", extra={"tool": name})
        return result
    except Exception as e:
        log.error("工具执行失败", extra={"tool": name, "err": str(e)[:200]})

