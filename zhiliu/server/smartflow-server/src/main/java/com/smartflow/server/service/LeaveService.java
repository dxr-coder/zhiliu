package com.smartflow.server.service;

import static com.smartflow.server.service.NumberGen.genNo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.ApprovalTemplate;
import com.smartflow.pojo.entity.LeaveRequest;
import com.smartflow.pojo.entity.LeaveType;
import com.smartflow.server.approval.ApprovalEngine;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.ApprovalTemplateMapper;
import com.smartflow.server.mapper.LeaveMapper;
import com.smartflow.server.mapper.LeaveTypeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LeaveService {

    private final LeaveMapper leaveMapper;
    private final LeaveTypeMapper leaveTypeMapper;
    private final ApprovalTemplateMapper templateMapper;
    private final ApprovalEngine approvalEngine;

    public LeaveService(LeaveMapper leaveMapper,
                        LeaveTypeMapper leaveTypeMapper,
                        ApprovalTemplateMapper templateMapper,
                        ApprovalEngine approvalEngine) {
        this.leaveMapper = leaveMapper;
        this.leaveTypeMapper = leaveTypeMapper;
        this.templateMapper = templateMapper;
        this.approvalEngine = approvalEngine;
    }

    @Transactional
    public LeaveRequest submit(LoginUser user, LeaveRequest req) {
        if (req.getStartDate() == null || req.getEndDate() == null) {
            throw new BusinessException("起止日期必填");
        }
        if (req.getEndDate().isBefore(req.getStartDate())) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BusinessException("事由必填");
        }
        LeaveType type = leaveTypeMapper.selectOne(new LambdaQueryWrapper<LeaveType>()
                .eq(LeaveType::getCode, req.getType())
                .eq(LeaveType::getStatus, "active"));
        if (type == null) {
            throw new BusinessException("假种不存在或已停用: " + req.getType());
        }

        long days = ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1;
        req.setDays(BigDecimal.valueOf(days));

        // 重叠校验
        Long overlap = leaveMapper.selectCount(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getUserId, user.uid())
                .in(LeaveRequest::getStatus, "submitted", "pending_manager", "pending_hr", "approved", "effective")
                .le(LeaveRequest::getStartDate, req.getEndDate())
                .ge(LeaveRequest::getEndDate, req.getStartDate()));
        if (overlap != null && overlap > 0) {
            throw new BusinessException(409, "与已有假期区间重叠");
        }

        // B1 余额：假种 needBalance 时只做占位校验（ledger B2）
        if (Boolean.TRUE.equals(type.getNeedBalance())) {
            // 演示：固定余额 10 天，不足则拒
            BigDecimal demoBalance = BigDecimal.TEN;
            if (req.getDays().compareTo(demoBalance) > 0) {
                throw new BusinessException(400, "余额不足（演示余额 10 天）");
            }
            req.setBalanceBefore(demoBalance);
            req.setBalanceHold(req.getDays());
            req.setBalanceAfter(demoBalance.subtract(req.getDays()));
        }

        req.setUserId(user.uid());
        req.setDeptId(user.deptId());
        req.setAiDraft(Boolean.TRUE.equals(req.getAiDraft()));
        req.setStatus("draft");
        req.setLeaveNo(genNo("L"));
        leaveMapper.insert(req);

        // 链快照：按天数决定是否带 HR（读 chainPolicy）
        String chain = buildChain(type, req.getDays());
        req.setChainSnapshot(chain);

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("days", req.getDays());
        Long instId = approvalEngine.start("leave", req.getId(), user.uid(), chain, ctx);
        req.setApprovalInstanceId(instId);
        req.setStatus("pending_manager");
        leaveMapper.updateById(req);
        return req;
    }

    @Transactional
    public LeaveRequest approveStep(LoginUser user, Long leaveId, boolean agree, String comment) {
        LeaveRequest leave = leaveMapper.selectById(leaveId);
        if (leave == null) {
            throw new BusinessException(404, "请假单不存在");
        }
        if (!leave.getStatus().startsWith("pending")) {
            throw new BusinessException(409, "当前状态不可审批: " + leave.getStatus());
        }
        if (agree) {
            boolean done = approvalEngine.agree(leave.getApprovalInstanceId(), user.uid(), comment);
            // 重读实例状态由引擎保证；单据状态：有 pending_hr 则 pending_hr，否则 approved
            if (done) {
                leave.setStatus("approved");
            } else {
                // 还有下一步：简单置 pending_hr（B1 单链 manager->hr）
                leave.setStatus("pending_hr");
            }
        } else {
            approvalEngine.reject(leave.getApprovalInstanceId(), user.uid(), comment);
            leave.setStatus("rejected");
        }
        leaveMapper.updateById(leave);
        return leave;
    }

    @Transactional
    public LeaveRequest withdraw(LoginUser user, Long leaveId) {
        LeaveRequest leave = leaveMapper.selectById(leaveId);
        if (leave == null || !leave.getUserId().equals(user.uid())) {
            throw new BusinessException(404, "请假单不存在");
        }
        if (!leave.getStatus().startsWith("pending") && !"submitted".equals(leave.getStatus())) {
            throw new BusinessException(409, "仅提交/审批中可撤回");
        }
        leave.setStatus("cancelled");
        leaveMapper.updateById(leave);
        return leave;
    }

    /** 列表五件套：分页+关键字+状态。 */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<LeaveRequest> pageQuery(
            com.smartflow.common.PageQuery q) {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<LeaveRequest>(
                q.pageNo(), q.pageSize());
        var qw = new LambdaQueryWrapper<LeaveRequest>()
                .eq(q.hasStatus(), LeaveRequest::getStatus, q.status())
                .and(q.hasKeyword(), w -> w
                        .like(LeaveRequest::getLeaveNo, q.keyword())
                        .or()
                        .like(LeaveRequest::getReason, q.keyword()))
                .orderByDesc(LeaveRequest::getCreatedAt);
        return leaveMapper.selectPage(page, qw);
    }

    public List<LeaveRequest> myList(LoginUser user) {
        return leaveMapper.selectList(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getUserId, user.uid())
                .orderByDesc(LeaveRequest::getCreatedAt));
    }

    
    private String buildChain(LeaveType type, BigDecimal days) {
        boolean needHr = "annual".equals(type.getCode()) && days.compareTo(BigDecimal.valueOf(3)) > 0
                || "sick".equals(type.getCode());
        ApprovalTemplate tpl = templateMapper.selectOne(new LambdaQueryWrapper<ApprovalTemplate>()
                .eq(ApprovalTemplate::getBizType, "leave")
                .eq(ApprovalTemplate::getStatus, "active")
                .orderByDesc(ApprovalTemplate::getVersion)
                .last("LIMIT 1"));
        String raw = tpl != null && tpl.getStepsJson() != null && !tpl.getStepsJson().isBlank()
                ? tpl.getStepsJson()
                : defaultLeaveSteps();
        return filterHrStep(raw, needHr);
    }

    private String defaultLeaveSteps() {
        return "[{\"step\":1,\"role\":\"manager\",\"mode\":\"serial\"},"
                + "{\"step\":2,\"role\":\"hr\",\"mode\":\"serial\"}]";
    }

    /** 模板含 hr 步时按 needHr 保留/剔除；保留时去掉 skipWhen，避免病假短单被跳过人事。 */
    private String filterHrStep(String stepsJson, boolean needHr) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var list = mapper.readValue(stepsJson, java.util.List.class);
            var kept = new java.util.ArrayList<Map<String, Object>>();
            for (Object o : list) {
                @SuppressWarnings("unchecked")
                Map<String, Object> s = (Map<String, Object>) o;
                boolean isHr = "hr".equals(String.valueOf(s.get("role")));
                if (isHr && !needHr) {
                    continue;
                }
                if (isHr && needHr) {
                    s.remove("skipWhen");
                }
                kept.add(s);
            }
            if (kept.isEmpty()) {
                kept.add(java.util.Map.of("step", 1, "role", "manager", "mode", "serial"));
            }
            return mapper.writeValueAsString(kept);
        } catch (Exception e) {
            return needHr
                    ? "[{\"step\":1,\"role\":\"manager\",\"mode\":\"serial\"},{\"step\":2,\"role\":\"hr\",\"mode\":\"serial\"}]"
                    : "[{\"step\":1,\"role\":\"manager\",\"mode\":\"serial\"}]";
        }
    }
}
