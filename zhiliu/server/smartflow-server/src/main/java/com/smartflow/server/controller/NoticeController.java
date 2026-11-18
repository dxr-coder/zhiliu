package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.pojo.entity.Notice;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.NoticeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 通知中心接口。 */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    public Result<List<Notice>> list(HttpServletRequest request,
                                     @RequestParam(required = false) Boolean unread,
                                     @RequestParam(required = false) String type) {
        LoginUser u = LoginUser.require(request);
        return Result.ok(noticeService.list(u.uid(), unread, type));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unread(HttpServletRequest request) {
        LoginUser u = LoginUser.require(request);
        return Result.ok(Map.of("count", noticeService.unreadCount(u.uid())));
    }

    @PostMapping("/read-all")
    public Result<Void> readAll(HttpServletRequest request) {
        LoginUser u = LoginUser.require(request);
        noticeService.readAll(u.uid());
        return Result.ok();
    }
}
