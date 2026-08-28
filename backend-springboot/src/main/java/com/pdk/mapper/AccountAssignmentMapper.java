package com.pdk.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.AccountAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper public interface AccountAssignmentMapper extends BaseMapper<AccountAssignment> {
    @Select("SELECT a.* FROM pdk_account_assignment a JOIN pdk_token_pool t ON t.id = a.token_id " +
            "WHERE a.biz_id = #{bizId} AND a.user_id = #{userId} AND a.status = 'ACTIVE' AND a.expire_at > NOW() " +
            "AND a.used_calls < a.allocated_calls AND t.biz_id = #{bizId} AND t.health_status = 'HEALTHY' " +
            "ORDER BY a.used_calls ASC, a.slot_index ASC LIMIT 1 FOR UPDATE")
    AccountAssignment selectNextUsableForUpdate(@Param("bizId") Long bizId, @Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(allocated_calls - used_calls), 0) FROM pdk_account_assignment " +
            "WHERE biz_id = #{bizId} AND user_id = #{userId} AND status = 'ACTIVE'")
    Integer selectSumRemaining(@Param("bizId") Long bizId, @Param("userId") Long userId);
}
