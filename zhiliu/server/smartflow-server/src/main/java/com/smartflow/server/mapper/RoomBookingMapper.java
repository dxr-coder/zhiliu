package com.smartflow.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.pojo.entity.RoomBooking;
import org.apache.ibatis.annotations.Mapper;

/** 会议室预订 Mapper。 */
@Mapper
public interface RoomBookingMapper extends BaseMapper<RoomBooking> {
}
