package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.Asset;
import com.smartflow.pojo.entity.AssetFlow;
import com.smartflow.pojo.entity.OffboardChecklist;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.AssetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;
    private final com.smartflow.server.service.ImportService importService;
    private final com.smartflow.server.service.ExportService exportService;

    public AssetController(AssetService assetService,
                           com.smartflow.server.service.ImportService importService,
                           com.smartflow.server.service.ExportService exportService) {
        this.assetService = assetService;
        this.importService = importService;
        this.exportService = exportService;
    }

    @GetMapping
    public Result<List<Asset>> list(@RequestParam(required = false) String status) {
        return Result.ok(assetService.list(status));
    }

    /** 入库（it/admin） */
    @PostMapping
    public Result<Asset> stockIn(HttpServletRequest request, @RequestBody Asset body) {
        return Result.ok(assetService.stockIn(LoginUser.require(request), body));
    }

    /** 领用 {holderId?} */
    @PostMapping("/{id}/apply")
    public Result<Asset> apply(HttpServletRequest request, @PathVariable Long id,
                               @RequestBody(required = false) Map<String, Object> body) {
        Long holder = body == null || body.get("holderId") == null
                ? null : Long.parseLong(String.valueOf(body.get("holderId")));
        return Result.ok(assetService.apply(LoginUser.require(request), id, holder));
    }

    @PostMapping("/{id}/return")
    public Result<Asset> ret(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(assetService.returnAsset(LoginUser.require(request), id));
    }

    @PostMapping("/{id}/repair")
    public Result<Asset> repair(HttpServletRequest request, @PathVariable Long id,
                                @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null
                ? null : String.valueOf(body.get("reason"));
        return Result.ok(assetService.repair(LoginUser.require(request), id, reason));
    }

    @PostMapping("/{id}/lost")
    public Result<Asset> lost(HttpServletRequest request, @PathVariable Long id,
                             @RequestBody Map<String, Object> body) {
        String reason = String.valueOf(body.getOrDefault("reason", "丢失"));
        return Result.ok(assetService.lost(LoginUser.require(request), id, reason));
    }

    @PostMapping("/{id}/compensate")
    public Result<Asset> compensate(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(assetService.compensate(LoginUser.require(request), id));
    }

    @PostMapping("/{id}/dispose")
    public Result<Asset> dispose(HttpServletRequest request, @PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null
                ? "报废" : String.valueOf(body.get("reason"));
        return Result.ok(assetService.dispose(LoginUser.require(request), id, reason));
    }

    @GetMapping("/{id}/flows")
    public Result<List<AssetFlow>> flows(@PathVariable Long id) {
        return Result.ok(assetService.flows(id));
    }

    /** Excel 导出资产台账 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String status)
            throws java.io.IOException {
        var rows = assetService.list(status);
        if (rows.size() > 500) {
            rows = rows.subList(0, 500);
        }
        byte[] bytes = exportService.exportAssets(rows);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=assets.xlsx")
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
    }

    /** Excel 导入（multipart file，列 sn,name,location） */
    @PostMapping("/import")
    public Result<com.smartflow.server.service.ImportService.ImportResult> importExcel(
            HttpServletRequest request, @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return Result.ok(importService.importAssets(LoginUser.require(request), file));
    }

    // ── 离职 ──
    @PostMapping("/offboard")
    public Result<OffboardChecklist> openOffboard(@RequestBody Map<String, Long> body) {
        return Result.ok(assetService.openOffboard(body.get("userId")));
    }

    @GetMapping("/offboard")
    public Result<List<OffboardChecklist>> listOffboard() {
        return Result.ok(assetService.listOffboard());
    }

    /** 勾选单项 {item: assets|accounts|permissions|badge|loan, done: true} */
    @PostMapping("/offboard/{id}/tick")
    public Result<OffboardChecklist> tick(HttpServletRequest request, @PathVariable Long id,
                                          @RequestBody Map<String, Object> body) {
        String item = String.valueOf(body.get("item"));
        boolean done = Boolean.TRUE.equals(body.get("done"));
        return Result.ok(assetService.tickOffboardItem(LoginUser.require(request), id, item, done));
    }

    /** 完成：五项全绿才过；{forceAll:true} 一键勾四软项（资产仍须真清零）。 */
    @PostMapping("/offboard/{id}/complete")
    public Result<OffboardChecklist> complete(HttpServletRequest request, @PathVariable Long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        boolean forceAll = body != null && Boolean.TRUE.equals(body.get("forceAll"));
        return Result.ok(assetService.completeOffboard(LoginUser.require(request), id, forceAll));
    }
}
