package com.pdk.config;

import com.pdk.interceptor.DeviceSecurityInterceptor;
import com.pdk.interceptor.AdminAuthInterceptor;
import com.pdk.update.interceptor.ClientVersionEnforcementInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final DeviceSecurityInterceptor deviceSecurityInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final ClientVersionEnforcementInterceptor clientVersionEnforcementInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(clientVersionEnforcementInterceptor)
                .addPathPatterns("/api/v1/client/**")
                .excludePathPatterns("/api/v1/client/updates/**", "/api/v1/client/auth/logout",
                        "/api/v1/client/config/**", "/api/v1/client/business/**");
        registry.addInterceptor(deviceSecurityInterceptor)
                .addPathPatterns("/api/v1/dispatch/**", "/api/v1/client/**")
                .excludePathPatterns("/api/v1/client/auth/login", "/api/v1/client/auth/register",
                        "/api/v1/client/auth/sms/send", "/api/v1/client/auth/change-password",
                        "/api/v1/client/auth/reset-password",
                        "/api/v1/client/updates/**",
                        "/api/v1/client/config/**", "/api/v1/client/business/**");
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/admin/**")
                .excludePathPatterns("/api/v1/admin/auth/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
