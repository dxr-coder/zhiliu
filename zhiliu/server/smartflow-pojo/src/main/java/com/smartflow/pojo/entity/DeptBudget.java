package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 部门月预算（占用 hold / 释放）。 */
@Data
@TableName("sf_dept_budget")
public class DeptBudget {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deptId;
    private String period;
    private BigDecimal amount;
    private BigDecimal used;
}
