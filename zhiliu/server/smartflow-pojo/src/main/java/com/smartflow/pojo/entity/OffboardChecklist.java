package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_offboard_checklist")
public class OffboardChecklist {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** open/done */
    private String status;
    private Boolean assetsReturned;
    private Boolean accountsDisabled;
    private Boolean permissionsRevoked;
    private Boolean badgeReturned;
    private Boolean loanCleared;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
