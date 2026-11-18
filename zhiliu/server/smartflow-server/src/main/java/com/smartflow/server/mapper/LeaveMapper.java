package com.smartflow.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.pojo.entity.LeaveRequest;
import org.apache.ibatis.annotations.Mapper;

/** 请假 Mapper。 */
@Mapper
public interface LeaveMapper extends BaseMapper<LeaveRequest> {
}
