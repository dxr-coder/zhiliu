package com.smartflow.server.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartflow.common.JwtUtil;
import com.smartflow.common.Result;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${smartflow.jwt.secret}")
    private String secret;

    private final ObjectMapper om = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            write(response, 401, "未登录");
            return false;
        }
        try {
            Claims claims = JwtUtil.parse(secret, header.substring(7));
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            LoginUser user = new LoginUser(
                    claims.get("uid", Long.class),
                    roles == null ? List.of() : roles,
                    claims.get("deptId", Long.class),
                    claims.get("username", String.class)
            );
            request.setAttribute("sf_user", user);
            return true;
        } catch (Exception e) {
            write(response, 401, "登录态无效或已过期");
            return false;
        }
    }

    private void write(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code == 401 ? 401 : 200);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(om.writeValueAsString(Result.fail(code, msg)));
    }

    /** 登录上下文（B1 简版，可换 ThreadLocal） */
    public record LoginUser(Long uid, List<String> roles, Long deptId, String username) {

        public boolean hasRole(String role) {
            return roles != null && roles.contains(role);
        }

        public boolean isAdmin() {
            return hasRole("admin");
        }

        public static LoginUser require(HttpServletRequest request) {
            Object attr = request.getAttribute("sf_user");
            if (attr instanceof LoginUser u) {
                return u;
            }
            throw new IllegalStateException("missing sf_user attribute");
        }
    }
}
