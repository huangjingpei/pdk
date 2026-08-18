package com.pdk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pdk.domain.entity.CardKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CardKeyMapper extends BaseMapper<CardKey> {

    /**
     * 悲观行锁锁定卡密记录 (SELECT ... FOR UPDATE) 杜绝高并发重复核销
     */
    @Select("SELECT * FROM pdk_card_key WHERE card_key = #{cardKey} FOR UPDATE")
    CardKey selectOneForUpdate(@Param("cardKey") String cardKey);
}
