package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sf_role")
public class Role {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleCode;
    private String name;
    private String description;
}
