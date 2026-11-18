package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_approval_task")
public class ApprovalTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long instanceId;
    private Integer step;
    private Long approverId;
    /** pending/agree/reject/transfer/add_sign/skip */
    private String action;
    private String comment;
    private LocalDateTime actedAt;
}
