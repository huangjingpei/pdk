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
                // 只有当业务处理器自身未就绪（HANDLER_UNHEALTHY 或 HANDLER_MISSING）时才认为系统核心 DOWN。
                // 外部媒体基础设施（如远端流媒体节点临时离线 MEDIA_NODE_MISSING）记录在业务指标中，不中断服务主进程健康检查与自动化部署。
                if ("ACTIVE".equals(item.getConfiguredStatus())
                        && Boolean.TRUE.equals(item.getDeploymentEnabled())
                        && ("HANDLER_UNHEALTHY".equals(item.getEffectiveStatus())
                            || "HANDLER_MISSING".equals(item.getEffectiveStatus()))) {
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
