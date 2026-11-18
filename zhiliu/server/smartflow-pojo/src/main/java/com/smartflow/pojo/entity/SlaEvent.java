package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** SLA 事件：首响/解决/提醒/破线。 */
@Data
@TableName("sf_sla_event")
public class SlaEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ticketId;
    private String type;
    private LocalDateTime at;
    private Long notifierId;
}
