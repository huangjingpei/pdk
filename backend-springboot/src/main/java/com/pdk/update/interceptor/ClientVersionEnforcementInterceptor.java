package com.pdk.update.interceptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.Business;
import com.pdk.platform.business.BusinessService;
import com.pdk.update.domain.ClientUpdatePolicy;
import com.pdk.update.mapper.ClientUpdatePolicyMapper;
import com.pdk.update.service.SemanticVersion;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 第二道强制更新门禁。桥接期对缺少版本 Header 的旧客户端保持兼容。 */
@Component
@RequiredArgsConstructor
public class ClientVersionEnforcementInterceptor implements HandlerInterceptor {
    private final BusinessService businessService;
    private final ClientUpdatePolicyMapper policyMapper;
    private final ObjectMapper objectMapper;

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String appRaw=request.getHeader("X-PDK-App-ID"), version=request.getHeader("X-PDK-Client-Version");
        String platform=request.getHeader("X-PDK-Platform"), arch=request.getHeader("X-PDK-Arch");
        if (appRaw==null || version==null || platform==null || arch==null) return true;
        long appId;
        try { appId=Long.parseLong(appRaw); } catch(NumberFormatException e) { return true; }
        Business business=businessService.requireByAppId(appId);
        ClientUpdatePolicy policy=policyMapper.selectOne(new LambdaQueryWrapper<ClientUpdatePolicy>()
                .eq(ClientUpdatePolicy::getBizId,business.getId()).eq(ClientUpdatePolicy::getChannel,"STABLE")
                .eq(ClientUpdatePolicy::getPlatform,platform.toUpperCase()).eq(ClientUpdatePolicy::getArch,arch.toUpperCase()).last("LIMIT 1"));
        if (policy==null || policy.getUpdateEnabled()!=1 || policy.getServerEnforcementEnabled()!=1 || policy.getMinimumSupportedVersion()==null) return true;
        if (SemanticVersion.parse(version).compareTo(SemanticVersion.parse(policy.getMinimumSupportedVersion()))>=0) return true;
        response.setStatus(426); response.setCharacterEncoding("UTF-8"); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), CommonResult.failed(42600, "客户端版本过低，请先完成强制更新"));
        return false;
    }
}
