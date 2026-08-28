package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.LoginLog;
import com.pdk.domain.vo.LastLoginView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {

    /**
     * 批量取每个用户最近一次成功登录的时间与 IP。
     * GROUP_CONCAT 按时间倒序拼接后取首个，等价于「最新一条的 ip_address」。
     */
    @Select("SELECT actor_id AS actorId, MAX(created_at) AS lastLoginAt, "
            + "SUBSTRING_INDEX(GROUP_CONCAT(ip_address ORDER BY created_at DESC SEPARATOR ','), ',', 1) AS lastLoginIp "
            + "FROM pdk_login_log "
            + "WHERE actor_type = 'CLIENT' AND result = 'SUCCESS' AND actor_id IN "
            + "<script><foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script> "
            + "GROUP BY actor_id")
    List<LastLoginView> lastLoginBatch(@Param("ids") List<Long> ids);
}
