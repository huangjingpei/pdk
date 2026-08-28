package com.pdk.platform.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.business.spi.BusinessHandlerRegistry;
import com.pdk.config.BusinessDeploymentProperties;
import com.pdk.domain.entity.Business;
import com.pdk.mapper.BusinessMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BusinessStartupValidator implements ApplicationRunner {
    private final BusinessMapper businessMapper;
    private final BusinessHandlerRegistry handlerRegistry;
    private final BusinessDeploymentProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        List<Business> all = businessMapper.selectList(new LambdaQueryWrapper<Business>().orderByAsc(Business::getId));
        for (String code : properties.normalizedEnabledCodes()) {
            boolean defined = all.stream().anyMatch(item -> code.equalsIgnoreCase(item.getBizCode()));
            if (!defined) throw new IllegalStateException("部署白名单业务未在 pdk_business 定义: " + code);
            if (!handlerRegistry.contains(code)) throw new IllegalStateException("部署白名单业务缺少 Handler: " + code);
        }
        if (properties.isFailFastActiveUnsupported()) {
            for (Business business : all) {
                if ("ACTIVE".equals(business.getStatus())
                        && (!properties.normalizedEnabledCodes().contains(business.getBizCode().toUpperCase())
                        || !handlerRegistry.contains(business.getBizCode()))) {
                    throw new IllegalStateException("ACTIVE 业务在当前部署不可用: " + business.getBizCode());
                }
            }
        }
    }
}
