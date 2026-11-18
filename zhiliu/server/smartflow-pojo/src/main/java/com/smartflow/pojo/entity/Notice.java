package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_notice")
public class Notice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    /** sla_breach|approval|ticket|kb_publish|asset_warranty */
    private String type;
    private String title;
    private String body;
    private String bizType;
    private Long bizId;
    private Boolean readFlag;
    private LocalDateTime createdAt;
}
