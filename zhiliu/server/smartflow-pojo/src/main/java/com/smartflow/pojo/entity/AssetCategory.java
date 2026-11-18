package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_asset_category")
public class AssetCategory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private Integer depreciateYears;
    private Integer warrantyDefault;
    private String status;
    private LocalDateTime createdAt;
}
