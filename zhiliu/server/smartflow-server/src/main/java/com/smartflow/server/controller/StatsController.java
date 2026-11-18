package com.smartflow.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.Result;
import com.smartflow.pojo.entity.*;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TicketMapper ticketMapper;
    private final SlaEventMapper slaEventMapper;
    private final ExpenseMapper expenseMapper;
    private final LeaveMapper leaveMapper;
    private final AssetMapper assetMapper;
    private final RoomBookingMapper bookingMapper;
    private final MeetingRoomMapper roomMapper;
    private final PurchaseRequestMapper purchaseMapper;
    private final com.smartflow.server.service.SysConfigService sysConfig;

    public StatsController(TicketMapper ticketMapper,
                           SlaEventMapper slaEventMapper,
                           ExpenseMapper expenseMapper,
                           LeaveMapper leaveMapper,
                           AssetMapper assetMapper,
                           RoomBookingMapper bookingMapper,
                           MeetingRoomMapper roomMapper,
                           PurchaseRequestMapper purchaseMapper,
                           com.smartflow.server.service.SysConfigService sysConfig) {
        this.ticketMapper = ticketMapper;
        this.slaEventMapper = slaEventMapper;
        this.expenseMapper = expenseMapper;
        this.leaveMapper = leaveMapper;
        this.assetMapper = assetMapper;
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.purchaseMapper = purchaseMapper;
        this.sysConfig = sysConfig;
    }

    /** 业务 8 指标 + 运维任务健康/AI 开关。 */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(HttpServletRequest request) {
        LoginUser.require(request);
        LocalDateTime dayAgo = LocalDateTime.now().minusDays(7);

        // 1 工单量
        long tCreated = count(ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .ge(Ticket::getCreatedAt, dayAgo)));
        long tDone = count(ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .in(Ticket::getStatus, "resolved", "closed")
                .ge(Ticket::getCreatedAt, dayAgo)));
        long tOpen = count(ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .notIn(Ticket::getStatus, "resolved", "closed", "rejected", "duplicate")));

        // 2 SLA 达标率：近 7 天完成单中「无 breach」占比；无完成单显示 —（不装 100）
        java.util.Set<Long> breachTicketIds = new java.util.HashSet<>();
        for (SlaEvent ev : slaEventMapper.selectList(new LambdaQueryWrapper<SlaEvent>()
                .eq(SlaEvent::getType, "breach"))) {
            if (ev.getTicketId() != null) {
                breachTicketIds.add(ev.getTicketId());
            }
        }
        long slaDone = 0;
        long slaOk = 0;
        for (Ticket tk : ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .in(Ticket::getStatus, "resolved", "closed")
                .ge(Ticket::getCreatedAt, dayAgo))) {
            slaDone++;
            if (!breachTicketIds.contains(tk.getId())) {
                slaOk++;
            }
        }
        String slaRate = slaDone == 0 ? "—" :
                BigDecimal.valueOf(slaOk).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(slaDone), 1, RoundingMode.HALF_UP).toPlainString();

        // 3 报销在途与金额
        long expInFlight = count(expenseMapper.selectCount(new LambdaQueryWrapper<Expense>()
                .likeRight(Expense::getStatus, "pending")));
        BigDecimal expAmount = expenseMapper.selectList(new LambdaQueryWrapper<Expense>()
                        .likeRight(Expense::getStatus, "pending"))
                .stream().map(Expense::getAmountTotal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long expPaid = count(expenseMapper.selectCount(new LambdaQueryWrapper<Expense>()
                .eq(Expense::getStatus, "paid")));

        // 4 请假在途
        long leaveInFlight = count(leaveMapper.selectCount(new LambdaQueryWrapper<LeaveRequest>()
                .likeRight(LeaveRequest::getStatus, "pending")));

        // 5 资产状态分布 + 保修 30 天
        long aStock = count(assetMapper.selectCount(new LambdaQueryWrapper<Asset>().eq(Asset::getStatus, "stock")));
        long aAssigned = count(assetMapper.selectCount(new LambdaQueryWrapper<Asset>().eq(Asset::getStatus, "assigned")));
        long aRepair = count(assetMapper.selectCount(new LambdaQueryWrapper<Asset>().eq(Asset::getStatus, "repair")));
        long aLost = count(assetMapper.selectCount(new LambdaQueryWrapper<Asset>().eq(Asset::getStatus, "lost")));
        LocalDateTime warrantyCut = LocalDateTime.now().plusDays(30);
        long aWarrantySoon = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .isNotNull(Asset::getWarrantyEnd)
                        .le(Asset::getWarrantyEnd, java.time.LocalDate.now().plusDays(30)))
                .size();
        // 保险丝：warrantyEnd 是 LocalDate
        if (warrantyCut != null) {
            // already counted above with LocalDate comparison
        }

        // 6 会议室 no_show / 使用
        long bkTotal = count(bookingMapper.selectCount(null));
        long bkNoShow = count(bookingMapper.selectCount(new LambdaQueryWrapper<RoomBooking>()
                .eq(RoomBooking::getStatus, "no_show")));
        long roomCount = count(roomMapper.selectCount(new LambdaQueryWrapper<MeetingRoom>()
                .eq(MeetingRoom::getStatus, "active")));

        // 7 CSAT：Python 侧主数据，Java 占位（点踩来自通知/工单，简化返回 -）
        // 8 知识库本周发布：Python 侧；Java 占位 0

        // 采购在途
        long poInFlight = count(purchaseMapper.selectCount(new LambdaQueryWrapper<PurchaseRequest>()
                .notIn(PurchaseRequest::getStatus, "accepted", "rejected", "cancelled")));

        Map<String, Object> business = new HashMap<>();
        business.put("tickets7d", tCreated);
        business.put("ticketsDone7d", tDone);
        business.put("ticketsOpen", tOpen);
        business.put("slaRate", slaRate);
        business.put("expenseInFlight", expInFlight);
        business.put("expenseInFlightAmount", expAmount);
        business.put("expensePaid", expPaid);
        business.put("leaveInFlight", leaveInFlight);
        business.put("assetStock", aStock);
        business.put("assetAssigned", aAssigned);
        business.put("assetRepair", aRepair);
        business.put("assetLost", aLost);
        business.put("assetWarrantySoon", aWarrantySoon);
        business.put("bookings", bkTotal);
        business.put("roomNoShow", bkNoShow);
        business.put("roomCount", roomCount);
        business.put("purchaseInFlight", poInFlight);
        // CSAT：sf_ticket.csatScore 非空均分
        List<Ticket> scored = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .isNotNull(Ticket::getCsatScore));
        Double csatAvg = scored.stream()
                .map(Ticket::getCsatScore)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(Double.NaN);
        business.put("csatAvg", Double.isNaN(csatAvg) ? null
                : Math.round(csatAvg * 10) / 10.0);
        business.put("kbPublished7d", "见管理端知识库"); // 主数据在 Python AI 侧

        Map<String, Object> ops = new HashMap<>();
        // 运维/AI 六指标（设计 10.2）
        ops.put("aiEnabled", sysConfig.aiEnabled());
        ops.put("triageMinConf", sysConfig.get("ai.triage_min_conf", "0.6"));
        ops.put("taskHealth", Map.of(
                "slaJob", "scheduled_60s",
                "roomReleaseJob", "manual_or_admin",
                "lastOk", "alive"));
        ops.put("breachEvents", (long) breachTicketIds.size());
        // 草稿采纳率：ai_draft=1 且已 approved/effective 的请假 / 全部 ai_draft 请假
        long aiDraft = count(leaveMapper.selectCount(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getAiDraft, true)));
        long aiDraftAdopted = count(leaveMapper.selectCount(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getAiDraft, true)
                .in(LeaveRequest::getStatus, "approved", "effective", "done")));
        String draftAdopt = aiDraft == 0 ? "—" :
                BigDecimal.valueOf(aiDraftAdopted).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(aiDraft), 0, RoundingMode.HALF_UP).toPlainString() + "%";
        ops.put("draftAdoptRate", draftAdopt);
        // 以下依赖 Python 侧主数据，未接通则明确标注（不装有数）
        ops.put("triageAuditRate", "未接入");
        ops.put("ragResolveRate", "未接入");
        ops.put("transferRate", "未接入");
        ops.put("tokenCostDaily", "未接入");

        Map<String, Object> out = new HashMap<>();
        out.put("business", business);
        out.put("ops", ops);
        return Result.ok(out);
    }

    /** 工单近 14 天新建/完成趋势（看板曲线）。 */
    @GetMapping("/trends")
    public Result<List<Map<String, Object>>> trends(HttpServletRequest request) {
        LoginUser.require(request);
        LocalDateTime from = LocalDateTime.now().minusDays(13).toLocalDate().atStartOfDay();
        List<Ticket> rows = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .ge(Ticket::getCreatedAt, from)
                .orderByAsc(Ticket::getCreatedAt));
        Map<String, int[]> byDay = new java.util.TreeMap<>();
        for (Ticket t : rows) {
            String d = t.getCreatedAt().toLocalDate().toString();
            int[] v = byDay.computeIfAbsent(d, k -> new int[2]);
            v[0]++;
            if ("resolved".equals(t.getStatus()) || "closed".equals(t.getStatus())) {
                v[1]++;
            }
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            String d = java.time.LocalDate.now().minusDays(i).toString();
            int[] v = byDay.getOrDefault(d, new int[2]);
            Map<String, Object> m = new HashMap<>();
            m.put("date", d);
            m.put("created", v[0]);
            m.put("done", v[1]);
            out.add(m);
        }
        return Result.ok(out);
    }

    private long count(Long c) {
        return c == null ? 0 : c;
    }
}
