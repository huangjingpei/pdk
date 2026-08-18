package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.PdkAdminAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PdkAdminAuditLogMapper extends BaseMapper<PdkAdminAuditLog> {
}
