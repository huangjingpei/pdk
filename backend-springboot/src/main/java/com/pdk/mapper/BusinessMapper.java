package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.Business;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessMapper extends BaseMapper<Business> {
}
