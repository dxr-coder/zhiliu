package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sf_expense")
public class Expense {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String expenseNo;
    private Long userId;
    private Long deptId;
    private String type;
    private BigDecimal amountTotal;
    private String currency;
    private String costCenter;
    private String budgetPeriod;
    private BigDecimal budgetHold;
    private String status;
    private String policyFlags;
    private String idempotencyKey;
    private Long approvalInstanceId;
    private Boolean aiGenerated;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
