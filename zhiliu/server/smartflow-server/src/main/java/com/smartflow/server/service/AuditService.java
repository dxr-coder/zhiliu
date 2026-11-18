package com.smartflow.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.pojo.entity.AuditLog;
import com.smartflow.server.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(Long operatorId, String action, String targetType, String targetId,
                       String beforeJson, String afterJson, String traceId, String ip) {
        AuditLog log = new AuditLog();
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setBeforeJson(toJson(beforeJson));
        log.setAfterJson(toJson(afterJson));
        log.setTraceId(traceId);
        log.setIp(ip);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    /** DB 列是 JSON：裸字符串包成合法 JSON 字符串；已是 JSON 的原样。 */
    private static String toJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
                || (t.startsWith("\"") && t.endsWith("\""))) {
            return t;
        }
        return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public List<AuditLog> search(String action, Long operatorId, int limit) {
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(operatorId != null, AuditLog::getOperatorId, operatorId)
                .orderByDesc(AuditLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 500)));
    }
}
