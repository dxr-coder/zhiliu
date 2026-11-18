package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_room_booking")
public class RoomBooking {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bookingNo;
    private Long roomId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Long organizerId;
    private String attendees;
    private String title;
    /** booked/checked_in/done/cancelled/no_show/late_cancel */
    private String status;
    private LocalDateTime checkInDeadline;
    private Boolean lateCancelFlag;
    private Integer creditDelta;
    private LocalDateTime createdAt;
}
