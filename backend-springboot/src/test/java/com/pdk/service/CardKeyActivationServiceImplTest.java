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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.pdk.platform.business.BusinessContext;

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
    private PackagePlanMapper packagePlanMapper;
    @Mock
    private PdkAdminAuditLogMapper auditLogMapper;
    @Mock
    private AccountAssignmentService assignmentService;

    @InjectMocks
    private CardKeyActivationServiceImpl activationService;

    private ActivateCardDTO validDTO;
    private CardKey unusedCard;
    private User activeUser;
    private PackagePlan standardPkg;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CardKey.class);
        validDTO = new ActivateCardDTO();
        validDTO.setCardKey("PDK-8891-2041-9982");
        validDTO.setUserPhone("13800138000");
        validDTO.setDeviceId("MAC-00-1B-44-11-3A-B7");
        validDTO.setActualAmount(new BigDecimal("200.00"));
        validDTO.setOrderType("NORMAL_SALE");
        validDTO.setPaymentChannel("ALIPAY");

        unusedCard = new CardKey();
        unusedCard.setId(101L);
        unusedCard.setBizId(1L);
        unusedCard.setCardKey("PDK-8891-2041-9982");
        unusedCard.setPackageId(2);
        unusedCard.setStatus("UNUSED");
        unusedCard.setGeneratedByAdmin("agent_01");

        activeUser = new User();
        activeUser.setId(501L);
        activeUser.setBizId(1L);
        activeUser.setPhone("13800138000");
        activeUser.setStatus("ACTIVE");
        activeUser.setRemainingCalls(20);
        activeUser.setExpireTime(LocalDateTime.now().plusDays(1));

        standardPkg = new PackagePlan();
        standardPkg.setId(2);
        standardPkg.setBizId(1L);
        standardPkg.setName("200元月卡多账号防控版");
        standardPkg.setListPrice(new BigDecimal("200.00"));
        standardPkg.setSalePrice(new BigDecimal("200.00"));
        standardPkg.setDurationHours(720);
        standardPkg.setAccountCount(10);
        standardPkg.setCallsPerAccount(30);
        standardPkg.setStatus("ACTIVE");
    }

    @Test
    @DisplayName("UT-01: 正常卡密原子核销 - 独立财务表必须入库且配额顺延")
    void testActivateCardKey_Success() {
        when(cardKeyMapper.selectOneForUpdate(1L, "PDK-8891-2041-9982")).thenReturn(unusedCard);
        when(userMapper.selectOne(any())).thenReturn(activeUser);
        when(packagePlanMapper.selectById(2)).thenReturn(standardPkg);
        when(cardKeyMapper.update(any(), any())).thenReturn(1); // CAS 成功

        ActivationResultVO result = activationService.activateCardKeyAtomic(validDTO, context());

        assertNotNull(result);
        assertEquals("200元月卡多账号防控版", result.getPackageName());
        assertEquals(30, result.getExtendedDays());
        assertEquals(300, result.getTotalAddedCalls());

        // 验证动作二：向财务独立表插入 1 条记账流水
        verify(financialIncomeMapper, times(1)).insert(any(FinancialIncome.class));
        // 验证动作三：更新用户配额与到期日
        verify(userMapper, times(1)).updateById(any(User.class));
        // 验证动作四：写入永久审计日志
        verify(auditLogMapper, times(1)).insert(any(PdkAdminAuditLog.class));
        verify(assignmentService).activatePaid(activeUser, standardPkg, unusedCard.getId());
    }

    @Test
    @DisplayName("UT-02: 重复核销已被使用的卡密 - 必须抛出 BusinessException 并中断事务")
    void testActivateAlreadyUsedCard_ThrowsException() {
        unusedCard.setStatus("ACTIVATED");
        unusedCard.setActivatedAt(LocalDateTime.now().minusDays(1));
        when(cardKeyMapper.selectOneForUpdate(1L, "PDK-8891-2041-9982")).thenReturn(unusedCard);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            activationService.activateCardKeyAtomic(validDTO, context());
        });

        assertEquals(40002, ex.getCode());
        assertTrue(ex.getMessage().contains("已被核销使用"));
        verify(financialIncomeMapper, never()).insert(any(FinancialIncome.class));
    }

    @Test
    @DisplayName("UT-03: 客户端不能篡改卡密面值")
    void rejectsTamperedAmount() {
        validDTO.setActualAmount(new BigDecimal("1.00"));
        when(cardKeyMapper.selectOneForUpdate(1L, validDTO.getCardKey())).thenReturn(unusedCard);
        when(packagePlanMapper.selectById(2)).thenReturn(standardPkg);

        BusinessException error = assertThrows(BusinessException.class,
                () -> activationService.activateCardKeyAtomic(validDTO, context()));
        assertEquals(40005, error.getCode());
        verify(userMapper, never()).insert(any(User.class));
        verify(financialIncomeMapper, never()).insert(any(FinancialIncome.class));
    }

    @Test
    @DisplayName("UT-04: 已绑定其他电脑时禁止直接激活覆盖")
    void rejectsActivationFromDifferentDevice() {
        activeUser.setDeviceId("DEVICE-OLD");
        when(cardKeyMapper.selectOneForUpdate(1L, validDTO.getCardKey())).thenReturn(unusedCard);
        when(packagePlanMapper.selectById(2)).thenReturn(standardPkg);
        when(userMapper.selectOne(any())).thenReturn(activeUser);

        BusinessException error = assertThrows(BusinessException.class,
                () -> activationService.activateCardKeyAtomic(validDTO, context()));
        assertEquals(40103, error.getCode());
        verify(financialIncomeMapper, never()).insert(any(FinancialIncome.class));
    }

    @Test
    @DisplayName("UT-05: 停用套餐对应卡密不能激活")
    void rejectsInactivePackage() {
        standardPkg.setStatus("INACTIVE");
        when(cardKeyMapper.selectOneForUpdate(1L, validDTO.getCardKey())).thenReturn(unusedCard);
        when(packagePlanMapper.selectById(2)).thenReturn(standardPkg);

        assertEquals(40007, assertThrows(BusinessException.class,
                () -> activationService.activateCardKeyAtomic(validDTO, context())).getCode());
    }

    private BusinessContext context() {
        return new BusinessContext(1, 1, "PDD", "拼多多", "desc", "SELF_SERVICE",
                true, 24, 1, 20, false);
    }
}
