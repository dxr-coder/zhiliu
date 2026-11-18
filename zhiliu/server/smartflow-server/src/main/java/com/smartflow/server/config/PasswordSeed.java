package com.smartflow.server.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.pojo.entity.User;
import com.smartflow.server.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordSeed implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordSeed.class);

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public PasswordSeed(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void run(String... args) {
        String hash = encoder.encode("123456");
        var users = userMapper.selectList(new LambdaQueryWrapper<>());
        for (User u : users) {
            String ph = u.getPasswordHash();
            if (ph == null || ph.startsWith("{noop}") || !ph.startsWith("$2")) {
                u.setPasswordHash(hash);
                userMapper.updateById(u);
            }
        }
        log.info("[seed] password hash ready for {} users (dev password=123456)", users.size());
    }
}
