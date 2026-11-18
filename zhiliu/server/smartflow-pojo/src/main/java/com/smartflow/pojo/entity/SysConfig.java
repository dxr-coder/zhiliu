package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_sys_config")
public class SysConfig {
    @TableId(type = IdType.INPUT)
    private String cfgKey;
    private String cfgValue;
    private LocalDateTime updatedAt;
}
