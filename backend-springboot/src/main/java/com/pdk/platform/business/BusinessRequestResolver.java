package com.pdk.platform.business;

import com.pdk.common.exception.BusinessException;
import com.pdk.config.BusinessDeploymentProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 客户端公开 appId 的统一入口。
 *
 * 当前兼容阶段只部署 appId=1/PDD；请求未携带 appId 时仍回落到 1。
 * 后续接入 pdk_business 后，由该类负责查询 appId -> BusinessContext，调用方无需改变 URL。
 */
@Component
@RequiredArgsConstructor
public class BusinessRequestResolver {
    public static final String APP_ID_HEADER = "X-PDK-App-ID";
    public static final String ATTR_APP_ID = "pdkAppId";
    public static final String ATTR_BIZ_ID = "pdkBizId";
    public static final String ATTR_BIZ_CODE = "pdkBizCode";
    public static final String ATTR_BUSINESS_CONTEXT = "pdkBusinessContext";
    public static final long LEGACY_PDD_APP_ID = 1L;
    public static final String LEGACY_PDD_BIZ_CODE = "PDD";

    private final BusinessService businessService;
    private final BusinessDeploymentProperties deploymentProperties;

    public long resolveAndBind(HttpServletRequest request, Long bodyAppId) {
        return resolveContextAndBind(request, bodyAppId).appId();
    }

    public BusinessContext resolveContextAndBind(HttpServletRequest request, Long bodyAppId) {
        Long headerAppId = parseHeader(request == null ? null : request.getHeader(APP_ID_HEADER));
        long resolved = resolve(bodyAppId, headerAppId);
        BusinessContext context = businessService.requireAvailableByAppId(resolved);
        if (request != null) {
            request.setAttribute(ATTR_APP_ID, context.appId());
            request.setAttribute(ATTR_BIZ_ID, context.bizId());
            request.setAttribute(ATTR_BIZ_CODE, context.bizCode());
            request.setAttribute(ATTR_BUSINESS_CONTEXT, context);
        }
        return context;
    }

    public long resolve(Long bodyAppId, Long headerAppId) {
        validatePositive(bodyAppId);
        validatePositive(headerAppId);
        if (bodyAppId != null && headerAppId != null && !bodyAppId.equals(headerAppId)) {
            throw new BusinessException(40050, "请求头 X-PDK-App-ID 与请求体 appId 不一致");
        }
        if (bodyAppId != null) return bodyAppId;
        if (headerAppId != null) return headerAppId;
        if (!deploymentProperties.isAllowLegacyMissingAppId()) {
            throw new BusinessException(40050, "请求必须携带 X-PDK-App-ID 或 appId");
        }
        return deploymentProperties.getLegacyDefaultAppId();
    }

    public static BusinessContext context(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(ATTR_BUSINESS_CONTEXT);
        if (value instanceof BusinessContext context) return context;
        throw new BusinessException(40050, "请求缺少业务上下文");
    }

    private Long parseHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(40050, "X-PDK-App-ID 必须为正整数");
        }
    }

    private void validatePositive(Long appId) {
        if (appId != null && appId <= 0) {
            throw new BusinessException(40050, "appId 必须为正整数");
        }
    }
}
