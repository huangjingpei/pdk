package com.pdk.platform.business;

import com.pdk.common.exception.BusinessException;
import com.pdk.config.BusinessDeploymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessRequestResolverTest {
    private BusinessService businessService;
    private BusinessDeploymentProperties properties;
    private BusinessRequestResolver resolver;

    @BeforeEach
    void setUp() {
        businessService = mock(BusinessService.class);
        properties = new BusinessDeploymentProperties();
        resolver = new BusinessRequestResolver(businessService, properties);
        when(businessService.requireAvailableByAppId(1L)).thenReturn(context(1, 1, "PDD"));
        when(businessService.requireAvailableByAppId(2L)).thenReturn(context(2, 2, "ZHIBO_AI"));
    }

    @Test
    void missingAppIdFallsBackToConfiguredLegacyBusiness() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(1L, resolver.resolveAndBind(request, null));
        assertEquals(1L, request.getAttribute(BusinessRequestResolver.ATTR_BIZ_ID));
        assertEquals("PDD", request.getAttribute(BusinessRequestResolver.ATTR_BIZ_CODE));
    }

    @Test
    void matchingHeaderAndBodyBindRealBusinessContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BusinessRequestResolver.APP_ID_HEADER, "2");
        assertEquals(2L, resolver.resolveAndBind(request, 2L));
        assertEquals(2L, request.getAttribute(BusinessRequestResolver.ATTR_BIZ_ID));
        assertEquals("ZHIBO_AI", request.getAttribute(BusinessRequestResolver.ATTR_BIZ_CODE));
    }

    @Test
    void headerAndBodyMismatchIsRejected() {
        assertEquals(40050, assertThrows(BusinessException.class, () -> resolver.resolve(1L, 2L)).getCode());
    }

    @Test
    void malformedHeaderIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BusinessRequestResolver.APP_ID_HEADER, "PDD");
        assertEquals(40050, assertThrows(BusinessException.class,
                () -> resolver.resolveAndBind(request, null)).getCode());
    }

    @Test
    void missingAppIdCanBeStrictlyDisabled() {
        properties.setAllowLegacyMissingAppId(false);
        assertEquals(40050, assertThrows(BusinessException.class,
                () -> resolver.resolve(null, null)).getCode());
    }

    private BusinessContext context(long bizId, long appId, String code) {
        return new BusinessContext(bizId, appId, code, code, "description", "SELF_SERVICE",
                true, 24, 1, 20, false);
    }
}
