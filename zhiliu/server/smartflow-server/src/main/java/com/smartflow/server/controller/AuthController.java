package com.smartflow.server.controller;

import com.smartflow.common.Result;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Validated @RequestBody LoginReq req) {
        return Result.ok(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpServletRequest request) {
        LoginUser u = LoginUser.require(request);
        return Result.ok(Map.of(
                "uid", u.uid(),
                "username", u.username() == null ? "" : u.username(),
                "roles", u.roles(),
                "deptId", u.deptId() == null ? 0L : u.deptId(),
                "perms", authService.permsOf(u.uid())
        ));
    }

    public record RegisterReq(@NotBlank String username, @NotBlank String password,
                              String realName) {
    }

    /** 注册（业务账号）：建 sf_user + employee 角色，直接发 token。 */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Validated @RequestBody RegisterReq req) {
        return Result.ok(authService.register(req.username(), req.password(), req.realName()));
    }
}
