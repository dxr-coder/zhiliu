package com.smartflow.server.service;

import static com.smartflow.server.service.NumberGen.genNo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.SlaEvent;
import com.smartflow.pojo.entity.SlaPolicy;
import com.smartflow.pojo.entity.Ticket;
import com.smartflow.pojo.entity.TicketEvent;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.SlaEventMapper;
import com.smartflow.server.mapper.SlaPolicyMapper;
import com.smartflow.server.mapper.TicketEventMapper;
import com.smartflow.server.mapper.TicketMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class TicketService {

    /** 允许的状态迁移（from -> 可到 to）；单测直接锁这张表 */
    static final Map<String, List<String>> TRANSITIONS = Map.of(
            "triage", List.of("assigned", "rejected", "duplicate"),
            "assigned", List.of("in_progress", "escalated", "rejected"),
            "in_progress", List.of("waiting_user", "waiting_third", "escalated", "resolved"),
            "waiting_user", List.of("in_progress"),
            "waiting_third", List.of("in_progress"),
            "escalated", List.of("in_progress", "resolved"),
            "resolved", List.of("closed", "in_progress"),
            "closed", List.of("reopen"),
            "rejected", List.of(),
            "duplicate", List.of()
    );

    /** 纯函数：状态迁移是否合法（reopen 特例由调用方处理）。 */
    static boolean canTransition(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        if ("closed".equals(from) && "assigned".equals(to)) {
            return true; // reopen
        }
        return TRANSITIONS.getOrDefault(from, List.of()).contains(to);
    }

    private final TicketMapper ticketMapper;
    private final TicketEventMapper eventMapper;
    private final SlaPolicyMapper slaPolicyMapper;
    private final SlaEventMapper slaEventMapper;
    private final NoticeService noticeService;
    private final AuditService auditService;

    public TicketService(TicketMapper ticketMapper,
                         TicketEventMapper eventMapper,
                         SlaPolicyMapper slaPolicyMapper,
                         SlaEventMapper slaEventMapper,
                         NoticeService noticeService,
                         AuditService auditService) {
        this.ticketMapper = ticketMapper;
        this.eventMapper = eventMapper;
        this.slaPolicyMapper = slaPolicyMapper;
        this.slaEventMapper = slaEventMapper;
        this.noticeService = noticeService;
        this.auditService = auditService;
    }

    @Transactional
    public Ticket create(LoginUser user, Ticket body) {
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            throw new BusinessException("标题必填");
        }
        String pri = body.getPriority() == null ? "p2" : body.getPriority();
        body.setPriority(pri);
        body.setReporterId(user.uid());
        body.setDeptId(user.deptId());
        body.setSource(body.getSource() == null ? "portal" : body.getSource());
        body.setStatus("triage");
        body.setTicketNo(genNo("T"));
        body.setReopenCount(0);

        SlaPolicy sla = slaPolicyMapper.selectOne(new LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getPriority, pri)
                .eq(SlaPolicy::getStatus, "active"));
        if (sla != null) {
            body.setSlaDeadline(LocalDateTime.now().plusMinutes(sla.getResolveMin()));
        }
        ticketMapper.insert(body);
        event(body.getId(), null, "triage", user.uid(), "created");
        auditService.record(user.uid(), "ticket.create", "ticket", body.getTicketNo(),
                null, "triage", null, null);
        return body;
    }

    @Transactional
    public Ticket transition(LoginUser user, Long id, String toStatus, String reason, Long assigneeId) {
        Ticket t = ticketMapper.selectById(id);
        if (t == null) {
            throw new BusinessException(404, "工单不存在");
        }
        if ("reopen".equals(toStatus) && "closed".equals(t.getStatus())) {
            toStatus = "assigned";
            t.setReopenCount((t.getReopenCount() == null ? 0 : t.getReopenCount()) + 1);
        }
        if (!canTransition(t.getStatus(), toStatus)) {
            throw new BusinessException(409, "非法迁移: " + t.getStatus() + " -> " + toStatus);
        }
        String from = t.getStatus();
        t.setStatus(toStatus);
        if (assigneeId != null) {
            t.setAssigneeId(assigneeId);
        }
        if ("assigned".equals(toStatus) && t.getFirstResponseAt() == null) {
            t.setFirstResponseAt(LocalDateTime.now());
            slaEvent(t.getId(), "first_response", user.uid());
        }
        if ("in_progress".equals(toStatus) && t.getFirstResponseAt() == null) {
            t.setFirstResponseAt(LocalDateTime.now());
            slaEvent(t.getId(), "first_response", user.uid());
        }
        if ("resolved".equals(toStatus)) {
            t.setResolution(reason != null ? reason : t.getResolution());
            slaEvent(t.getId(), "resolve", user.uid());
        }
        ticketMapper.updateById(t);
        event(t.getId(), from, toStatus, user.uid(), reason);
        if (t.getReporterId() != null && ("resolved".equals(toStatus) || "closed".equals(toStatus))) {
            noticeService.push(t.getReporterId(), "ticket", "工单状态更新 " + t.getTicketNo(),
                    toStatus, "ticket", t.getId());
        }
        auditService.record(user.uid(), "ticket." + toStatus, "ticket", t.getTicketNo(),
                from, toStatus, null, null);
        return t;
    }

    /** 列表五件套：分页+关键字+状态。 */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Ticket> pageQuery(
            com.smartflow.common.PageQuery q) {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Ticket>(
                q.pageNo(), q.pageSize());
        var qw = new LambdaQueryWrapper<Ticket>()
                .eq(q.hasStatus(), Ticket::getStatus, q.status())
                .and(q.hasKeyword(), w -> w
                        .like(Ticket::getTitle, q.keyword())
                        .or()
                        .like(Ticket::getTicketNo, q.keyword()))
                .orderByDesc(Ticket::getCreatedAt);
        return ticketMapper.selectPage(page, qw);
    }

    public List<Ticket> myList(LoginUser user) {
        return ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getReporterId, user.uid())
                .orderByDesc(Ticket::getCreatedAt));
    }

    public List<TicketEvent> events(Long ticketId) {
        return eventMapper.selectList(new LambdaQueryWrapper<TicketEvent>()
                .eq(TicketEvent::getTicketId, ticketId)
                .orderByAsc(TicketEvent::getCreatedAt));
    }

    private void event(Long ticketId, String from, String to, Long actor, String reason) {
        TicketEvent e = new TicketEvent();
        e.setTicketId(ticketId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setActorId(actor);
        e.setReason(reason);
        e.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(e);
    }

    private void slaEvent(Long ticketId, String type, Long notifier) {
        SlaEvent e = new SlaEvent();
        e.setTicketId(ticketId);
        e.setType(type);
        e.setAt(LocalDateTime.now());
        e.setNotifierId(notifier);
        slaEventMapper.insert(e);
    }
}
