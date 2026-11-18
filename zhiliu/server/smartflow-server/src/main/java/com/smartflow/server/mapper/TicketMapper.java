package com.smartflow.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.pojo.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

/** Ticket Mapper。 */
@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
