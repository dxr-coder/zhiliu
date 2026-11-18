package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 资产流转流水（追加式）。 */
@Data
@TableName("sf_asset_flow")
public class AssetFlow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assetId;
    private String action;
    private Long fromHolder;
    private Long toHolder;
    private Long actorId;
    private String reason;
    private LocalDateTime createdAt;
}
