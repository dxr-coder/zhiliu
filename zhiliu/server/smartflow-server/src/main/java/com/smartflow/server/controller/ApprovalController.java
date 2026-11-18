package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.ApprovalInstance;
import com.smartflow.pojo.entity.ApprovalTask;
import com.smartflow.pojo.entity.Expense;
import com.smartflow.pojo.entity.LeaveRequest;
import com.smartflow.pojo.entity.PurchaseRequest;
import com.smartflow.pojo.entity.Ticket;
import com.smartflow.server.approval.ApprovalEngine;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.ApprovalInstanceMapper;
import com.smartflow.server.mapper.ExpenseMapper;
import com.smartflow.server.mapper.LeaveMapper;
import com.smartflow.server.mapper.PurchaseRequestMapper;
import com.smartflow.server.mapper.TicketMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalEngine approvalEngine;
    private final ApprovalInstanceMapper instanceMapper;
    private final LeaveMapper leaveMapper;
    private final ExpenseMapper expenseMapper;
    private final PurchaseRequestMapper purchaseMapper;
    private final TicketMapper ticketMapper;

    public ApprovalController(ApprovalEngine approvalEngine,
                              ApprovalInstanceMapper instanceMapper,
                              LeaveMapper leaveMapper,
                              ExpenseMapper expenseMapper,
                              PurchaseRequestMapper purchaseMapper,
                              TicketMapper ticketMapper) {
        this.approvalEngine = approvalEngine;
        this.instanceMapper = instanceMapper;
        this.leaveMapper = leaveMapper;
        this.expenseMapper = expenseMapper;
        this.purchaseMapper = purchaseMapper;
        this.ticketMapper = ticketMapper;
    }

    @GetMapping("/todo")
    public Result<List<Map<String, Object>>> todo(HttpServletRequest request) {
        LoginUser u = LoginUser.require(request);
        List<ApprovalTask> tasks = approvalEngine.todoOf(u.uid());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApprovalTask t : tasks) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("instanceId", t.getInstanceId());
            m.put("step", t.getStep());
            m.put("comment", t.getComment());
            ApprovalInstance inst = t.getInstanceId() == null ? null
                    : instanceMapper.selectById(t.getInstanceId());
            String bizType = inst == null ? null : inst.getBizType();
            Long bizId = inst == null ? null : inst.getBizId();
            m.put("bizType", bizType);
            m.put("bizId", bizId);
            m.put("summary", summarize(bizType, bizId));
            out.add(m);
        }
        return Result.ok(out);
    }

    private String summarize(String bizType, Long bizId) {
        if (bizType == null || bizId == null) {
            return "";
        }
        try {
            if ("leave".equals(bizType)) {
                LeaveRequest l = leaveMapper.selectById(bizId);
                if (l == null) return "请假单#" + bizId;
                return "请假 " + l.getLeaveNo() + " " + l.getType() + " "
                    + l.getStartDate() + "~" + l.getEndDate() + " "
                    + l.getDays() + "天 " + nz(l.getReason());
            }
            if ("expense".equals(bizType)) {
                Expense e = expenseMapper.selectById(bizId);
                if (e == null) return "报销单#" + bizId;
                return "报销 " + e.getExpenseNo() + " ¥" + e.getAmountTotal() + " " + nz(e.getType());
            }
            if ("purchase".equals(bizType)) {
                PurchaseRequest pr = purchaseMapper.selectById(bizId);
                if (pr == null) return "采购#" + bizId;
                return "采购 " + pr.getDemandNo() + " " + pr.getTitle()
                    + " 预算 " + (pr.getBudget() == null ? "未填" : pr.getBudget());
            }
            if ("ticket".equals(bizType)) {
                Ticket tk = ticketMapper.selectById(bizId);
                if (tk == null) return "工单#" + bizId;
                return "工单 " + tk.getTicketNo() + " " + tk.getTitle();
            }
            return bizType + "#" + bizId;
        } catch (Exception e) {
            return bizType + "#" + bizId;
        }
    }

    private String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    @PostMapping("/{instanceId}/agree")
    public Result<Void> agree(HttpServletRequest request,
                              @PathVariable Long instanceId,
                              @RequestBody(required = false) Map<String, Object> body) {
        LoginUser u = LoginUser.require(request);
        String comment = body == null || body.get("comment") == null
                ? null : String.valueOf(body.get("comment"));
        approvalEngine.agree(instanceId, u.uid(), comment);
        return Result.ok();
    }

    @PostMapping("/{instanceId}/reject")
    public Result<Void> reject(HttpServletRequest request,
                               @PathVariable Long instanceId,
                               @RequestBody(required = false) Map<String, Object> body) {
        LoginUser u = LoginUser.require(request);
        String comment = body == null || body.get("comment") == null
                ? null : String.valueOf(body.get("comment"));
        approvalEngine.reject(instanceId, u.uid(), comment);
        return Result.ok();
    }

    @PostMapping("/{instanceId}/transfer")
    public Result<Void> transfer(HttpServletRequest request,
                                 @PathVariable Long instanceId,
                                 @RequestBody Map<String, Object> body) {
        LoginUser u = LoginUser.require(request);
        Long to = Long.parseLong(String.valueOf(body.get("toUserId")));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        approvalEngine.transfer(instanceId, u.uid(), to, comment);
        return Result.ok();
    }

    @PostMapping("/{instanceId}/add-sign")
    public Result<Void> addSign(HttpServletRequest request,
                                @PathVariable Long instanceId,
                                @RequestBody Map<String, Object> body) {
        LoginUser u = LoginUser.require(request);
        Long to = Long.parseLong(String.valueOf(body.get("toUserId")));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        approvalEngine.addSign(instanceId, u.uid(), to, comment);
        return Result.ok();
    }
}
