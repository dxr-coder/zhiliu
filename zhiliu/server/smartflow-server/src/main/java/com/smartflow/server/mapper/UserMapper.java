package com.smartflow.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.pojo.entity.User;
import org.apache.ibatis.annotations.Mapper;

/** 用户 Mapper。 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
