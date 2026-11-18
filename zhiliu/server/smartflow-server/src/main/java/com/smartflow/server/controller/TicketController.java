package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.Ticket;
import com.smartflow.pojo.entity.TicketEvent;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final com.smartflow.server.service.ExportService exportService;

    public TicketController(TicketService ticketService,
                            com.smartflow.server.service.ExportService exportService) {
        this.ticketService = ticketService;
        this.exportService = exportService;
    }

    /** 列表五件套：GET /api/tickets?page&size&keyword&status */
    @GetMapping
    public Result<?> page(@org.springframework.web.bind.annotation.ModelAttribute com.smartflow.common.PageQuery q) {
        var page = ticketService.pageQuery(q);
        return Result.ok(com.smartflow.common.PageResult.of(page.getTotal(), page.getRecords()));
    }

    /** Excel 导出 */
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @org.springframework.web.bind.annotation.ModelAttribute com.smartflow.common.PageQuery q) throws java.io.IOException {
        q = new com.smartflow.common.PageQuery(q.page(), 500, q.keyword(), q.status(), q.sort());
        var page = ticketService.pageQuery(q);
        byte[] bytes = exportService.exportTickets(page.getRecords());
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=tickets.xlsx")
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
    }

    @PostMapping
    public Result<Ticket> create(HttpServletRequest request, @RequestBody Ticket body) {
        return Result.ok(ticketService.create(LoginUser.require(request), body));
    }

    @GetMapping("/my")
    public Result<List<Ticket>> my(HttpServletRequest request) {
        return Result.ok(ticketService.myList(LoginUser.require(request)));
    }

    /** POST /api/tickets/{id}/transition {toStatus, reason, assigneeId} */
    @PostMapping("/{id}/transition")
    public Result<Ticket> transition(HttpServletRequest request, @PathVariable Long id,
                                     @RequestBody Map<String, Object> body) {
        String to = String.valueOf(body.get("toStatus"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        Long assignee = body.get("assigneeId") == null ? null
                : Long.parseLong(String.valueOf(body.get("assigneeId")));
        return Result.ok(ticketService.transition(LoginUser.require(request), id, to, reason, assignee));
    }

    @GetMapping("/{id}/events")
    public Result<List<TicketEvent>> events(@PathVariable Long id) {
        return Result.ok(ticketService.events(id));
    }
}
