package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_approval_instance")
public class ApprovalInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;
    private Long bizId;
    private Long templateId;
    private Integer templateVer;
    private Integer currentStep;
    /** running/approved/rejected/cancelled */
    private String status;
    private Long starterId;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
