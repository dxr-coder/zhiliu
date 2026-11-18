package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sf_expense_policy")
public class ExpensePolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;
    private BigDecimal limitPerDay;
    private Boolean needInvoice;
    private String status;
    private LocalDateTime updatedAt;
}
