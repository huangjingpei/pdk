package com.pdk.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.AccountAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper public interface AccountAssignmentMapper extends BaseMapper<AccountAssignment> {
    @Select("SELECT a.* FROM pdk_account_assignment a JOIN pdk_token_pool t ON t.id = a.token_id " +
            "WHERE a.user_id = #{userId} AND a.status = 'ACTIVE' AND a.expire_at > NOW() " +
            "AND a.used_calls < a.allocated_calls AND t.health_status = 'HEALTHY' " +
            "ORDER BY a.used_calls ASC, a.slot_index ASC LIMIT 1 FOR UPDATE")
    AccountAssignment selectNextUsableForUpdate(@Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(allocated_calls - used_calls), 0) FROM pdk_account_assignment " +
            "WHERE user_id = #{userId} AND status = 'ACTIVE'")
    Integer selectSumRemaining(@Param("userId") Long userId);
}
