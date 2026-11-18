package com.smartflow.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 会议室主数据。 */
@Data
@TableName("sf_meeting_room")
public class MeetingRoom {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private Integer capacity;
    private String equip;
    private String location;
    private String status;
}
