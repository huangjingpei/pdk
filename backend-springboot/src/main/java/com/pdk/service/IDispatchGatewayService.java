package com.pdk.service;

import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.domain.entity.User;
import com.pdk.platform.business.BusinessContext;

public interface IDispatchGatewayService {

    /**
     * 客户端申请短效租借 Token (AES-128-GCM 加密下发)
     */
    EncryptedTokenPayloadVO acquireEncryptedToken(AcquireTokenRequestDTO dto, BusinessContext business,
                                                   User user, String deviceId);

    /**
     * 客户端异步上报业务执行结果 (扣除配额 / 故障免责拉黑自愈)
     */
    void reportAndDeductQuota(ReportResultDTO dto, BusinessContext business, User user);
}
