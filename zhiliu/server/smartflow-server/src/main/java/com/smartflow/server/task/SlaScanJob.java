package com.smartflow.server.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.pojo.entity.SlaEvent;
import com.smartflow.pojo.entity.Ticket;
import com.smartflow.pojo.entity.TicketEvent;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.SlaEventMapper;
import com.smartflow.server.mapper.TicketEventMapper;
import com.smartflow.server.mapper.TicketMapper;
import com.smartflow.server.service.AuditService;
import com.smartflow.server.service.NoticeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SlaScanJob {

    private static final Logger log = LoggerFactory.getLogger(SlaScanJob.class);

    private final TicketMapper ticketMapper;
    private final TicketEventMapper eventMapper;
    private final SlaEventMapper slaEventMapper;
    private final NoticeService noticeService;
    private final AuditService auditService;

    public SlaScanJob(TicketMapper ticketMapper,
                      TicketEventMapper eventMapper,
                      SlaEventMapper slaEventMapper,
                      NoticeService noticeService,
                      AuditService auditService) {
        this.ticketMapper = ticketMapper;
        this.eventMapper = eventMapper;
        this.slaEventMapper = slaEventMapper;
        this.noticeService = noticeService;
        this.auditService = auditService;
    }

    /** 每分钟扫一次；生产可改 cron。 */
    @Scheduled(fixedDelayString = "60000")
    public void scan() {
        try {
            doScan();
        } catch (Exception e) {
            log.error("[sla] scan failed", e);
        }
    }

    @Transactional
    public int doScan() {
        LocalDateTime now = LocalDateTime.now();
        // 未关闭且有 deadline 的在途单
        List<Ticket> open = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .in(Ticket::getStatus, "triage", "assigned", "in_progress",
                        "waiting_user", "waiting_third", "escalated")
                .isNotNull(Ticket::getSlaDeadline));
        int hit = 0;
        for (Ticket t : open) {
            LocalDateTime dl = t.getSlaDeadline();
            if (dl == null) {
                continue;
            }
            boolean breached = !dl.isAfter(now);
            // 临期：剩余时间 < 总时长 25%（用 first_response 不好算，简化为 deadline 前 2h 或已过）
            boolean remind = !breached && dl.minusMinutes(120).isBefore(now);

            boolean hasBreach = hasEvent(t.getId(), "breach");
            boolean hasRemind = hasEvent(t.getId(), "remind");

            if (breached && !hasBreach) {
                writeEvent(t.getId(), "breach", null);
                if (!"escalated".equals(t.getStatus())) {
                    String from = t.getStatus();
                    t.setStatus("escalated");
                    ticketMapper.updateById(t);
                    TicketEvent ev = new TicketEvent();
                    ev.setTicketId(t.getId());
                    ev.setFromStatus(from);
                    ev.setToStatus("escalated");
                    ev.setActorId(null);
                    ev.setReason("SLA breach auto escalate");
                    ev.setCreatedAt(now);
                    eventMapper.insert(ev);
                }
                noticeService.push(t.getReporterId(), "sla_breach",
                        "SLA 破线 " + t.getTicketNo(), t.getTitle(), "ticket", t.getId());
                if (t.getAssigneeId() != null) {
                    noticeService.push(t.getAssigneeId(), "sla_breach",
                            "你负责的工单 SLA 破线 " + t.getTicketNo(), t.getTitle(), "ticket", t.getId());
                }
                auditService.record(null, "ticket.sla_breach", "ticket", t.getTicketNo(),
                        fromStatusOf(t), "escalated", null, null);
                hit++;
            } else if (remind && !hasRemind && !breached) {
                writeEvent(t.getId(), "remind", t.getAssigneeId());
                noticeService.push(t.getAssigneeId() != null ? t.getAssigneeId() : t.getReporterId(),
                        "sla_breach", "SLA 临期 " + t.getTicketNo(), t.getTitle(), "ticket", t.getId());
                hit++;
            }
        }
        if (hit > 0) {
            log.info("[sla] scan hit {}", hit);
        }
        return hit;
    }

    private boolean hasEvent(Long ticketId, String type) {
        Long c = slaEventMapper.selectCount(new LambdaQueryWrapper<SlaEvent>()
                .eq(SlaEvent::getTicketId, ticketId)
                .eq(SlaEvent::getType, type));
        return c != null && c > 0;
    }

    private void writeEvent(Long ticketId, String type, Long notifier) {
        SlaEvent e = new SlaEvent();
        e.setTicketId(ticketId);
        e.setType(type);
        e.setAt(LocalDateTime.now());
        e.setNotifierId(notifier);
        slaEventMapper.insert(e);
    }

    private String fromStatusOf(Ticket t) {
        return t.getStatus();
    }
}
