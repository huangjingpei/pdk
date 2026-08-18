package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.TokenPool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TokenPoolMapper extends BaseMapper<TokenPool> {

    @Select("SELECT * FROM pdk_token_pool WHERE health_status = 'HEALTHY' AND daily_calls_count < daily_max_capacity ORDER BY daily_calls_count ASC LIMIT 1 FOR UPDATE")
    TokenPool selectAvailableHealthyTokenForUpdate();

    @Update("UPDATE pdk_token_pool SET health_status = #{status}, last_fault_time = NOW() WHERE id = #{id}")
    int markTokenFaultStatus(@Param("id") Long id, @Param("status") String status);
}
