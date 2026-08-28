package com.pdk.business.spi;

import com.pdk.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BusinessHandlerRegistry {
    private final Map<String, BusinessHandler> handlers;

    public BusinessHandlerRegistry(List<BusinessHandler> discoveredHandlers) {
        Map<String, BusinessHandler> registered = new LinkedHashMap<>();
        for (BusinessHandler handler : discoveredHandlers) {
            if (handler.supportedBizCodes() == null || handler.supportedBizCodes().isEmpty()) {
                throw new IllegalStateException("BusinessHandler.supportedBizCodes 不能为空: " + handler.getClass().getName());
            }
            for (String declaredCode : handler.supportedBizCodes()) {
                String code = normalize(declaredCode);
                if (code.isBlank()) {
                    throw new IllegalStateException("BusinessHandler.bizCode 不能为空: " + handler.getClass().getName());
                }
                BusinessHandler previous = registered.putIfAbsent(code, handler);
                if (previous != null) {
                    throw new IllegalStateException("重复的业务 Handler: " + code + " -> "
                            + previous.getClass().getName() + ", " + handler.getClass().getName());
                }
            }
        }
        this.handlers = Collections.unmodifiableMap(registered);
    }

    public BusinessHandler require(String bizCode) {
        BusinessHandler handler = handlers.get(normalize(bizCode));
        if (handler == null) {
            throw new BusinessException(50350, "当前部署不支持业务: " + bizCode);
        }
        return handler;
    }

    public boolean contains(String bizCode) {
        return handlers.containsKey(normalize(bizCode));
    }

    public Map<String, BusinessHandler> snapshot() {
        return handlers;
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
