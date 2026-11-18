package com.smartflow.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class SysConfigService {

    private final com.smartflow.server.mapper.SysConfigMapper configMapper;

    public SysConfigService(com.smartflow.server.mapper.SysConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public String get(String key, String def) {
        var e = configMapper.selectById(key);
        return e == null || e.getCfgValue() == null ? def : e.getCfgValue();
    }

    public boolean aiEnabled() {
        return !"false".equalsIgnoreCase(get("ai.enabled", "true"));
    }

    public Map<String, String> all() {
        Map<String, String> m = new HashMap<>();
        configMapper.selectList(null).forEach(e -> m.put(e.getCfgKey(), e.getCfgValue()));
        return m;
    }

    /** admin 写开关。 */
    public Map<String, String> set(LoginUser user, String key, String value) {
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(403, "仅 admin 可改系统开关");
        }
        if (key == null || key.isBlank() || value == null) {
            throw new BusinessException("key/value 必填");
        }
        var e = configMapper.selectById(key);
        if (e == null) {
            e = new com.smartflow.pojo.entity.SysConfig();
            e.setCfgKey(key);
            e.setCfgValue(value);
            configMapper.insert(e);
        } else {
            e.setCfgValue(value);
            configMapper.updateById(e);
        }
        return all();
    }
}
