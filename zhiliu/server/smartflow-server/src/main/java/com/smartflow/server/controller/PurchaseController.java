package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.PurchaseRequest;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.PurchaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @GetMapping
    public Result<List<PurchaseRequest>> list(@RequestParam(required = false) String status) {
        return Result.ok(purchaseService.list(status));
    }

    @GetMapping("/my")
    public Result<List<PurchaseRequest>> my(HttpServletRequest request) {
        return Result.ok(purchaseService.myList(LoginUser.require(request)));
    }

    @PostMapping
    public Result<PurchaseRequest> create(HttpServletRequest request, @RequestBody PurchaseRequest body) {
        return Result.ok(purchaseService.create(LoginUser.require(request), body));
    }

    /** 录比价 {quotesJson} */
    @PostMapping("/{id}/quotes")
    public Result<PurchaseRequest> quotes(HttpServletRequest request, @PathVariable Long id,
                                          @RequestBody Map<String, Object> body) {
        String json = String.valueOf(body.getOrDefault("quotesJson", "[]"));
        return Result.ok(purchaseService.addQuotes(LoginUser.require(request), id, json));
    }

    @PostMapping("/{id}/submit")
    public Result<PurchaseRequest> submit(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(purchaseService.submitApproval(LoginUser.require(request), id));
    }

    @PostMapping("/{id}/approve")
    public Result<PurchaseRequest> approve(HttpServletRequest request, @PathVariable Long id,
                                           @RequestBody Map<String, Object> body) {
        boolean agree = Boolean.TRUE.equals(body.get("agree"));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        return Result.ok(purchaseService.approveStep(LoginUser.require(request), id, agree, comment));
    }

    @PostMapping("/{id}/ordered")
    public Result<PurchaseRequest> ordered(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(purchaseService.markOrdered(LoginUser.require(request), id));
    }

    @PostMapping("/{id}/received")
    public Result<PurchaseRequest> received(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(purchaseService.markReceived(LoginUser.require(request), id));
    }

    @PostMapping("/{id}/accepted")
    public Result<PurchaseRequest> accepted(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(purchaseService.markAccepted(LoginUser.require(request), id));
    }

    /** 挂报销 {expenseId} */
    @PostMapping("/{id}/link-expense")
    public Result<PurchaseRequest> linkExpense(HttpServletRequest request, @PathVariable Long id,
                                               @RequestBody Map<String, Object> body) {
        Long expenseId = Long.parseLong(String.valueOf(body.get("expenseId")));
        return Result.ok(purchaseService.linkExpense(LoginUser.require(request), id, expenseId));
    }
}
