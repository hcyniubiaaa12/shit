package com.mall.pay.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.pay.entity.PayInfo;
import com.mall.pay.mapper.PayInfoMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 关单补偿定时任务的调度逻辑：挑哪些单、逐笔交给谁、单笔失败怎么办。
 *
 * <p>单笔对账的渠道行为（关单 / 退款 / 金额核对）在 {@code PayServiceTest} 里覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayCloseSweeperTest {

    @Mock private PayInfoMapper payInfoMapper;
    @Mock private PayService payService;

    @InjectMocks
    private PayCloseSweeper sweeper;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PayInfo.class);
    }

    @Test
    void sweep_noCandidates_doesNothing() {
        when(payInfoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        sweeper.sweep();
        verify(payService, never()).reconcileClosedPay(any());
    }

    @Test
    void sweep_reconcilesEachCandidate() {
        PayInfo a = closedPay("P001");
        PayInfo b = closedPay("P002");
        when(payInfoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));

        sweeper.sweep();

        verify(payService).reconcileClosedPay(a);
        verify(payService).reconcileClosedPay(b);
    }

    /** 已对账过的单不重复处理（标记在 Redis 里，SQL 过滤不到，必须在内存里跳过） */
    @Test
    void sweep_skipsAlreadyReconciled() {
        PayInfo a = closedPay("P001");
        PayInfo b = closedPay("P002");
        when(payInfoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));
        when(payService.isReconciled("P001")).thenReturn(true);

        sweeper.sweep();

        verify(payService, never()).reconcileClosedPay(a);
        verify(payService).reconcileClosedPay(b);
    }

    /** 单笔渠道异常不能中断整轮：后面的支付单还要继续对账 */
    @Test
    void sweep_singleFailure_doesNotStopOthers() {
        PayInfo a = closedPay("P001");
        PayInfo b = closedPay("P002");
        when(payInfoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));
        when(payService.reconcileClosedPay(a)).thenThrow(new RuntimeException("渠道超时"));

        sweeper.sweep();

        verify(payService).reconcileClosedPay(b);
    }

    private PayInfo closedPay(String payNo) {
        PayInfo pay = new PayInfo();
        pay.setPayNo(payNo);
        pay.setOrderNo("ORD_" + payNo);
        pay.setOutTradeNo(payNo);
        pay.setStatus(3);
        pay.setAmount(new BigDecimal("39.00"));
        return pay;
    }
}
