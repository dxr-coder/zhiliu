package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单事件（追加式轨迹）。 */
@Data
@TableName("sf_ticket_event")
public class TicketEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ticketId;
    private String fromStatus;
    private String toStatus;
    private Long actorId;
    private String reason;
    private LocalDateTime createdAt;
}
