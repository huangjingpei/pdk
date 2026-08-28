package com.pdk.business.zhibo;

import com.pdk.business.spi.BusinessHandlerRegistry;
import com.pdk.common.exception.BusinessException;
import com.pdk.config.BusinessDeploymentProperties;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ZhiboBusinessHandlerTest {
    @Test
    void aggregateHandlerRegistersBothConcreteBusinessCodes() {
        ZhiboBusinessHandler handler = new ZhiboBusinessHandler();
        BusinessHandlerRegistry registry = new BusinessHandlerRegistry(List.of(handler));

        assertSame(handler, registry.require("ZHIBO_AI"));
        assertSame(handler, registry.require("zhibo_live"));
        assertFalse(registry.contains("ZHIBO"), "聚合名不是数据库业务编码");
    }

    @Test
    void aggregateDeploymentAliasExpandsToBothBusinesses() {
        BusinessDeploymentProperties properties = new BusinessDeploymentProperties();
        properties.setEnabledCodes("PDD, ZHIBO");
        assertEquals(Set.of("PDD", "ZHIBO_AI", "ZHIBO_LIVE"), properties.normalizedEnabledCodes());
    }

    @Test
    void validatesLiveActionsAtHandlerBoundary() {
        ZhiboBusinessHandler handler = new ZhiboBusinessHandler();
        AcquireTokenRequestDTO accepted = new AcquireTokenRequestDTO();
        accepted.setActionType("AI_GENERATE");
        assertDoesNotThrow(() -> handler.validateAcquire(accepted));

        AcquireTokenRequestDTO rejected = new AcquireTokenRequestDTO();
        rejected.setActionType("GOODS_COLLECT");
        assertEquals(40001, assertThrows(BusinessException.class,
                () -> handler.validateAcquire(rejected)).getCode());
    }
}
