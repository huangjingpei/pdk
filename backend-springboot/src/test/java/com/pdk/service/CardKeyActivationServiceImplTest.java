package com.pdk.service;

import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ActivateCardDTO;
import com.pdk.domain.entity.*;
import com.pdk.domain.vo.ActivationResultVO;
import com.pdk.mapper.*;
import com.pdk.service.impl.CardKeyActivationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CardKeyActivationServiceImplTest {

    @Mock
    private CardKeyMapper cardKeyMapper;
    @Mock
    private FinancialIncomeMapper financialIncomeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PackageTemplateMapper packageTemplateMapper;
    @Mock
    private AdminAuditLogMapper auditLogMapper;

    @InjectMocks
    private CardKeyActivationServiceImpl activationService;

    private ActivateCardDTO validDTO;
    private CardKey unusedCard;
    private User activeUser;
    private PackageTemplate standardPkg;

    @BeforeEach
    void setUp() {
        validDTO = new ActivateCardDTO();
        validDTO.setCardKey("PDK-8891-2041-9982");
        validDTO.setUserPhone("13800138000");
        validDTO.setDeviceId("MAC-00-1B-44-11-3A-B7");
        validDTO.setActualAmount(new BigDecimal("200.00"));
        validDTO.setOrderType("NORMAL_SALE");
        validDTO.setPaymentChannel("ALIPAY");

        unusedCard = new CardKey();
        unusedCard.setId(101L);
        unusedCard.setCardKey("PDK-8891-2041-9982");
        unusedCard.setPackageId(2);
        unusedCard.setStatus("UNUSED");
        unusedCard.setGeneratedByAdmin("agent_01");

        activeUser = new User();
        activeUser.setId(501L);
        activeUser.setPhone("13800138000");
        activeUser.setStatus("ACTIVE");
        activeUser.setRemainingCalls(20);
        activeUser.setExpireTime(LocalDateTime.now().plusDays(1));

        standardPkg = new PackageTemplate();
        standardPkg.setId(2);
        standardPkg.setName("200元月卡多账号防控版");
        standardPkg.setPrice(new BigDecimal("200.00"));
        standardPkg.setDurationDays(30);
        standardPkg.setAccountCountX(10);
        standardPkg.setCallsPerAccountY(30);
    }

    @Test
    @DisplayName("UT-01: 正常卡密原子核销 - 独立财务表必须入库且配额顺延")
    void testActivateCardKey_Success() {
        when(cardKeyMapper.selectOneForUpdate("PDK-8891-2041-9982")).thenReturn(unusedCard);
        when(userMapper.selectOne(any())).thenReturn(activeUser);
        when(packageTemplateMapper.selectById(2)).thenReturn(standardPkg);
        when(cardKeyMapper.update(any(), any())).thenReturn(1); // CAS 成功

        ActivationResultVO result = activationService.activateCardKeyAtomic(validDTO);

        assertNotNull(result);
        assertEquals("200元月卡多账号防控版", result.getPackageName());
        assertEquals(30, result.getExtendedDays());
        assertEquals(300, result.getTotalAddedCalls());

        // 验证动作二：向财务独立表插入 1 条记账流水
        verify(financialIncomeMapper, times(1)).insert(any(FinancialIncome.class));
        // 验证动作三：更新用户配额与到期日
        verify(userMapper, times(1)).updateById(any(User.class));
        // 验证动作四：写入永久审计日志
        verify(auditLogMapper, times(1)).insert(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("UT-02: 重复核销已被使用的卡密 - 必须抛出 BusinessException 并中断事务")
    void testActivateAlreadyUsedCard_ThrowsException() {
        unusedCard.setStatus("ACTIVATED");
        unusedCard.setActivatedAt(LocalDateTime.now().minusDays(1));
        when(cardKeyMapper.selectOneForUpdate("PDK-8891-2041-9982")).thenReturn(unusedCard);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            activationService.activateCardKeyAtomic(validDTO);
        });

        assertEquals(40002, ex.getCode());
        assertTrue(ex.getMessage().contains("已被核销使用"));
        verify(financialIncomeMapper, never()).insert(any(FinancialIncome.class));
    }
}
