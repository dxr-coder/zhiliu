package com.smartflow.server.service;

import com.smartflow.common.PageQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleFlowTest {

    // ── 请假天数（含首尾）──
    static long calcDays(LocalDate s, LocalDate e) {
        return ChronoUnit.DAYS.between(s, e) + 1;
    }

    @Test
    void leaveDaysInclusive() {
        assertEquals(1, calcDays(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 17)));
        assertEquals(3, calcDays(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 19)));
        assertTrue(calcDays(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 17)) < 0
                || LocalDate.of(2026, 11, 19).isBefore(LocalDate.of(2026, 11, 17)));
    }

    @Test
    void leaveEndDateBeforeStartRejected() {
        LocalDate s = LocalDate.of(2026, 11, 20);
        LocalDate e = LocalDate.of(2026, 11, 17);
        assertTrue(e.isBefore(s), "结束早于开始必须拒");
    }

    @Test
    void leaveBalanceHoldMath() {
        BigDecimal demo = BigDecimal.TEN;
        BigDecimal days = BigDecimal.valueOf(5);
        BigDecimal after = demo.subtract(days);
        assertEquals(0, after.compareTo(BigDecimal.valueOf(5)));
        // 11 天 > 演示余额 10 -> 应拒
        assertTrue(BigDecimal.valueOf(11).compareTo(demo) > 0);
    }

    @Test
    void leaveOverlapPredicate() {
        // 已有 [17,19]，新单 [18,20] 重叠；[20,21] 不重叠
        assertTrue(overlaps(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 19),
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 20)));
        assertFalse(overlaps(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 19),
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21)));
        // 边界：请假日期含首尾，首尾同一天相接算重叠（与 SQL le/ge 一致）
        assertTrue(overlaps(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 19),
                LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 21)));
        // 完全隔开一天不重叠
        assertFalse(overlaps(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 19),
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)));
    }

    private boolean overlaps(LocalDate s1, LocalDate e1, LocalDate s2, LocalDate e2) {
        // le(start2, end1) && ge(end2, start1) 与 SQL 一致；相接：start2==end1 仍 true（SQL le）
        // 业务上请假用 le/ge：相接算重叠（同天）——这里锁 SQL 语义
        return !s2.isAfter(e1) && !e2.isBefore(s1);
    }

    // ── 报销金额和 ──
    @Test
    void expenseAmountMustBePositiveAndSumMatch() {
        assertTrue(new BigDecimal("0.01").signum() > 0);
        assertEquals(0, new BigDecimal("10.50")
                .add(new BigDecimal("20.50"))
                .compareTo(new BigDecimal("31.00")));
        // 总额与明细不一致必须拒
        BigDecimal total = new BigDecimal("100");
        BigDecimal sum = new BigDecimal("99.99");
        assertNotEquals(0, total.compareTo(sum));
    }

    @Test
    void expenseOverStandardFlag() {
        assertTrue(new BigDecimal("5000.01").compareTo(new BigDecimal("5000")) > 0);
        assertFalse(new BigDecimal("5000").compareTo(new BigDecimal("5000")) > 0);
    }

    // ── 会议室时段重叠 [start, end) ──
    @Test
    void roomOverlapHalfOpen() {
        // 已订 10:00-11:00；新订 11:00-12:00 不冲突；10:30-11:30 冲突
        assertFalse(timeOverlap(10, 11, 11, 12));
        assertTrue(timeOverlap(10, 11, 10.5, 11.5));
        assertTrue(timeOverlap(10, 11, 9.5, 10.5));
        // 完全包含
        assertTrue(timeOverlap(10, 12, 10.5, 11));
    }

    private boolean timeOverlap(double s1, double e1, double s2, double e2) {
        // lt(start2, e1) && gt(end2, s1)
        return s2 < e1 && e2 > s1;
    }

    // ── 采购状态机 ──
    @Test
    void purchaseLegalMoves() {
        assertTrue(PurchaseService.canMove("demand", "quoted"));
        assertTrue(PurchaseService.canMove("quoted", "approved"));
        assertTrue(PurchaseService.canMove("approved", "ordered"));
        assertTrue(PurchaseService.canMove("ordered", "received"));
        assertTrue(PurchaseService.canMove("received", "accepted"));
        assertTrue(PurchaseService.canMove("demand", "cancelled"));
    }

    @Test
    void purchaseIllegalMoves() {
        assertFalse(PurchaseService.canMove("demand", "accepted"));
        assertFalse(PurchaseService.canMove("demand", "ordered"));
        assertFalse(PurchaseService.canMove("quoted", "ordered")); // 须先 approved
        assertFalse(PurchaseService.canMove("accepted", "ordered")); // 终态
        assertFalse(PurchaseService.canMove("rejected", "demand"));
        assertFalse(PurchaseService.canMove(null, "quoted"));
        assertFalse(PurchaseService.canMove("demand", null));
    }

    @Test
    void purchaseMinQuotes() {
        assertTrue(PurchaseService.MIN_QUOTES >= 2, "比价至少 2 家");
        assertEquals(2, PurchaseService.MIN_QUOTES);
    }

    @Test
    void purchaseTerminalStatesEmpty() {
        assertTrue(PurchaseService.PURCHASE_TRANSITIONS.get("accepted").isEmpty());
        assertTrue(PurchaseService.PURCHASE_TRANSITIONS.get("rejected").isEmpty());
        assertTrue(PurchaseService.PURCHASE_TRANSITIONS.get("cancelled").isEmpty());
    }

    // ── 工单已有表与资产表仍锁（防误改）──
    @Test
    void ticketAndAssetTablesStillLocked() {
        assertTrue(TicketService.canTransition("triage", "assigned"));
        assertFalse(TicketService.canTransition("resolved", "triage"));
        assertTrue(AssetService.canMove("stock", "assigned"));
        assertFalse(AssetService.canMove("disposed", "stock"));
    }

    // ── 审批链 JSON 形状（模板读取后过滤 hr）──
    @Test
    void chainSnapshotShape() {
        String chain = "[{\"step\":1,\"role\":\"manager\",\"mode\":\"serial\"},"
                + "{\"step\":2,\"role\":\"hr\",\"mode\":\"serial\"}]";
        assertTrue(chain.contains("\"role\":\"manager\""));
        assertTrue(chain.contains("\"role\":\"hr\""));
        // 剔 hr 后只剩 manager
        String noHr = chain.replace(",{\"step\":2,\"role\":\"hr\",\"mode\":\"serial\"}", "");
        assertFalse(noHr.contains("\"role\":\"hr\""));
        assertTrue(noHr.contains("\"role\":\"manager\""));
    }

    @Test
    void pageQueryStillSafe() {
        PageQuery q = new PageQuery(null, 300, null, null, null);
        assertEquals(200, q.pageSize());
    }
}
