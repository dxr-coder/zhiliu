package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sf_leave_type")
public class LeaveType {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private Boolean needBalance;
    private Integer certDays;
    private String chainPolicy;
    private String status;
}
