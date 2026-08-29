package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.DeviceLicense;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceLicenseMapper extends BaseMapper<DeviceLicense> {
    @Select("SELECT * FROM pdk_device_license WHERE id = #{id} FOR UPDATE")
    DeviceLicense selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM pdk_device_license WHERE card_key_id = #{cardKeyId} FOR UPDATE")
    DeviceLicense selectByCardForUpdate(@Param("cardKeyId") Long cardKeyId);
}
