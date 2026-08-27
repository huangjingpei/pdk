package com.pdk.business.spi;

import com.pdk.business.pdd.PddBusinessHandler;
import com.pdk.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessHandlerRegistryTest {
    private PddBusinessHandler pdd() {
        return new PddBusinessHandler(new com.pdk.business.pdd.PddActionValidator(),
                new com.pdk.business.pdd.PddCredentialCodec(),
                new com.pdk.business.pdd.PddFailureClassifier());
    }

    @Test
    void resolvesCodesCaseInsensitivelyAndRejectsMissingHandler() {
        BusinessHandlerRegistry registry = new BusinessHandlerRegistry(List.of(pdd()));
        assertInstanceOf(PddBusinessHandler.class, registry.require("pdd"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> registry.require("ZHIBO_AI"));
        assertEquals(50350, error.getCode());
    }

    @Test
    void duplicateBizCodesFailAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new BusinessHandlerRegistry(List.of(pdd(), pdd())));
    }
}
