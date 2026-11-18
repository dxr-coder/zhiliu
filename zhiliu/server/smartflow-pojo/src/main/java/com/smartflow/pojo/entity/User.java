package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String empNo;
    private String username;
    /** BCrypt，不存明文 */
    private String passwordHash;
    private String realName;
    private String phone;
    private Long deptId;
    /** active / disabled */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
