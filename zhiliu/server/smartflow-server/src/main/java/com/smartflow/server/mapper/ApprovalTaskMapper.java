package com.smartflow.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.pojo.entity.ApprovalTask;
import org.apache.ibatis.annotations.Mapper;

/** 审批任务 Mapper。 */
@Mapper
public interface ApprovalTaskMapper extends BaseMapper<ApprovalTask> {
}
