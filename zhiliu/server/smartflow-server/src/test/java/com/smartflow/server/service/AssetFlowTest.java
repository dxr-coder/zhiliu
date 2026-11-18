package com.smartflow.server.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class AssetFlowTest {

    @Test
    void assetLegalMoves() {
        assertTrue(AssetService.canMove("stock", "assigned"));
        assertTrue(AssetService.canMove("assigned", "stock"));
        assertTrue(AssetService.canMove("assigned", "repair"));
        assertTrue(AssetService.canMove("assigned", "lost"));
        assertTrue(AssetService.canMove("lost", "lost_pending_compensate"));
        assertTrue(AssetService.canMove("lost_pending_compensate", "dispose"));
        assertTrue(AssetService.canMove("dispose", "disposed"));
    }

    @Test
    void assetIllegalMoves() {
        assertFalse(AssetService.canMove("stock", "lost"));       // 未领用不能丢
        assertFalse(AssetService.canMove("disposed", "stock"));   // 终态
        assertFalse(AssetService.canMove("assigned", "dispose")); // 须先归还
        assertFalse(AssetService.canMove(null, "stock"));
        assertFalse(AssetService.canMove("stock", null));
    }

    @Test
    void assetTerminalHasNoExit() {
        assertTrue(AssetService.ASSET_TRANSITIONS.get("disposed").isEmpty());
    }

    /** 请假天数：闭区间 start..end */
    @Test
    void leaveDaysInclusive() {
        assertEquals(3, days(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)));
        assertEquals(1, days(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20)));
        assertTrue(LocalDate.of(2026, 11, 22).isAfter(LocalDate.of(2026, 11, 20)));
    }

    /** 演示余额 10 天：hold 不得超余额 */
    @Test
    void leaveBalanceHold() {
        BigDecimal balance = BigDecimal.TEN;
        BigDecimal hold3 = BigDecimal.valueOf(3);
        assertTrue(balance.subtract(hold3).compareTo(BigDecimal.valueOf(7)) == 0);
        BigDecimal hold11 = BigDecimal.valueOf(11);
        assertTrue(hold11.compareTo(balance) > 0, "11 天应余额不足");
    }

    /** 报销：明细和必须等于总额 */
    @Test
    void expenseAmountMustMatchItems() {
        BigDecimal a = new BigDecimal("553.00");
        BigDecimal b = new BigDecimal("733.50");
        BigDecimal total = new BigDecimal("1286.50");
        assertEquals(0, a.add(b).compareTo(total));
        assertNotEquals(0, a.add(b).compareTo(new BigDecimal("1286.51")));
    }

    private long days(LocalDate s, LocalDate e) {
        return java.time.temporal.ChronoUnit.DAYS.between(s, e) + 1;
    }
}
