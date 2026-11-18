package com.smartflow.server.controller;

import com.smartflow.common.BusinessException;
import com.smartflow.common.Result;
import com.smartflow.pojo.entity.AuditLog;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 审计查询（admin）。 */
@RestController
@RequestMapping("/api/audits")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public Result<List<AuditLog>> search(HttpServletRequest request,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) Long operatorId,
                                         @RequestParam(defaultValue = "100") int limit) {
        LoginUser u = LoginUser.require(request);
        if (!u.isAdmin()) {
            throw new BusinessException(403, "仅 admin 可查审计");
        }
        return Result.ok(auditService.search(action, operatorId, limit));
    }
}
