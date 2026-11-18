package com.smartflow.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.smartflow.server", "com.smartflow.common"})
@EnableScheduling
@MapperScan("com.smartflow.server.mapper")
public class SmartFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartFlowApplication.class, args);
    }
}
