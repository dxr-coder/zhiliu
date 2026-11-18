package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** SLA 策略（四级可配）。 */
@Data
@TableName("sf_sla_policy")
public class SlaPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String priority;
    private Integer firstRespMin;
    private Integer resolveMin;
    private String escalateRole;
    private String status;
}
