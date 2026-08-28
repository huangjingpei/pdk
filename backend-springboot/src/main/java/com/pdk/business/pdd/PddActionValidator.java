package com.pdk.business.pdd;

import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PddActionValidator {
    private static final Set<String> ACTIONS = Set.of("GOODS_COLLECT", "ORDER_PULL", "DETAIL_QUERY");

    public Set<String> supportedActions() {
        return ACTIONS;
    }

    public void validate(AcquireTokenRequestDTO request) {
        if (request == null || request.getActionType() == null || !ACTIONS.contains(request.getActionType())) {
            throw new BusinessException(40001, "业务动作类型不合法");
        }
    }
}
