package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.Expense;
import com.smartflow.pojo.entity.ExpenseItem;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.ExpenseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;
    private final com.smartflow.server.service.ExportService exportService;
    private final com.smartflow.server.service.ImportService importService;

    public ExpenseController(ExpenseService expenseService,
                             com.smartflow.server.service.ExportService exportService,
                             com.smartflow.server.service.ImportService importService) {
        this.expenseService = expenseService;
        this.exportService = exportService;
        this.importService = importService;
    }

    @GetMapping
    public Result<?> page(@org.springframework.web.bind.annotation.ModelAttribute com.smartflow.common.PageQuery q) {
        var page = expenseService.pageQuery(q);
        return Result.ok(com.smartflow.common.PageResult.of(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @org.springframework.web.bind.annotation.ModelAttribute com.smartflow.common.PageQuery q) throws java.io.IOException {
        q = new com.smartflow.common.PageQuery(q.page(), 500, q.keyword(), q.status(), q.sort());
        var page = expenseService.pageQuery(q);
        byte[] bytes = exportService.exportExpenses(page.getRecords());
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=expenses.xlsx")
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
    }

    public record SubmitReq(Expense expense, List<ExpenseItem> items, String idempotencyKey) {
    }

    @PostMapping
    public Result<Expense> submit(HttpServletRequest request, @RequestBody SubmitReq body) {
        return Result.ok(expenseService.submit(LoginUser.require(request),
                body.expense(), body.items(), body.idempotencyKey()));
    }

    @GetMapping("/my")
    public Result<List<Expense>> my(HttpServletRequest request) {
        return Result.ok(expenseService.myList(LoginUser.require(request)));
    }

    /** Excel 导入报销明细（列 type,amount,invoiceNo,itemName）→ 逐行 draft 单 */
    @PostMapping("/import")
    public Result<com.smartflow.server.service.ImportService.ImportResult> importExcel(
            HttpServletRequest request,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return Result.ok(importService.importExpenses(LoginUser.require(request), file));
    }

    @PostMapping("/{id}/approve")
    public Result<Expense> approve(HttpServletRequest request, @PathVariable Long id,
                                   @RequestBody Map<String, Object> body) {
        boolean agree = Boolean.TRUE.equals(body.get("agree"));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        return Result.ok(expenseService.approve(LoginUser.require(request), id, agree, comment));
    }

    @PostMapping("/{id}/withdraw")
    public Result<Expense> withdraw(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(expenseService.withdraw(LoginUser.require(request), id));
    }

    @PostMapping("/{id}/pay")
    public Result<Expense> pay(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(expenseService.pay(LoginUser.require(request), id));
    }

    @PostMapping("/{id}/void")
    public Result<Expense> voidExpense(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(expenseService.voidExpense(LoginUser.require(request), id));
    }
}
