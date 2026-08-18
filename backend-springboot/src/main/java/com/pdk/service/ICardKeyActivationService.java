package com.pdk.service;

import com.pdk.domain.dto.ActivateCardDTO;
import com.pdk.domain.dto.CreateCardBatchDTO;
import com.pdk.domain.dto.TrialRegisterDTO;
import com.pdk.domain.vo.ActivationResultVO;
import java.util.List;

public interface ICardKeyActivationService {

    /**
     * 客户端卡密原子核销 (悲观锁 + CAS + 财务独立表同步入库 + 权益顺延)
     */
    ActivationResultVO activateCardKeyAtomic(ActivateCardDTO dto);

    /**
     * 新用户手机号注册并领取 1 天 20 次体验试用
     */
    ActivationResultVO registerTrialAccount(TrialRegisterDTO dto);

    /**
     * 管理员/代理商批量生成卡密
     */
    List<String> createCardKeyBatch(CreateCardBatchDTO dto, String operatorAdmin);
}
