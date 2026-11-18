package com.smartflow.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.common.Result;
import com.smartflow.pojo.entity.User;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.RoleMapper;
import com.smartflow.server.mapper.UserMapper;
import com.smartflow.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final AuditService auditService;

    public UserController(UserMapper userMapper, RoleMapper roleMapper, AuditService auditService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.auditService = auditService;
    }

    private void requireAdmin(LoginUser u) {
        if (!u.isAdmin()) {
            throw new BusinessException(403, "仅 admin 可管理用户");
        }
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(HttpServletRequest request) {
        requireAdmin(LoginUser.require(request));
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .orderByAsc(User::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("realName", u.getRealName());
            m.put("deptId", u.getDeptId());
            m.put("status", u.getStatus());
            m.put("roles", roleMapper.selectRoleCodesByUserId(u.getId()));
            m.put("role_name", primaryRole(u.getId()));
            out.add(m);
        }
        return Result.ok(out);
    }

    private String primaryRole(Long userId) {
        List<String> rs = roleMapper.selectRoleCodesByUserId(userId);
        if (rs.contains("admin")) return "admin";
        if (rs.contains("manager")) return "manager";
        if (rs.contains("hr")) return "hr";
        if (rs.contains("finance")) return "finance";
        if (rs.contains("agent")) return "agent";
        return rs.isEmpty() ? "employee" : rs.get(0);
    }

    /** 启停 {status: active|disabled} */
    @PutMapping("/{id}/status")
    public Result<User> setStatus(HttpServletRequest request, @PathVariable Long id,
                                  @RequestParam String status) {
        LoginUser u = LoginUser.require(request);
        requireAdmin(u);
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw new BusinessException("status 非法");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        auditService.record(u.uid(), "user.status", "user", user.getUsername(),
                null, status, null, null);
        return Result.ok(user);
    }

    /** 改主角色 {roleCode: employee|manager|hr|finance|agent|admin}（替换全部角色，保留 employee 底） */
    @PutMapping("/{id}/role")
    public Result<Map<String, Object>> setRole(HttpServletRequest request, @PathVariable Long id,
                                               @RequestBody Map<String, String> body) {
        LoginUser u = LoginUser.require(request);
        requireAdmin(u);
        String code = body.get("roleCode");
        if (code == null || code.isBlank()) {
            throw new BusinessException("roleCode 必填");
        }
        if (id.equals(u.uid()) && !"admin".equals(code)) {
            throw new BusinessException(409, "不能把自己降级为非管理员");
        }
        Long roleId = roleMapper.selectIdByCode(code);
        if (roleId == null) {
            throw new BusinessException(404, "角色不存在: " + code);
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        roleMapper.clearUserRoles(id);
        // 人人至少 employee
        Long empId = roleMapper.selectIdByCode("employee");
        if (empId != null) {
            roleMapper.insertUserRole(id, empId);
        }
        if (!"employee".equals(code)) {
            roleMapper.insertUserRole(id, roleId);
        }
        auditService.record(u.uid(), "user.role", "user", user.getUsername(),
                null, code, null, null);
        Map<String, Object> out = new HashMap<>();
        out.put("id", id);
        out.put("roleCode", code);
        out.put("roles", roleMapper.selectRoleCodesByUserId(id));
        return Result.ok(out);
    }

    /** 选人下拉用：任意登录用户可取精简列表（仅 id/姓名/用户名），不含敏感字段。 */
    @GetMapping("/pickable")
    public Result<List<Map<String, Object>>> pickable(HttpServletRequest request) {
        LoginUser.require(request);
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, "active")
                .orderByAsc(User::getRealName));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (User u : users) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("realName", u.getRealName() == null ? u.getUsername() : u.getRealName());
            m.put("roles", roleMapper.selectRoleCodesByUserId(u.getId()));
            out.add(m);
        }
        return Result.ok(out);
    }
}
