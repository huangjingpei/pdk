package com.pdk.platform.business;

import com.pdk.domain.vo.BusinessRuntimeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Actuator 中暴露业务 DB 开关、部署 allowlist 和 Handler 的组合健康状态。 */
@Component("business")
@RequiredArgsConstructor
public class BusinessHealthIndicator implements HealthIndicator {
    private final BusinessService businessService;

    @Override
    public Health health() {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            boolean activeUnavailable = false;
            for (BusinessRuntimeVO item : businessService.listRuntime()) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("appId", item.getAppId());
                state.put("configured", item.getConfiguredStatus());
                state.put("effective", item.getEffectiveStatus());
                state.put("handler", item.getHandlerHealth());
                details.put(item.getBizCode(), state);
                if ("ACTIVE".equals(item.getConfiguredStatus())
                        && Boolean.TRUE.equals(item.getDeploymentEnabled())
                        && !"AVAILABLE".equals(item.getEffectiveStatus())) {
                    activeUnavailable = true;
                }
            }
            Health.Builder result = activeUnavailable ? Health.down() : Health.up();
            return result.withDetail("businesses", details).build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
