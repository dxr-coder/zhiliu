package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sf_leave")
public class LeaveRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String leaveNo;
    private Long userId;
    private Long deptId;
    /** annual/sick/personal/comp */
    private String type;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal days;
    private String reason;
    private BigDecimal balanceBefore;
    private BigDecimal balanceHold;
    private BigDecimal balanceAfter;
    /** draft/submitted/pending_manager/pending_hr/approved/rejected/cancelled/effective/done/cancelled_after_approve */
    private String status;
    /** 提交时审批链 JSON 快照 */
    private String chainSnapshot;
    private Long approvalInstanceId;
    private Boolean aiDraft;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
