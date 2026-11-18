package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 工单（B2 十二态子集先上主路径）。 */
@Data
@TableName("sf_ticket")
public class Ticket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private String title;
    private String category;
    private String subCategory;
    private String priority;
    private String status;
    private Long reporterId;
    private Long assigneeId;
    private Long groupId;
    private Long deptId;
    private String source;
    private BigDecimal aiConfidence;
    private LocalDateTime firstResponseAt;
    private LocalDateTime slaDeadline;
    private String resolution;
    private Long mergeTargetId;
    private Integer reopenCount;
    private Integer csatScore;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
