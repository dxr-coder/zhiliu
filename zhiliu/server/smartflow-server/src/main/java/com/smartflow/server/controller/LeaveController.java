package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.LeaveRequest;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.LeaveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping
    public Result<?> page(@org.springframework.web.bind.annotation.ModelAttribute com.smartflow.common.PageQuery q) {
        var page = leaveService.pageQuery(q);
        return Result.ok(com.smartflow.common.PageResult.of(page.getTotal(), page.getRecords()));
    }

    @PostMapping
    public Result<LeaveRequest> submit(HttpServletRequest request, @RequestBody LeaveRequest body) {
        return Result.ok(leaveService.submit(LoginUser.require(request), body));
    }

    @GetMapping("/my")
    public Result<List<LeaveRequest>> my(HttpServletRequest request) {
        return Result.ok(leaveService.myList(LoginUser.require(request)));
    }

    /** 审批：agree/reject；leaveId 路径 + comment body。 */
    @PostMapping("/{id}/approve")
    public Result<LeaveRequest> approve(HttpServletRequest request,
                                        @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        boolean agree = Boolean.TRUE.equals(body.get("agree"));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        return Result.ok(leaveService.approveStep(LoginUser.require(request), id, agree, comment));
    }

    @PostMapping("/{id}/withdraw")
    public Result<LeaveRequest> withdraw(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(leaveService.withdraw(LoginUser.require(request), id));
    }
}
