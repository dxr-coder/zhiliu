package com.smartflow.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.common.Result;
import com.smartflow.pojo.entity.ApprovalTemplate;
import com.smartflow.pojo.entity.Dept;
import com.smartflow.pojo.entity.LeaveType;
import com.smartflow.pojo.entity.SlaPolicy;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.ApprovalTemplateMapper;
import com.smartflow.server.mapper.DeptMapper;
import com.smartflow.server.mapper.LeaveTypeMapper;
import com.smartflow.server.mapper.SlaPolicyMapper;
import com.smartflow.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LeaveTypeMapper leaveTypeMapper;
    private final SlaPolicyMapper slaPolicyMapper;
    private final ApprovalTemplateMapper templateMapper;
    private final DeptMapper deptMapper;
    private final AuditService auditService;
    private final com.smartflow.server.mapper.ExpensePolicyMapper expensePolicyMapper;
    private final com.smartflow.server.mapper.DeptBudgetMapper budgetMapper;
    private final com.smartflow.server.mapper.RoleMapper roleMapper;
    private final com.smartflow.server.mapper.AssetCategoryMapper assetCategoryMapper;
    private final com.smartflow.server.mapper.MeetingRoomMapper meetingRoomMapper;

    public ConfigController(LeaveTypeMapper leaveTypeMapper,
                            SlaPolicyMapper slaPolicyMapper,
                            ApprovalTemplateMapper templateMapper,
                            DeptMapper deptMapper,
                            AuditService auditService,
                            com.smartflow.server.mapper.ExpensePolicyMapper expensePolicyMapper,
                            com.smartflow.server.mapper.DeptBudgetMapper budgetMapper,
                            com.smartflow.server.mapper.RoleMapper roleMapper,
                            com.smartflow.server.mapper.AssetCategoryMapper assetCategoryMapper,
                            com.smartflow.server.mapper.MeetingRoomMapper meetingRoomMapper) {
        this.leaveTypeMapper = leaveTypeMapper;
        this.slaPolicyMapper = slaPolicyMapper;
        this.templateMapper = templateMapper;
        this.deptMapper = deptMapper;
        this.auditService = auditService;
        this.expensePolicyMapper = expensePolicyMapper;
        this.budgetMapper = budgetMapper;
        this.roleMapper = roleMapper;
        this.assetCategoryMapper = assetCategoryMapper;
        this.meetingRoomMapper = meetingRoomMapper;
    }

    private void requireAdmin(LoginUser u) {
        if (!u.isAdmin()) {
            throw new BusinessException(403, "仅 admin 可改配置");
        }
    }

    // ── 假种 ──
    @GetMapping("/leave-types")
    public Result<List<LeaveType>> listLeaveTypes() {
        return Result.ok(leaveTypeMapper.selectList(new LambdaQueryWrapper<LeaveType>()
                .orderByAsc(LeaveType::getId)));
    }

    @PostMapping("/leave-types")
    public Result<LeaveType> saveLeaveType(HttpServletRequest req, @RequestBody LeaveType body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getId() == null) {
            leaveTypeMapper.insert(body);
        } else {
            if (leaveTypeMapper.selectById(body.getId()) == null) {
                throw new BusinessException(404, "假种不存在");
            }
            leaveTypeMapper.updateById(body);
        }
        auditService.record(u.uid(), "config.leave_type.save", "leave_type",
                body.getCode(), null, body.getStatus(), null, null);
        return Result.ok(body);
    }

    // ── SLA ──
    @GetMapping("/sla-policies")
    public Result<List<SlaPolicy>> listSla() {
        return Result.ok(slaPolicyMapper.selectList(new LambdaQueryWrapper<SlaPolicy>()
                .orderByAsc(SlaPolicy::getPriority)));
    }

    @PostMapping("/sla-policies")
    public Result<SlaPolicy> saveSla(HttpServletRequest req, @RequestBody SlaPolicy body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getId() == null) {
            slaPolicyMapper.insert(body);
        } else {
            if (slaPolicyMapper.selectById(body.getId()) == null) {
                throw new BusinessException(404, "SLA 策略不存在");
            }
            slaPolicyMapper.updateById(body);
        }
        auditService.record(u.uid(), "config.sla.save", "sla_policy",
                body.getPriority(), null, String.valueOf(body.getResolveMin()), null, null);
        return Result.ok(body);
    }

    // ── 审批模板 ──
    @GetMapping("/approval-templates")
    public Result<List<ApprovalTemplate>> listTemplates(@RequestParam(required = false) String bizType) {
        return Result.ok(templateMapper.selectList(new LambdaQueryWrapper<ApprovalTemplate>()
                .eq(bizType != null && !bizType.isBlank(), ApprovalTemplate::getBizType, bizType)
                .orderByAsc(ApprovalTemplate::getBizType)
                .orderByDesc(ApprovalTemplate::getVersion)));
    }

    @PostMapping("/approval-templates")
    public Result<ApprovalTemplate> saveTemplate(HttpServletRequest req, @RequestBody ApprovalTemplate body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getStepsJson() == null || body.getStepsJson().isBlank()) {
            throw new BusinessException("stepsJson 必填");
        }
        if (body.getId() == null) {
            Integer max = templateMapper.selectList(new LambdaQueryWrapper<ApprovalTemplate>()
                            .eq(ApprovalTemplate::getBizType, body.getBizType()))
                    .stream().map(ApprovalTemplate::getVersion).max(Integer::compare).orElse(0);
            body.setVersion(max + 1);
            templateMapper.insert(body);
        } else {
            if (templateMapper.selectById(body.getId()) == null) {
                throw new BusinessException(404, "审批模板不存在");
            }
            templateMapper.updateById(body);
        }
        auditService.record(u.uid(), "config.template.save", "approval_template",
                body.getBizType() + " v" + body.getVersion(), null, body.getStatus(), null, null);
        return Result.ok(body);
    }

    // ── 部门 ──
    @GetMapping("/depts")
    public Result<List<Dept>> listDepts() {
        return Result.ok(deptMapper.selectList(new LambdaQueryWrapper<Dept>()
                .orderByAsc(Dept::getParentId).orderByAsc(Dept::getId)));
    }

    @PostMapping("/depts")
    public Result<Dept> saveDept(HttpServletRequest req, @RequestBody Dept body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getName() == null || body.getName().isBlank()) {
            throw new BusinessException("部门名必填");
        }
        if (body.getId() == null) {
            deptMapper.insert(body);
        } else {
            if (deptMapper.selectById(body.getId()) == null) {
                throw new BusinessException(404, "部门不存在");
            }
            deptMapper.updateById(body);
        }
        auditService.record(u.uid(), "config.dept.save", "dept",
                String.valueOf(body.getId()), null, body.getName(), null, null);
        return Result.ok(body);
    }

    // ── 报销标准 ──
    @GetMapping("/expense-policies")
    public Result<List<com.smartflow.pojo.entity.ExpensePolicy>> listExpensePolicies() {
        return Result.ok(expensePolicyMapper.selectList(
                new LambdaQueryWrapper<com.smartflow.pojo.entity.ExpensePolicy>()
                        .orderByAsc(com.smartflow.pojo.entity.ExpensePolicy::getType)));
    }

    @PostMapping("/expense-policies")
    public Result<com.smartflow.pojo.entity.ExpensePolicy> saveExpensePolicy(
            HttpServletRequest req, @RequestBody com.smartflow.pojo.entity.ExpensePolicy body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getType() == null || body.getType().isBlank()) {
            throw new BusinessException("type 必填");
        }
        var exist = expensePolicyMapper.selectOne(new LambdaQueryWrapper<com.smartflow.pojo.entity.ExpensePolicy>()
                .eq(com.smartflow.pojo.entity.ExpensePolicy::getType, body.getType()));
        if (exist != null) {
            body.setId(exist.getId());
            expensePolicyMapper.updateById(body);
        } else {
            expensePolicyMapper.insert(body);
        }
        auditService.record(u.uid(), "config.expense_policy.save", "expense_policy",
                body.getType(), null, body.getStatus(), null, null);
        return Result.ok(body);
    }

    // ── 部门预算 ──
    @GetMapping("/budgets")
    public Result<List<com.smartflow.pojo.entity.DeptBudget>> listBudgets() {
        return Result.ok(budgetMapper.selectList(new LambdaQueryWrapper<com.smartflow.pojo.entity.DeptBudget>()
                .orderByAsc(com.smartflow.pojo.entity.DeptBudget::getDeptId)
                .orderByDesc(com.smartflow.pojo.entity.DeptBudget::getPeriod)));
    }

    @PostMapping("/budgets")
    public Result<com.smartflow.pojo.entity.DeptBudget> saveBudget(
            HttpServletRequest req, @RequestBody com.smartflow.pojo.entity.DeptBudget body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getDeptId() == null || body.getPeriod() == null || body.getAmount() == null) {
            throw new BusinessException("deptId/period/amount 必填");
        }
        if (body.getId() == null) {
            budgetMapper.insert(body);
        } else {
            if (budgetMapper.selectById(body.getId()) == null) {
                throw new BusinessException(404, "预算记录不存在");
            }
            budgetMapper.updateById(body);
        }
        auditService.record(u.uid(), "config.budget.save", "dept_budget",
                body.getDeptId() + "/" + body.getPeriod(), null,
                String.valueOf(body.getAmount()), null, null);
        return Result.ok(body);
    }

    // ── 角色列表（矩阵只读；权限勾选 P1 简化为角色列表）──
    @GetMapping("/roles")
    public Result<List<com.smartflow.pojo.entity.Role>> listRoles() {
        return Result.ok(roleMapper.selectList(new LambdaQueryWrapper<com.smartflow.pojo.entity.Role>()
                .orderByAsc(com.smartflow.pojo.entity.Role::getId)));
    }

    // ── 资产分类 ──
    @GetMapping("/asset-categories")
    public Result<List<com.smartflow.pojo.entity.AssetCategory>> listAssetCategories() {
        return Result.ok(assetCategoryMapper.selectList(
                new LambdaQueryWrapper<com.smartflow.pojo.entity.AssetCategory>()
                        .orderByAsc(com.smartflow.pojo.entity.AssetCategory::getCode)));
    }

    @PostMapping("/asset-categories")
    public Result<com.smartflow.pojo.entity.AssetCategory> saveAssetCategory(
            HttpServletRequest req, @RequestBody com.smartflow.pojo.entity.AssetCategory body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getCode() == null || body.getCode().isBlank()
                || body.getName() == null || body.getName().isBlank()) {
            throw new BusinessException("code/name 必填");
        }
        var exist = assetCategoryMapper.selectOne(new LambdaQueryWrapper<com.smartflow.pojo.entity.AssetCategory>()
                .eq(com.smartflow.pojo.entity.AssetCategory::getCode, body.getCode().trim()));
        if (exist != null) {
            body.setId(exist.getId());
            assetCategoryMapper.updateById(body);
        } else {
            if (body.getDepreciateYears() == null) {
                body.setDepreciateYears(3);
            }
            if (body.getWarrantyDefault() == null) {
                body.setWarrantyDefault(36);
            }
            if (body.getStatus() == null) {
                body.setStatus("active");
            }
            assetCategoryMapper.insert(body);
        }
        auditService.record(u.uid(), "config.asset_category.save", "asset_category",
                body.getCode(), null, body.getStatus(), null, null);
        return Result.ok(body);
    }

    // ── 会议室主数据 ──
    @GetMapping("/meeting-rooms")
    public Result<List<com.smartflow.pojo.entity.MeetingRoom>> listMeetingRooms() {
        return Result.ok(meetingRoomMapper.selectList(
                new LambdaQueryWrapper<com.smartflow.pojo.entity.MeetingRoom>()
                        .orderByAsc(com.smartflow.pojo.entity.MeetingRoom::getCode)));
    }

    @PostMapping("/meeting-rooms")
    public Result<com.smartflow.pojo.entity.MeetingRoom> saveMeetingRoom(
            HttpServletRequest req, @RequestBody com.smartflow.pojo.entity.MeetingRoom body) {
        LoginUser u = LoginUser.require(req);
        requireAdmin(u);
        if (body.getCode() == null || body.getCode().isBlank()
                || body.getName() == null || body.getName().isBlank()) {
            throw new BusinessException("code/name 必填");
        }
        var exist = meetingRoomMapper.selectOne(new LambdaQueryWrapper<com.smartflow.pojo.entity.MeetingRoom>()
                .eq(com.smartflow.pojo.entity.MeetingRoom::getCode, body.getCode().trim()));
        if (exist != null) {
            body.setId(exist.getId());
            meetingRoomMapper.updateById(body);
        } else {
            if (body.getCapacity() == null) {
                body.setCapacity(8);
            }
            if (body.getStatus() == null) {
                body.setStatus("active");
            }
            meetingRoomMapper.insert(body);
        }
        auditService.record(u.uid(), "config.meeting_room.save", "meeting_room",
                body.getCode(), null, body.getStatus(), null, null);
        return Result.ok(body);
    }
}
