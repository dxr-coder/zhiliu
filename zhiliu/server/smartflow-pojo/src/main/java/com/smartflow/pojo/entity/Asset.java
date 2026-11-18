package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sf_asset")
public class Asset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sn;
    private String name;
    private Long categoryId;
    /** stock/assigned/repair/lost/lost_pending_compensate/dispose/disposed */
    private String status;
    private Long holderId;
    private Long deptId;
    private LocalDate buyDate;
    private LocalDate warrantyEnd;
    private Integer depreciateYears;
    private String location;
    private LocalDateTime lastCheckAt;
    private LocalDateTime createdAt;
}
