package com.smartflow.server.service;

import static com.smartflow.server.service.NumberGen.genNo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.PurchaseRequest;
import com.smartflow.server.approval.ApprovalEngine;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.PurchaseRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class PurchaseService {

    /** 合法迁移 from -> 可到 to（单测锁表） */
    static final Map<String, List<String>> PURCHASE_TRANSITIONS = Map.of(
            "demand", List.of("quoted", "cancelled"),
            "quoted", List.of("approved", "rejected", "cancelled"),
            "approved", List.of("ordered", "rejected", "cancelled"),
            "ordered", List.of("received", "cancelled"),
            "received", List.of("accepted"),
            "accepted", List.of(),
            "rejected", List.of(),
            "cancelled", List.of()
    );

    static boolean canMove(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return PURCHASE_TRANSITIONS.getOrDefault(from, List.of()).contains(to);
    }

    /** 比价至少几家（P0 规则 ≥2） */
    static final int MIN_QUOTES = 2;

    private final PurchaseRequestMapper purchaseMapper;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;
    private final AuditService auditService;
    private final ObjectMapper om = new ObjectMapper();

    public PurchaseService(PurchaseRequestMapper purchaseMapper,
                           ApprovalEngine approvalEngine,
                           NoticeService noticeService,
                           AuditService auditService) {
        this.purchaseMapper = purchaseMapper;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
        this.auditService = auditService;
    }

    public List<PurchaseRequest> list(String status) {
        return purchaseMapper.selectList(new LambdaQueryWrapper<PurchaseRequest>()
                .eq(status != null && !status.isBlank(), PurchaseRequest::getStatus, status)
                .orderByDesc(PurchaseRequest::getCreatedAt));
    }

    public List<PurchaseRequest> myList(LoginUser user) {
        return purchaseMapper.selectList(new LambdaQueryWrapper<PurchaseRequest>()
                .eq(PurchaseRequest::getRequesterId, user.uid())
                .orderByDesc(PurchaseRequest::getCreatedAt));
    }

    /** 提报需求：demand。 */
    @Transactional
    public PurchaseRequest create(LoginUser user, PurchaseRequest body) {
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            throw new BusinessException("需求标题必填");
        }
        if (body.getQty() == null || body.getQty() <= 0) {
            body.setQty(1);
        }
        body.setRequesterId(user.uid());
        body.setDeptId(user.deptId());
        body.setStatus("demand");
        body.setDemandNo(genNo("P"));
        body.setCreatedAt(LocalDateTime.now());
        purchaseMapper.insert(body);
        auditService.record(user.uid(), "purchase.create", "purchase", body.getDemandNo(),
                null, "demand", null, null);
        return body;
    }

    /** 录入比价：≥MIN_QUOTES 家才可进 quoted。 */
    @Transactional
    public PurchaseRequest addQuotes(LoginUser user, Long id, String quotesJson) {
        PurchaseRequest p = loadOwnedOrManager(user, id);
        if (!"demand".equals(p.getStatus()) && !"quoted".equals(p.getStatus())) {
            throw new BusinessException(409, "仅 demand/quoted 可录比价: " + p.getStatus());
        }
        if (quotesJson == null || quotesJson.isBlank()) {
            throw new BusinessException("quotesJson 必填");
        }
        List<?> quotes;
        try {
            quotes = om.readValue(quotesJson, List.class);
        } catch (Exception e) {
            throw new BusinessException("比价 JSON 解析失败");
        }
        if (quotes.size() < MIN_QUOTES) {
            throw new BusinessException(400, "至少 " + MIN_QUOTES + " 家比价");
        }
        p.setQuotesJson(quotesJson);
        p.setStatus("quoted");
        p.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.updateById(p);
        auditService.record(user.uid(), "purchase.quote", "purchase", p.getDemandNo(),
                "demand", "quoted", null, null);
        return p;
    }

    /** 提交审批（quoted -> 走 ApprovalEngine，同意后 quoted 语义上等 approved 由回调推进）。 */
    @Transactional
    public PurchaseRequest submitApproval(LoginUser user, Long id) {
        PurchaseRequest p = loadOwnedOrManager(user, id);
        if (!"quoted".equals(p.getStatus())) {
            throw new BusinessException(409, "仅 quoted 可提交审批");
        }
        if (p.getQuotesJson() == null) {
            throw new BusinessException(400, "先录比价");
        }
        String chain = "[{\"step\":1,\"role\":\"manager\",\"mode\":\"serial\"},"
                + "{\"step\":2,\"role\":\"finance\",\"mode\":\"serial\"}]";
        Long instId = approvalEngine.start("purchase", p.getId(), user.uid(), chain,
                Map.of("budget", p.getBudget() == null ? BigDecimal.ZERO : p.getBudget()));
        p.setApprovalInstanceId(instId);
        // 引擎同意后由 approveStep 推进到 approved（与业务单同模式）
        p.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.updateById(p);
        auditService.record(user.uid(), "purchase.submit", "purchase", p.getDemandNo(),
                "quoted", "running", null, null);
        return p;
    }

    /** 审批推进：agree -> approved；reject -> rejected。 */
    @Transactional
    public PurchaseRequest approveStep(LoginUser user, Long id, boolean agree, String comment) {
        PurchaseRequest p = purchaseMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(404, "采购单不存在");
        }
        if (p.getApprovalInstanceId() == null) {
            throw new BusinessException(409, "未进入审批");
        }
        if (agree) {
            boolean done = approvalEngine.agree(p.getApprovalInstanceId(), user.uid(), comment);
            p.setStatus(done ? "approved" : "quoted"); // 还有下一步保持
            if (done) {
                noticeService.push(p.getRequesterId(), "approval",
                        "采购已通过 " + p.getDemandNo(), p.getTitle(), "purchase", p.getId());
            }
        } else {
            approvalEngine.reject(p.getApprovalInstanceId(), user.uid(), comment);
            p.setStatus("rejected");
        }
        p.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.updateById(p);
        auditService.record(user.uid(), agree ? "purchase.approve" : "purchase.reject",
                "purchase", p.getDemandNo(), null, p.getStatus(), null, null);
        return p;
    }

    /** 下单 approved -> ordered。 */
    @Transactional
    public PurchaseRequest markOrdered(LoginUser user, Long id) {
        PurchaseRequest p = loadOwnedOrManager(user, id);
        requireMove(p, "ordered");
        p.setStatus("ordered");
        p.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.updateById(p);
        auditService.record(user.uid(), "purchase.ordered", "purchase", p.getDemandNo(),
                "approved", "ordered", null, null);
        return p;
    }

    /** 到货 ordered -> received。 */
    @Transactional
    public PurchaseRequest markReceived(LoginUser user, Long id) {
        PurchaseRequest p = loadOwnedOrManager(user, id);
        requireMove(p, "received");
        p.setStatus("received");
        p.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.updateById(p);
        auditService.record(user.uid(), "purchase.received", "purchase", p.getDemandNo(),
                "ordered", "received", null, null);
        return p;
    }

    /** 验收 received -> accepted（终态，钱单 AI 不直写）。 */
    @Transactional
    public PurchaseRequest markAccepted(LoginUser user, Long id) {
        PurchaseRequest p = loadOwnedOrManager(user, id);
        if (!user.hasRole("finance") && !user.hasRole("agent") && !user.isAdmin()) {
            throw new BusinessException(403, "仅财务/行政/管理员可验收");
        }
        requireMove(p, "accepted");
        p.setStatus("accepted");
        p.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.updateById(p);
        auditService.record(user.uid(), "purchase.accepted", "purchase", p.getDemandNo(),
                "received", "accepted", null, null);
        return p;
    }

    /** 关联报销单（paid_link 简化：只挂 id）。 */
    @Transactional
    public PurchaseRequest linkExpense(LoginUser user, Long id, Long expenseId) {
        PurchaseRequest p = loadOwnedOrManager(user, id);
        if (!"accepted".equals(p.getStatus())) {
            throw new BusinessException(409, "仅 accepted 可挂报销");
        }
        p.setLinkedExpenseId(expenseId);
        p.setUpdatedAt(LocalDateTime.now());
        purchaseMapper.updateById(p);
        auditService.record(user.uid(), "purchase.link_expense", "purchase", p.getDemandNo(),
                null, "expense:" + expenseId, null, null);
        return p;
    }

    private void requireMove(PurchaseRequest p, String to) {
        if (!canMove(p.getStatus(), to)) {
            throw new BusinessException(409, "非法迁移: " + p.getStatus() + " -> " + to);
        }
    }

    private PurchaseRequest loadOwnedOrManager(LoginUser user, Long id) {
        PurchaseRequest p = purchaseMapper.selectById(id);
        if (p == null) {
            throw new BusinessException(404, "采购单不存在");
        }
        boolean owner = p.getRequesterId() != null && p.getRequesterId().equals(user.uid());
        if (!owner && !user.isAdmin() && !user.hasRole("agent") && !user.hasRole("finance")) {
            throw new BusinessException(403, "仅发起人/管理员/行政/财务可操作");
        }
        return p;
    }
}
