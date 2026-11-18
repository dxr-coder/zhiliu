package com.smartflow.server.service;

import com.smartflow.common.PageQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TicketFlowTest {

    @Test
    void legalTransitionsPass() {
        assertTrue(TicketService.canTransition("triage", "assigned"));
        assertTrue(TicketService.canTransition("assigned", "in_progress"));
        assertTrue(TicketService.canTransition("in_progress", "resolved"));
        assertTrue(TicketService.canTransition("resolved", "closed"));
        assertTrue(TicketService.canTransition("closed", "assigned")); // reopen
        assertTrue(TicketService.canTransition("in_progress", "waiting_user"));
        assertTrue(TicketService.canTransition("waiting_user", "in_progress"));
    }

    @Test
    void illegalTransitionsBlocked() {
        assertFalse(TicketService.canTransition("resolved", "triage"));
        assertFalse(TicketService.canTransition("triage", "closed"));
        assertFalse(TicketService.canTransition("rejected", "assigned"));
        assertFalse(TicketService.canTransition("duplicate", "in_progress"));
        assertFalse(TicketService.canTransition(null, "assigned"));
        assertFalse(TicketService.canTransition("triage", null));
    }

    @Test
    void terminalStatesHaveNoForwardExceptReopen() {
        assertTrue(TicketService.TRANSITIONS.get("rejected").isEmpty());
        assertTrue(TicketService.TRANSITIONS.get("duplicate").isEmpty());
        assertEquals(java.util.List.of("reopen"), TicketService.TRANSITIONS.get("closed"));
    }

    @Test
    void pageQueryBounds() {
        PageQuery q1 = new PageQuery(null, null, null, null, null);
        assertEquals(1, q1.pageNo());
        assertEquals(20, q1.pageSize());
        assertFalse(q1.hasKeyword());
        assertFalse(q1.hasStatus());

        PageQuery q2 = new PageQuery(0, 9999, "  ", "in_progress", null);
        assertEquals(1, q2.pageNo());
        assertEquals(200, q2.pageSize()); // 上限
        assertFalse(q2.hasKeyword());      // 空白不算关键字
        assertTrue(q2.hasStatus());
    }
}
