package com.smartflow.server.service;

import static com.smartflow.server.service.NumberGen.genNo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.ApprovalTemplate;
import com.smartflow.pojo.entity.DeptBudget;
import com.smartflow.pojo.entity.Expense;
import com.smartflow.pojo.entity.ExpenseItem;
import com.smartflow.server.approval.ApprovalEngine;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.ApprovalTemplateMapper;
import com.smartflow.server.mapper.DeptBudgetMapper;
import com.smartflow.server.mapper.ExpenseItemMapper;
import com.smartflow.server.mapper.ExpenseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ExpenseService {

    private static final String DEFAULT_CHAIN =
            "[{\"step\":1,\"role\":\"manager\",\"mode\":\"serial\"},"
                    + "{\"step\":2,\"role\":\"finance\",\"mode\":\"serial\"}]";

    private final ExpenseMapper expenseMapper;
    private final ExpenseItemMapper itemMapper;
    private final DeptBudgetMapper budgetMapper;
    private final ApprovalTemplateMapper templateMapper;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;
    private final AuditService auditService;
    private final com.smartflow.server.mapper.ExpensePolicyMapper policyMapper;
    private final ObjectMapper om = new ObjectMapper();

    public ExpenseService(ExpenseMapper expenseMapper,
                          ExpenseItemMapper itemMapper,
                          DeptBudgetMapper budgetMapper,
                          ApprovalTemplateMapper templateMapper,
                          ApprovalEngine approvalEngine,
                          NoticeService noticeService,
                          AuditService auditService,
                          com.smartflow.server.mapper.ExpensePolicyMapper policyMapper) {
        this.expenseMapper = expenseMapper;
        this.itemMapper = itemMapper;
        this.budgetMapper = budgetMapper;
        this.templateMapper = templateMapper;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
        this.auditService = auditService;
        this.policyMapper = policyMapper;
    }

    /**
     * @param items 明细（含 invoiceNo）
     * @param idempotencyKey 客户端幂等键
     */
    @Transactional
    public Expense submit(LoginUser user, Expense body, List<ExpenseItem> items, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Expense exist = expenseMapper.selectOne(new LambdaQueryWrapper<Expense>()
                    .eq(Expense::getIdempotencyKey, idempotencyKey));
            if (exist != null) {
                return exist; // 幂等：直接回原单
            }
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException("至少一条明细");
        }
        BigDecimal sum = BigDecimal.ZERO;
        List<String> invDup = new ArrayList<>();
        for (ExpenseItem it : items) {
            if (it.getAmount() == null || it.getAmount().signum() <= 0) {
                throw new BusinessException("明细金额必须大于 0");
            }
            sum = sum.add(it.getAmount());
            if (it.getInvoiceNo() != null && !it.getInvoiceNo().isBlank()) {
                Long c = itemMapper.selectCount(new LambdaQueryWrapper<ExpenseItem>()
                        .eq(ExpenseItem::getInvoiceNo, it.getInvoiceNo()));
                if (c != null && c > 0) {
                    invDup.add(it.getInvoiceNo());
                }
            }
        }
        if (!invDup.isEmpty()) {
            throw new BusinessException(409, "发票号已存在: " + String.join(",", invDup));
        }
        if (body.getAmountTotal() == null) {
            body.setAmountTotal(sum);
        } else if (body.getAmountTotal().compareTo(sum) != 0) {
            throw new BusinessException("总额与明细之和不一致");
        }

        // 预算 hold
        String period = body.getBudgetPeriod() != null
                ? body.getBudgetPeriod()
                : LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        body.setBudgetPeriod(period);
        DeptBudget budget = budgetMapper.selectOne(new LambdaQueryWrapper<DeptBudget>()
                .eq(DeptBudget::getDeptId, user.deptId())
                .eq(DeptBudget::getPeriod, period));
        List<String> flags = new ArrayList<>();
        if (budget != null) {
            BigDecimal remain = budget.getAmount().subtract(budget.getUsed());
            if (remain.compareTo(body.getAmountTotal()) < 0) {
                flags.add("budget_low");
            } else {
                budget.setUsed(budget.getUsed().add(body.getAmountTotal()));
                budgetMapper.updateById(budget);
                body.setBudgetHold(body.getAmountTotal());
            }
        }

        // 简单标准：单笔 > 5000 打标
        if (body.getAmountTotal().compareTo(new BigDecimal("5000")) > 0) {
            flags.add("over_standard");
        }
        // 报销标准（配置中心）：超日限额 / 缺票打标
        com.smartflow.pojo.entity.ExpensePolicy pol = policyMapper.selectOne(
                new LambdaQueryWrapper<com.smartflow.pojo.entity.ExpensePolicy>()
                        .eq(com.smartflow.pojo.entity.ExpensePolicy::getType, body.getType())
                        .eq(com.smartflow.pojo.entity.ExpensePolicy::getStatus, "active"));
        if (pol != null) {
            if (pol.getLimitPerDay() != null && body.getAmountTotal().compareTo(pol.getLimitPerDay()) > 0) {
                flags.add("over_daily_limit");
            }
            if (Boolean.TRUE.equals(pol.getNeedInvoice())) {
                boolean anyInvoice = items.stream()
                        .anyMatch(it -> it.getInvoiceNo() != null && !it.getInvoiceNo().isBlank());
                if (!anyInvoice) {
                    flags.add("missing_invoice");
                }
            }
        }
        body.setPolicyFlags(toStringJson(flags));

        body.setUserId(user.uid());
        body.setDeptId(user.deptId());
        body.setStatus("draft");
        body.setExpenseNo(genNo("E"));
        body.setIdempotencyKey(idempotencyKey);
        body.setAiGenerated(Boolean.TRUE.equals(body.getAiGenerated()));
        expenseMapper.insert(body);

        for (ExpenseItem it : items) {
            it.setExpenseId(body.getId());
            itemMapper.insert(it);
        }

        String chain = loadExpenseChain();
        Long instId = approvalEngine.start("expense", body.getId(), user.uid(), chain, Map.of("amount", body.getAmountTotal()));
        body.setApprovalInstanceId(instId);
        body.setStatus("pending_manager");
        expenseMapper.updateById(body);
        noticeService.push(financeOrManagerHint(user), "approval", "新报销待审 " + body.getExpenseNo(),
                "金额 " + body.getAmountTotal(), "expense", body.getId());
        auditService.record(user.uid(), "expense.submit", "expense", body.getExpenseNo(),
                null, body.getStatus(), null, null);
        return body;
    }

    @Transactional
    public Expense approve(LoginUser user, Long id, boolean agree, String comment) {
        Expense e = expenseMapper.selectById(id);
        if (e == null) {
            throw new BusinessException(404, "报销单不存在");
        }
        if (!e.getStatus().startsWith("pending")) {
            throw new BusinessException(409, "当前状态不可审批: " + e.getStatus());
        }
        boolean managerStep = "pending_manager".equals(e.getStatus());
        if (agree) {
            boolean done = approvalEngine.agree(e.getApprovalInstanceId(), user.uid(), comment);
            if (done || !managerStep) {
                // 全部同意或已到 finance 后一步完成 -> approved（B1 简化：全过即 approved，打款另接口）
                e.setStatus(done ? "approved" : "pending_finance");
            } else {
                e.setStatus("pending_finance");
            }
        } else {
            approvalEngine.reject(e.getApprovalInstanceId(), user.uid(), comment);
            e.setStatus("rejected");
            releaseBudget(e);
        }
        expenseMapper.updateById(e);
        auditService.record(user.uid(), agree ? "expense.approve" : "expense.reject",
                "expense", e.getExpenseNo(), null, e.getStatus(), null, null);
        if ("approved".equals(e.getStatus())) {
            noticeService.push(e.getUserId(), "approval", "报销已通过 " + e.getExpenseNo(),
                    "待打款", "expense", e.getId());
        }
        return e;
    }

    /** 撤回：提交人 + 草稿/审批中。 */
    @Transactional
    public Expense withdraw(LoginUser user, Long id) {
        Expense e = expenseMapper.selectById(id);
        if (e == null || !e.getUserId().equals(user.uid())) {
            throw new BusinessException(404, "报销单不存在");
        }
        if (!e.getStatus().startsWith("pending") && !"draft".equals(e.getStatus())) {
            throw new BusinessException(409, "仅草稿/审批中可撤回");
        }
        String before = e.getStatus();
        e.setStatus("cancelled");
        expenseMapper.updateById(e);
        releaseBudget(e);
        auditService.record(user.uid(), "expense.withdraw", "expense", e.getExpenseNo(),
                before, "cancelled", null, null);
        return e;
    }

    /** 财务打款。 */
    @Transactional
    public Expense pay(LoginUser user, Long id) {
        if (!user.hasRole("finance") && !user.isAdmin()) {
            throw new BusinessException(403, "仅 finance 可打款");
        }
        Expense e = expenseMapper.selectById(id);
        if (e == null || !"approved".equals(e.getStatus())) {
            throw new BusinessException(409, "仅 approved 可打款");
        }
        e.setStatus("paid");
        // 打款后 used 已 hold，不再加；voided 时回滚
        expenseMapper.updateById(e);
        auditService.record(user.uid(), "expense.pay", "expense", e.getExpenseNo(),
                "approved", "paid", null, null);
        noticeService.push(e.getUserId(), "approval", "报销已打款 " + e.getExpenseNo(),
                "金额 " + e.getAmountTotal(), "expense", e.getId());
        return e;
    }

    /** 冲销（仅 finance/admin，paid 后）。 */
    @Transactional
    public Expense voidExpense(LoginUser user, Long id) {
        if (!user.hasRole("finance") && !user.isAdmin()) {
            throw new BusinessException(403, "仅 finance/admin 可冲销");
        }
        Expense e = expenseMapper.selectById(id);
        if (e == null || !"paid".equals(e.getStatus())) {
            throw new BusinessException(409, "仅 paid 可冲销");
        }
        String before = e.getStatus();
        e.setStatus("voided");
        expenseMapper.updateById(e);
        releaseBudget(e);
        auditService.record(user.uid(), "expense.void", "expense", e.getExpenseNo(),
                before, "voided", null, null);
        return e;
    }

    /** 列表五件套：分页+关键字+状态。 */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Expense> pageQuery(
            com.smartflow.common.PageQuery q) {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Expense>(
                q.pageNo(), q.pageSize());
        var qw = new LambdaQueryWrapper<Expense>()
                .eq(q.hasStatus(), Expense::getStatus, q.status())
                .and(q.hasKeyword(), w -> w
                        .like(Expense::getExpenseNo, q.keyword())
                        .or()
                        .like(Expense::getType, q.keyword()))
                .orderByDesc(Expense::getCreatedAt);
        return expenseMapper.selectPage(page, qw);
    }

    public List<Expense> myList(LoginUser user) {
        return expenseMapper.selectList(new LambdaQueryWrapper<Expense>()
                .eq(Expense::getUserId, user.uid())
                .orderByDesc(Expense::getCreatedAt));
    }

    private void releaseBudget(Expense e) {
        if (e.getBudgetHold() == null || e.getBudgetHold().signum() <= 0 || e.getBudgetPeriod() == null) {
            return;
        }
        DeptBudget b = budgetMapper.selectOne(new LambdaQueryWrapper<DeptBudget>()
                .eq(DeptBudget::getDeptId, e.getDeptId())
                .eq(DeptBudget::getPeriod, e.getBudgetPeriod()));
        if (b != null) {
            b.setUsed(b.getUsed().subtract(e.getBudgetHold()).max(BigDecimal.ZERO));
            budgetMapper.updateById(b);
        }
    }

    private String toStringJson(List<String> flags) {
        try {
            return om.writeValueAsString(flags);
        } catch (Exception ex) {
            return "[]";
        }
    }

    /** 读配置中心 active 模板（expense），缺省回退 manager→finance。 */
    private String loadExpenseChain() {
        ApprovalTemplate tpl = templateMapper.selectOne(new LambdaQueryWrapper<ApprovalTemplate>()
                .eq(ApprovalTemplate::getBizType, "expense")
                .eq(ApprovalTemplate::getStatus, "active")
                .orderByDesc(ApprovalTemplate::getVersion)
                .last("LIMIT 1"));
        if (tpl != null && tpl.getStepsJson() != null && !tpl.getStepsJson().isBlank()) {
            return tpl.getStepsJson();
        }
        return DEFAULT_CHAIN;
    }

    private Long financeOrManagerHint(LoginUser user) {
        // 简化：通知给本人 + 留给 manager 流；manager 待办靠 approvals
        return user.uid();
    }
}
