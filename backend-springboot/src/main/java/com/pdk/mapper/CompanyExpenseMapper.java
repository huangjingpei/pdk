package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.CompanyExpense;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CompanyExpenseMapper extends BaseMapper<CompanyExpense> {
}
