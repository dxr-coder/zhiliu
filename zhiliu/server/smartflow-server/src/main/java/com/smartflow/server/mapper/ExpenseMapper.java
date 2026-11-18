package com.smartflow.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.pojo.entity.Expense;
import org.apache.ibatis.annotations.Mapper;

/** Expense Mapper。 */
@Mapper
public interface ExpenseMapper extends BaseMapper<Expense> {
}
