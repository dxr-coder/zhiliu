package com.smartflow.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.common.JwtUtil;
import com.smartflow.pojo.entity.User;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.RoleMapper;
import com.smartflow.server.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${smartflow.jwt.secret}")
    private String secret;

    @Value("${smartflow.jwt.access-ttl-minutes}")
    private long ttlMinutes;

    public AuthService(UserMapper userMapper, RoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
    }

    public Map<String, Object> login(String username, String password) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (user == null || !"active".equals(user.getStatus())) {
            throw new BusinessException(401, "用户不存在或已禁用");
        }
        String hash = user.getPasswordHash();
        boolean ok = hash != null && hash.startsWith("$2")
                ? encoder.matches(password, hash)
                : password.equals(hash != null && hash.startsWith("{noop}")
                    ? hash.substring(7) : hash);
        if (!ok) {
            throw new BusinessException(401, "密码错误");
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(user.getId());
        String token = JwtUtil.generate(secret, ttlMinutes, user.getId(), roles,
                user.getDeptId(), user.getUsername());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("expiresIn", ttlMinutes * 60);
        Map<String, Object> u = new HashMap<>();
        u.put("id", user.getId());
        u.put("username", user.getUsername());
        u.put("realName", user.getRealName());
        u.put("deptId", user.getDeptId());
        u.put("roles", roles);
        data.put("user", u);
        return data;
    }

    /** 注册业务账号：重名拒绝；默认 employee 角色；返回与 login 相同形状。 */
    public Map<String, Object> register(String username, String password, String realName) {
        if (username == null || username.isBlank() || password == null || password.length() < 4) {
            throw new BusinessException("用户名/密码不合法（密码至少 4 位）");
        }
        username = username.trim();
        Long exist = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (exist != null && exist > 0) {
            throw new BusinessException(409, "用户名已存在");
        }
        User u = new User();
        u.setUsername(username);
        u.setEmpNo("E" + System.currentTimeMillis() % 100000000L); // emp_no 非空唯一
        u.setRealName(realName == null || realName.isBlank() ? username : realName.trim());
        u.setPasswordHash(encoder.encode(password));
        u.setStatus("active");
        u.setDeptId(301L);
        userMapper.insert(u);
        Long empRoleId = roleMapper.selectIdByCode("employee");
        if (empRoleId != null) {
            roleMapper.insertUserRole(u.getId(), empRoleId);
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(u.getId());
        String token = JwtUtil.generate(secret, ttlMinutes, u.getId(), roles, u.getDeptId(), u.getUsername());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("expiresIn", ttlMinutes * 60);
        Map<String, Object> um = new HashMap<>();
        um.put("id", u.getId());
        um.put("username", u.getUsername());
        um.put("realName", u.getRealName());
        um.put("deptId", u.getDeptId());
        um.put("roles", roles);
        data.put("user", um);
        return data;
    }

    public List<String> permsOf(Long userId) {

        return roleMapper.selectPermCodesByUserId(userId);
    }

    public static LoginUser currentUser(jakarta.servlet.http.HttpServletRequest request) {
        return LoginUser.require(request);
    }
}
