package com.smartflow.server.controller;

import com.smartflow.common.BusinessException;
import com.smartflow.common.Result;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.UserMapper;
import com.smartflow.server.service.SysConfigService;
import com.smartflow.server.task.SlaScanJob;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminOpsController {

    private final SlaScanJob slaScanJob;
    private final com.smartflow.server.service.RoomService roomService;
    private final SysConfigService sysConfig;
    private final UserMapper userMapper;
    private final com.smartflow.server.service.ImportService importService;
    private final com.smartflow.server.service.ExportService exportService;

    public AdminOpsController(SlaScanJob slaScanJob,
                              com.smartflow.server.service.RoomService roomService,
                              SysConfigService sysConfig,
                              UserMapper userMapper,
                              com.smartflow.server.service.ImportService importService,
                              com.smartflow.server.service.ExportService exportService) {
        this.slaScanJob = slaScanJob;
        this.roomService = roomService;
        this.sysConfig = sysConfig;
        this.userMapper = userMapper;
        this.importService = importService;
        this.exportService = exportService;
    }

    @PostMapping("/sla-scan")
    public Result<Map<String, Integer>> triggerSlaScan(HttpServletRequest request) {
        LoginUser u = LoginUser.require(request);
        if (!u.isAdmin()) {
            throw new BusinessException(403, "仅 admin 可触发");
        }
        int hit = slaScanJob.doScan();
        return Result.ok(Map.of("hit", hit));
    }

    /** 爽约释放：过签到截止仍 booked -> no_show。 */
    @PostMapping("/room-release")
    public Result<Map<String, Integer>> roomRelease(HttpServletRequest request) {
        LoginUser u = LoginUser.require(request);
        if (!u.isAdmin()) {
            throw new BusinessException(403, "仅 admin 可触发");
        }
        int n = roomService.releaseNoShow();
        return Result.ok(Map.of("released", n));
    }

    /** AI 全局降级开关：{key:"ai.enabled", value:"false"}。 */
    @PostMapping("/sys-config")
    public Result<Map<String, String>> setConfig(HttpServletRequest request,
                                                 @RequestBody Map<String, String> body) {
        LoginUser u = LoginUser.require(request);
        return Result.ok(sysConfig.set(u, body.get("key"), body.get("value")));
    }

    @GetMapping("/sys-config")
    public Result<Map<String, String>> getConfig(HttpServletRequest request) {
        LoginUser.require(request);
        return Result.ok(sysConfig.all());
    }

    /** 用户导出（admin/hr）。 */
    @GetMapping("/users/export")
    public org.springframework.http.ResponseEntity<byte[]> exportUsers(HttpServletRequest request)
            throws java.io.IOException {
        LoginUser u = LoginUser.require(request);
        if (!u.isAdmin() && !u.hasRole("hr")) {
            throw new BusinessException(403, "仅 admin/hr 可导出用户");
        }
        var rows = userMapper.selectList(null);
        if (rows.size() > 500) {
            rows = rows.subList(0, 500);
        }
        byte[] bytes = exportService.exportUsers(rows);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=users.xlsx")
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
    }

    /** 用户导入（admin/hr）。 */
    @PostMapping("/users/import")
    public Result<com.smartflow.server.service.ImportService.ImportResult> importUsers(
            HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        return Result.ok(importService.importUsers(LoginUser.require(request), file));
    }
}
