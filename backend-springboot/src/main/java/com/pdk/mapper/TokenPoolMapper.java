package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.TokenPool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface TokenPoolMapper extends BaseMapper<TokenPool> {

    @Select("SELECT * FROM pdk_token_pool WHERE health_status = 'HEALTHY' AND is_discarded = 0 AND daily_calls_count < daily_max_capacity ORDER BY daily_calls_count ASC LIMIT 1 FOR UPDATE")
    TokenPool selectAvailableHealthyTokenForUpdate();

    @Update("UPDATE pdk_token_pool SET health_status = #{status}, last_fault_time = NOW() WHERE id = #{id}")
    int markTokenFaultStatus(@Param("id") Long id, @Param("status") String status);

    @Select("SELECT t.* FROM pdk_token_pool t WHERE t.health_status = 'HEALTHY' " +
            "AND t.is_discarded = 0 " +
            "AND t.daily_calls_count < t.daily_max_capacity AND NOT EXISTS " +
            "(SELECT 1 FROM pdk_account_assignment a WHERE a.token_id = t.id AND a.status = 'ACTIVE') " +
            "ORDER BY t.risk_score ASC, t.daily_calls_count ASC LIMIT #{limit} FOR UPDATE")
    List<TokenPool> selectUnassignedHealthyForUpdate(@Param("limit") int limit);

    @Insert("<script>INSERT INTO pdk_token_pool (token_val, account_alias, health_status, daily_calls_count, daily_max_capacity, risk_score, uuid, is_discarded) VALUES " +
            "<foreach collection='list' item='t' separator=','>(#{t.tokenVal}, #{t.accountAlias}, 'HEALTHY', 0, #{t.dailyMaxCapacity}, 0, #{t.uuid}, 0)</foreach></script>")
    int batchInsert(@Param("list") List<TokenPool> list);
}
