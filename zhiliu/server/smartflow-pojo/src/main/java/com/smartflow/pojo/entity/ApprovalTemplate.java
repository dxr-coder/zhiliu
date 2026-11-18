package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sf_approval_template")
public class ApprovalTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;
    private Integer version;
    private String stepsJson;
    private String rejectPolicy;
    private String status;
}
