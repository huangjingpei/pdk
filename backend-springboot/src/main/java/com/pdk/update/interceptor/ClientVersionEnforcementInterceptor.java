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

import java.util.Locale;

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
        if (version.isBlank()) return true; // 空版本头等同缺失，桥接期放行
        Business business;
        try { business=businessService.requireByAppId(appId); }
        catch (RuntimeException e) { return true; } // 业务不存在即无策略，交给后续鉴权处理
        ClientUpdatePolicy policy=policyMapper.selectOne(new LambdaQueryWrapper<ClientUpdatePolicy>()
                .eq(ClientUpdatePolicy::getBizId,business.getId()).eq(ClientUpdatePolicy::getChannel,"STABLE")
                .eq(ClientUpdatePolicy::getPlatform,platform.toUpperCase(Locale.ROOT)).eq(ClientUpdatePolicy::getArch,arch.toUpperCase(Locale.ROOT)).last("LIMIT 1"));
        if (policy==null || policy.getUpdateEnabled()!=1 || policy.getServerEnforcementEnabled()!=1 || policy.getMinimumSupportedVersion()==null) return true;
        SemanticVersion current, minimum;
        try { current=SemanticVersion.parse(version); minimum=SemanticVersion.parse(policy.getMinimumSupportedVersion()); }
        catch (RuntimeException e) {
            // 版本号无法解析时无法证明其满足最低版本要求，按强制门禁一律拦截（fail-closed）。
            return reject(response);
        }
        if (current.compareTo(minimum)>=0) return true;
        return reject(response);
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(426); response.setCharacterEncoding("UTF-8"); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), CommonResult.failed(42600, "客户端版本过低，请先完成强制更新"));
        return false;
    }
}
