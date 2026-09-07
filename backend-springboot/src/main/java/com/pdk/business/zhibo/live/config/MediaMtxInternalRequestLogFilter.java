package com.pdk.business.zhibo.live.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * MediaMTX 内部接口请求追踪过滤器。
 * <p>
 * 目的：MediaMTX 事件钩子（available/unavailable/read/unread）由外部进程回调，
 * 出问题时（例如 Windows 钩子环境变量未展开、URL 被截断、参数缺失）后端只能看到
 * Spring 绑定后的异常，看不到实际到达的原始请求。本过滤器把每个打到
 * /api/v1/internal/mediamtx/** 的请求的完整请求行（方法、URI、查询串、来源 IP）
 * 记录下来，serviceToken 值脱敏，便于从后端侧直接定位钩子发来的到底是什么。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MediaMtxInternalRequestLogFilter implements Filter {

    private static final String PREFIX = "/api/v1/internal/mediamtx/";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest req && req.getRequestURI().startsWith(PREFIX)) {
            log.info("[mtx-trace] <== {} {} from {}:{} query=[{}]",
                    req.getMethod(),
                    req.getRequestURI(),
                    req.getRemoteAddr(),
                    req.getRemotePort(),
                    maskToken(req.getQueryString()));
        }
        chain.doFilter(request, response);
    }

    private String maskToken(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return "(no query string)";
        }
        return queryString.replaceAll("serviceToken=[^&]*", "serviceToken=***");
    }
}
