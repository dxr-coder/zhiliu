package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 报销明细（发票号全局唯一）。 */
@Data
@TableName("sf_expense_item")
public class ExpenseItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long expenseId;
    private String name;
    private BigDecimal amount;
    private String invoiceNo;
    private LocalDate expenseDate;
}
