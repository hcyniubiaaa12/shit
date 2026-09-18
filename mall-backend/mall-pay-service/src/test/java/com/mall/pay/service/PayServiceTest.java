package com.mall.pay.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.BizException;
import com.mall.pay.entity.PayInfo;
import com.mall.pay.entity.Refund;
import com.mall.pay.gateway.AlipayGatewayClient;
import com.mall.pay.gateway.AlipaySandboxClient;
import com.mall.pay.gateway.PayCreateResult;
import com.mall.pay.gateway.PayQueryResult;
import com.mall.pay.mapper.OrderPayMapper;
import com.mall.pay.mapper.PayInfoMapper;
import com.mall.pay.mapper.RefundMapper;
import com.mall.pay.vo.PaySyncResult;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayServiceTest {

    @Mock private PayInfoMapper payInfoMapper;
    @Mock private RefundMapper refundMapper;
    @Mock private OrderPayMapper orderPayMapper;
    @Mock private AlipayGatewayClient alipayGatewayClient;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;
    /** 查单节流用的 Redis bucket（SETNX + TTL） */
    @Mock private RBucket<String> queryBucket;
    /** 关单补偿对账标记用的 Redis bucket */
    @Mock private RBucket<String> reconcileBucket;
    /** 沙箱客户端是可选的（Mock 渠道下不存在），因此用 ObjectProvider 注入 */
    @Mock private ObjectProvider<AlipaySandboxClient> sandboxProvider;

    @InjectMocks
    private PayService payService;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration config = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(config, "");
        TableInfoHelper.initTableInfo(assistant, PayInfo.class);
        TableInfoHelper.initTableInfo(assistant, Refund.class);
    }

    // ----------------------------------------------------------
    // create
    // ----------------------------------------------------------

    @Test
    void create_orderNotFound_throws() {
        when(orderPayMapper.selectByOrderNo("ORD_X")).thenReturn(null);
        assertThatThrownBy(() -> payService.create(1001L, "ORD_X"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");
    }

    @Test
    void create_orderStatusNotZero_throws() {
        OrderPayMapper.OrderPayView order = new OrderPayMapper.OrderPayView();
        order.setStatus(1); order.setPayAmount(new BigDecimal("100"));
        when(orderPayMapper.selectByOrderNo("ORD001")).thenReturn(order);
        assertThatThrownBy(() -> payService.create(1001L, "ORD001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可支付");
    }

    @Test
    void create_existingPendingPayInfo_returnsExisting() {
        OrderPayMapper.OrderPayView order = new OrderPayMapper.OrderPayView();
        order.setStatus(0); order.setPayAmount(new BigDecimal("100"));
        when(orderPayMapper.selectByOrderNo("ORD001")).thenReturn(order);
        PayInfo exist = new PayInfo();
        exist.setPayNo("P_EXISTING"); exist.setStatus(0); exist.setAmount(new BigDecimal("100"));
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(exist);
        when(alipayGatewayClient.createPay(anyString(), anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(voucher("P_EXISTING", "ORD001", false));
        PayCreateResult result = payService.create(1001L, "ORD001");
        assertThat(result.payNo()).isEqualTo("P_EXISTING");
        verify(payInfoMapper, never()).insert(any());
    }

    @Test
    void create_success_insertsPayInfo() {
        OrderPayMapper.OrderPayView order = new OrderPayMapper.OrderPayView();
        order.setStatus(0); order.setPayAmount(new BigDecimal("100"));
        when(orderPayMapper.selectByOrderNo("ORD001")).thenReturn(order);
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(payInfoMapper.insert(any(PayInfo.class))).thenReturn(1);
        when(alipayGatewayClient.createPay(anyString(), anyString(), anyString(), any(BigDecimal.class)))
                .thenAnswer(inv -> voucher(inv.getArgument(0), inv.getArgument(1), true));
        PayCreateResult result = payService.create(1001L, "ORD001");
        assertThat(result.payNo()).startsWith("P");
        assertThat(result.realChannel()).isTrue();
        verify(payInfoMapper).insert(any(PayInfo.class));
        verify(alipayGatewayClient).createPay(anyString(), anyString(), anyString(), any(BigDecimal.class));
    }

    // ----------------------------------------------------------
    // 支付宝异步通知（沙箱渠道）
    // ----------------------------------------------------------

    @Test
    void handleAlipayNotify_noSandboxChannel_returnsFalse() {
        when(sandboxProvider.getIfAvailable()).thenReturn(null);
        assertThat(payService.handleAlipayNotify(Map.of("out_trade_no", "P001"))).isFalse();
    }

    @Test
    void handleAlipayNotify_badSignature_returnsFalse() {
        AlipaySandboxClient sandbox = mock(AlipaySandboxClient.class);
        when(sandboxProvider.getIfAvailable()).thenReturn(sandbox);
        when(sandbox.verifyNotify(anyMap())).thenReturn(false);
        assertThat(payService.handleAlipayNotify(notifyParams("TRADE_SUCCESS"))).isFalse();
        // 验签失败绝不能入账
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
    }

    @Test
    void handleAlipayNotify_tradeNotSuccess_answersWithoutBooking() {
        AlipaySandboxClient sandbox = mock(AlipaySandboxClient.class);
        when(sandboxProvider.getIfAvailable()).thenReturn(sandbox);
        when(sandbox.verifyNotify(anyMap())).thenReturn(true);
        // 未付款状态：应答 success 但不入账
        assertThat(payService.handleAlipayNotify(notifyParams("WAIT_BUYER_PAY"))).isTrue();
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
    }

    @Test
    void handleAlipayNotify_success_booksPayment() throws InterruptedException {
        AlipaySandboxClient sandbox = mock(AlipaySandboxClient.class);
        when(sandboxProvider.getIfAvailable()).thenReturn(sandbox);
        when(sandbox.verifyNotify(anyMap())).thenReturn(true);
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setOrderNo("ORD001");
        pay.setAmount(new BigDecimal("100"));
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(redissonClient.getLock("pay:notify:P001")).thenReturn(rLock);
        when(rLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(payInfoMapper.markSuccess(eq(1L), eq("TRADE001"), anyString())).thenReturn(1);
        when(orderPayMapper.markPaid("ORD001")).thenReturn(1);
        when(orderPayMapper.selectMerchantId("ORD001")).thenReturn(100L);
        when(orderPayMapper.selectItems("ORD001")).thenReturn(Collections.emptyList());

        assertThat(payService.handleAlipayNotify(notifyParams("TRADE_SUCCESS"))).isTrue();
        verify(orderPayMapper).markPaid("ORD001");
        verify(orderPayMapper).addMerchantBalance(eq(100L), any(BigDecimal.class));
    }

    @Test
    void handleAlipayNotify_amountMismatch_rejectsAndReturnsFalse() {
        AlipaySandboxClient sandbox = mock(AlipaySandboxClient.class);
        when(sandboxProvider.getIfAvailable()).thenReturn(sandbox);
        when(sandbox.verifyNotify(anyMap())).thenReturn(true);
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setAmount(new BigDecimal("100"));
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        Map<String, String> params = notifyParams("TRADE_SUCCESS");
        params.put("total_amount", "0.01");   // 与支付单不一致
        assertThat(payService.handleAlipayNotify(params)).isFalse();
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
    }

    // ----------------------------------------------------------
    // handleCallback
    // ----------------------------------------------------------

    @Test
    void handleCallback_payInfoNotFound_throws() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> payService.handleCallback("P001", "TRADE001", "amount=100"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("支付单不存在");
    }

    @Test
    void handleCallback_bodyNull_throws() {
        PayInfo pay = mockPayInfo(1L, 0);
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        assertThatThrownBy(() -> payService.handleCallback("P001", "TRADE001", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("回调参数缺失");
    }

    @Test
    void handleCallback_amountMismatch_throws() {
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setAmount(new BigDecimal("100"));
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        assertThatThrownBy(() -> payService.handleCallback("P001", "TRADE001", "amount=99.99"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("金额不一致");
    }

    @Test
    void handleCallback_lockFails_throws() throws InterruptedException {
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setAmount(new BigDecimal("100"));
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(redissonClient.getLock("pay:notify:P001")).thenReturn(rLock);
        when(rLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        assertThatThrownBy(() -> payService.handleCallback("P001", "TRADE001", "amount=100"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("稍后重试");
    }

    @Test
    void handleCallback_success_orderSynced() throws InterruptedException {
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setOrderNo("ORD001");
        pay.setAmount(new BigDecimal("100"));
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(redissonClient.getLock("pay:notify:P001")).thenReturn(rLock);
        when(rLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(payInfoMapper.markSuccess(1L, "TRADE001", "amount=100")).thenReturn(1);
        when(orderPayMapper.markPaid("ORD001")).thenReturn(1);
        when(orderPayMapper.selectMerchantId("ORD001")).thenReturn(100L);
        when(orderPayMapper.selectItems("ORD001")).thenReturn(Collections.emptyList());
        payService.handleCallback("P001", "TRADE001", "amount=100");
        verify(orderPayMapper).markPaid("ORD001");
        verify(orderPayMapper).addMerchantBalance(eq(100L), any(BigDecimal.class));
    }

    @Test
    void handleCallback_idempotent_noOrderUpdate() throws InterruptedException {
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setOrderNo("ORD001");
        pay.setAmount(new BigDecimal("100"));
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(redissonClient.getLock("pay:notify:P001")).thenReturn(rLock);
        when(rLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(payInfoMapper.markSuccess(1L, "TRADE001", "amount=100")).thenReturn(0);
        when(orderPayMapper.markPaid("ORD001")).thenReturn(0);
        payService.handleCallback("P001", "TRADE001", "amount=100");
        verify(orderPayMapper).markPaid("ORD001");
        verify(orderPayMapper, never()).addMerchantBalance(anyLong(), any());
    }

    // ----------------------------------------------------------
    // refund
    // ----------------------------------------------------------

    @Test
    void refund_orderNotFound_throws() {
        when(orderPayMapper.selectByOrderNo("ORD_X")).thenReturn(null);
        assertThatThrownBy(() -> payService.refund(1001L, "ORD_X", "不想要了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");
    }

    @Test
    void refund_statusInvalid_throws() {
        OrderPayMapper.OrderPayView order = mockOrderPayView(0);
        when(orderPayMapper.selectByOrderNo("ORD001")).thenReturn(order);
        assertThatThrownBy(() -> payService.refund(1001L, "ORD001", "不想要了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能申请退款");
    }

    @Test
    void refund_alreadyRefunding_throws() {
        OrderPayMapper.OrderPayView order = mockOrderPayView(1);
        when(orderPayMapper.selectByOrderNo("ORD001")).thenReturn(order);
        when(refundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> payService.refund(1001L, "ORD001", "不想要了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已发起退款");
    }

    @Test
    void refund_success_insertsRefundAndMarksOrder() {
        OrderPayMapper.OrderPayView order = mockOrderPayView(1);
        when(orderPayMapper.selectByOrderNo("ORD001")).thenReturn(order);
        when(refundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        PayInfo pay = mockPayInfo(1L, 1);
        pay.setPayNo("P001"); pay.setOrderNo("ORD001");
        pay.setAmount(new BigDecimal("100")); pay.setOutTradeNo("P001");
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(refundMapper.insert(any(Refund.class))).thenReturn(1);
        when(orderPayMapper.markRefunding("ORD001")).thenReturn(1);
        // 渠道返回交易流水号（失败时抛 BizException，不再靠字符串前缀判断）
        when(alipayGatewayClient.refundPay(eq("P001"), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn("2026091822001400000001");
        when(refundMapper.markSuccess(any())).thenReturn(1);
        when(orderPayMapper.markRefunded("ORD001")).thenReturn(1);
        when(orderPayMapper.selectMerchantId("ORD001")).thenReturn(100L);
        when(orderPayMapper.selectItems("ORD001")).thenReturn(Collections.emptyList());
        String tradeNo = payService.refund(1001L, "ORD001", "不想要了");
        assertThat(tradeNo).isEqualTo("2026091822001400000001");
        verify(orderPayMapper).markRefunding("ORD001");
        verify(orderPayMapper).markRefunded("ORD001");
        verify(orderPayMapper).deductMerchantBalance(eq(100L), any(BigDecimal.class));
    }

    @Test
    void refund_channelThrows_marksRefundFailed() {
        OrderPayMapper.OrderPayView order = mockOrderPayView(1);
        when(orderPayMapper.selectByOrderNo("ORD001")).thenReturn(order);
        when(refundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        PayInfo pay = mockPayInfo(1L, 1);
        pay.setPayNo("P001"); pay.setOrderNo("ORD001");
        pay.setAmount(new BigDecimal("100")); pay.setOutTradeNo("P001");
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(refundMapper.insert(any(Refund.class))).thenReturn(1);
        when(orderPayMapper.markRefunding("ORD001")).thenReturn(1);
        when(alipayGatewayClient.refundPay(anyString(), anyString(), any(BigDecimal.class), anyString()))
                .thenThrow(new BizException("支付宝退款失败: code=ACQ.SYSTEM_ERROR"));
        assertThatThrownBy(() -> payService.refund(1001L, "ORD001", "不想要了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("支付宝退款失败");
        // 渠道失败：订单不应置为已退款，商家余额不扣回
        verify(orderPayMapper, never()).markRefunded(anyString());
        verify(orderPayMapper, never()).deductMerchantBalance(anyLong(), any());
    }

    // ----------------------------------------------------------
    // closePay
    // ----------------------------------------------------------

    @Test
    void closePay_pendingPay_closesChannelAndMarksClosed() {
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setOrderNo("ORD001"); pay.setOutTradeNo("P001");
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(payInfoMapper.markClosed(1L)).thenReturn(1);
        payService.closePay("ORD001");
        verify(alipayGatewayClient).closePay("P001");
        verify(payInfoMapper).markClosed(1L);
    }

    @Test
    void closePay_alreadyPaid_doesNothing() {
        PayInfo pay = mockPayInfo(1L, 1);
        pay.setOrderNo("ORD001");
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        payService.closePay("ORD001");
        verify(alipayGatewayClient, never()).closePay(anyString());
        verify(payInfoMapper, never()).markClosed(any());
    }

    // ----------------------------------------------------------
    // isPaid
    // ----------------------------------------------------------

    @Test
    void isPaid_true() {
        when(payInfoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThat(payService.isPaid("ORD001")).isTrue();
    }

    @Test
    void isPaid_false() {
        when(payInfoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        assertThat(payService.isPaid("ORD001")).isFalse();
    }

    // ----------------------------------------------------------
    // 主动查单补偿（通知为主、查单为辅）
    // ----------------------------------------------------------

    @Test
    void syncPayStatus_payInfoNotFound_throws() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> payService.syncPayStatus("ORD_X"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("支付单不存在");
    }

    @Test
    void syncPayStatus_alreadyPaid_returnsTrueWithoutChannelQuery() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockPayInfo(1L, 1));
        assertThat(payService.syncPayStatus("ORD001").paid()).isTrue();
        // 已入账就不该再打扰渠道
        verify(alipayGatewayClient, never()).queryPay(anyString());
    }

    @Test
    void syncPayStatus_failedPay_returnsFalseWithoutChannelQuery() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockPayInfo(1L, 2));
        assertThat(payService.syncPayStatus("ORD001").paid()).isFalse();
        verify(alipayGatewayClient, never()).queryPay(anyString());
    }

    @Test
    void syncPayStatus_mockChannel_returnsFalseWithoutQuery() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockPayInfo(1L, 0));
        when(alipayGatewayClient.isRealChannel()).thenReturn(false);
        assertThat(payService.syncPayStatus("ORD001").paid()).isFalse();
        verify(alipayGatewayClient, never()).queryPay(anyString());
    }

    /**
     * 支付宝同步跳转（return_url）回传的 out_trade_no 是**支付单号**，不是订单号。
     * 只按订单号查会永远查不到 —— 实测踩过：付款成功却一直显示"尚未确认到账"。
     */
    @Test
    void syncPayStatus_lookupByPayNo_booksPayment() throws InterruptedException {
        PayInfo pay = pendingPay();
        // 按订单号查不到，按支付单号才有
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, pay);
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE001", "TRADE_SUCCESS", new BigDecimal("100"), true));
        givenBookingSucceeds();

        assertThat(payService.syncPayStatus("P001").paid()).isTrue();
        verify(orderPayMapper).markPaid("ORD001");
    }

    /** 主用例：渠道确认已支付 → 走与异步通知完全相同的入账链路 */
    @Test
    void syncPayStatus_channelPaid_booksPayment() throws InterruptedException {
        PayInfo pay = pendingPay();
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE001", "TRADE_SUCCESS", new BigDecimal("100"), true));
        givenBookingSucceeds();

        assertThat(payService.syncPayStatus("ORD001").paid()).isTrue();
        // 金额按渠道实付核对后入账，订单与商家余额同步
        verify(payInfoMapper).markSuccess(eq(1L), eq("TRADE001"), eq("amount=100"));
        verify(orderPayMapper).markPaid("ORD001");
        verify(orderPayMapper).addMerchantBalance(eq(100L), any(BigDecimal.class));
    }

    @Test
    void syncPayStatus_channelNotPaid_returnsFalseWithoutBooking() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingPay());
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(PayQueryResult.notPaid("P001", "WAIT_BUYER_PAY"));

        assertThat(payService.syncPayStatus("ORD001").paid()).isFalse();
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
    }

    /** 渠道说付了却没回传金额：无法核对金额，宁可不入账 */
    @Test
    void syncPayStatus_paidButNoAmount_returnsFalse() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingPay());
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE001", "TRADE_SUCCESS", null, true));

        assertThat(payService.syncPayStatus("ORD001").paid()).isFalse();
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
        verify(alipayGatewayClient, never()).refundPay(anyString(), anyString(), any(), any());
    }

    /** 渠道实付金额与支付单不一致：入账与退款都必须停手（防篡改/错账） */
    @Test
    void syncPayStatus_amountMismatch_neitherBooksNorRefunds() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingPay());
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE001", "TRADE_SUCCESS", new BigDecimal("0.01"), true));

        assertThat(payService.syncPayStatus("ORD001").paid()).isFalse();
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
        verify(alipayGatewayClient, never()).refundPay(anyString(), anyString(), any(), any());
    }

    /**
     * 悬挂款：订单已取消（pay_info 已关闭）但用户仍付款成功 → 必须原路退回，绝不能入账。
     */
    @Test
    void syncPayStatus_closedPayButChannelPaid_refundsInsteadOfBooking() {
        PayInfo pay = mockPayInfo(1L, 3);           // 本地已关闭
        pay.setOrderNo("ORD001");
        pay.setOutTradeNo("P001");
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE009", "TRADE_SUCCESS", new BigDecimal("100"), true));
        when(refundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(refundMapper.insert(any(Refund.class))).thenReturn(1);
        when(alipayGatewayClient.refundPay(eq("P001"), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn("TRADE_REFUND_1");

        PaySyncResult res = payService.syncPayStatus("ORD001");
        assertThat(res.paid()).isFalse();
        assertThat(res.refunded()).isTrue();
        verify(alipayGatewayClient).refundPay(eq("P001"), anyString(), eq(new BigDecimal("100")), anyString());
        verify(refundMapper).markSuccess(any());
        // 关键：绝不入账（订单已取消，入账会让账目对不上）
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
        verify(orderPayMapper, never()).markPaid(anyString());
    }

    /** 悬挂款退款幂等：同一支付单只退一次（重复轮询不会重复退款） */
    @Test
    void syncPayStatus_strandedRefundIsIdempotent() {
        PayInfo pay = mockPayInfo(1L, 3);
        pay.setOrderNo("ORD001");
        pay.setOutTradeNo("P001");
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE009", "TRADE_SUCCESS", new BigDecimal("100"), true));
        when(refundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);   // 已有退款单

        assertThat(payService.syncPayStatus("ORD001").refunded()).isTrue();
        verify(alipayGatewayClient, never()).refundPay(anyString(), anyString(), any(), anyString());
        verify(refundMapper, never()).insert(any());
    }

    /** 节流：窗口内已查过就不再打渠道（前端 1s 轮询的护栏） */
    @Test
    void syncPayStatus_throttled_skipsChannelQuery() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingPay());
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        when(redissonClient.<String>getBucket("pay:query:P001")).thenReturn(queryBucket);
        when(queryBucket.trySet(eq("1"), anyLong(), any())).thenReturn(false);

        assertThat(payService.syncPayStatus("ORD001").paid()).isFalse();
        verify(alipayGatewayClient, never()).queryPay(anyString());
    }

    /** Redis 不可用不应阻断查单：宁可多查几次，也不能让用户永远看不到支付结果 */
    @Test
    void syncPayStatus_redisDown_stillQueriesChannel() throws InterruptedException {
        PayInfo pay = pendingPay();
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pay);
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        when(redissonClient.getBucket("pay:query:P001")).thenThrow(new RuntimeException("redis down"));
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE001", "TRADE_SUCCESS", new BigDecimal("100"), true));
        givenBookingSucceeds();

        assertThat(payService.syncPayStatus("ORD001").paid()).isTrue();
        verify(payInfoMapper).markSuccess(eq(1L), eq("TRADE001"), eq("amount=100"));
    }

    /** 与异步通知并发到达：状态机条件更新返回 0 行，不重复加余额/销量 */
    @Test
    void syncPayStatus_alreadyBookedByNotify_isIdempotent() throws InterruptedException {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingPay());
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        givenQueryPermit();
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE001", "TRADE_SUCCESS", new BigDecimal("100"), true));
        when(redissonClient.getLock("pay:notify:P001")).thenReturn(rLock);
        when(rLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(payInfoMapper.markSuccess(any(), anyString(), anyString())).thenReturn(0);  // 通知先到，已置成功
        when(orderPayMapper.markPaid("ORD001")).thenReturn(0);

        assertThat(payService.syncPayStatus("ORD001").paid()).isTrue();
        verify(orderPayMapper, never()).addMerchantBalance(anyLong(), any(BigDecimal.class));
    }

    // ----------------------------------------------------------
    // 关单补偿（订单取消后关掉渠道交易 / 退回悬挂款）
    // ----------------------------------------------------------

    /** 渠道没付：必须关掉支付宝侧交易，否则用户手里那个还开着的收银台页面能继续付款 */
    @Test
    void reconcile_closedPayAndChannelUnpaid_closesChannelTrade() {
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(PayQueryResult.notPaid("P001", "WAIT_BUYER_PAY"));

        assertThat(payService.reconcileClosedPay(closedPay())).isFalse();
        verify(alipayGatewayClient).closePay("P001");
        verify(alipayGatewayClient, never()).refundPay(anyString(), anyString(), any(), anyString());
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
    }

    /** 渠道已付：订单已取消，钱只能原路退回，绝不能入账 */
    @Test
    void reconcile_closedPayButChannelPaid_refundsStrandedMoney() {
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE009", "TRADE_SUCCESS", new BigDecimal("100"), true));
        when(refundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(refundMapper.insert(any(Refund.class))).thenReturn(1);
        when(alipayGatewayClient.refundPay(eq("P001"), anyString(), eq(new BigDecimal("100")), anyString()))
                .thenReturn("REFUND001");

        assertThat(payService.reconcileClosedPay(closedPay())).isTrue();
        verify(refundMapper).markSuccess(any());
        verify(alipayGatewayClient, never()).closePay(anyString());
        verify(payInfoMapper, never()).markSuccess(any(), any(), any());
    }

    /** 已对账过（Redis 标记在）：不再打扰渠道 */
    @Test
    void reconcile_alreadyReconciled_skipsChannel() {
        when(redissonClient.<String>getBucket("pay:reconciled:P001")).thenReturn(reconcileBucket);
        when(reconcileBucket.isExists()).thenReturn(true);

        assertThat(payService.reconcileClosedPay(closedPay())).isFalse();
        verify(alipayGatewayClient, never()).queryPay(anyString());
    }

    /** 金额对不上：既不关单也不退款（都动钱），留给后续对账继续暴露、人工介入 */
    @Test
    void reconcile_amountMismatch_leavesForManualHandling() {
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE009", "TRADE_SUCCESS", new BigDecimal("0.01"), true));

        assertThat(payService.reconcileClosedPay(closedPay())).isFalse();
        verify(alipayGatewayClient, never()).closePay(anyString());
        verify(alipayGatewayClient, never()).refundPay(anyString(), anyString(), any(), anyString());
    }

    /** 前端轮询到"已关闭的单被付了款"：返回 refunded=true，页面据此提示款项已退回 */
    @Test
    void syncPayStatus_closedPayButPaid_reportsRefunded() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(closedPay());
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(new PayQueryResult("P001", "TRADE009", "TRADE_SUCCESS", new BigDecimal("100"), true));
        when(refundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(refundMapper.insert(any(Refund.class))).thenReturn(1);

        PaySyncResult res = payService.syncPayStatus("ORD001");
        assertThat(res.paid()).isFalse();
        assertThat(res.refunded()).isTrue();
        assertThat(res.payStatus()).isEqualTo(3);
    }

    /** 已关闭但渠道也没付款：sync 返回 status=3，页面提示"订单已取消"，不再让用户干等 */
    @Test
    void syncPayStatus_closedPayAndUnpaid_reportsClosed() {
        when(payInfoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(closedPay());
        when(alipayGatewayClient.isRealChannel()).thenReturn(true);
        when(alipayGatewayClient.queryPay("P001"))
                .thenReturn(PayQueryResult.notPaid("P001", "TRADE_CLOSED"));

        PaySyncResult res = payService.syncPayStatus("ORD001");
        assertThat(res.paid()).isFalse();
        assertThat(res.refunded()).isFalse();
        assertThat(res.payStatus()).isEqualTo(3);
        verify(alipayGatewayClient).closePay("P001");
    }

    // ----------------------------------------------------------
    // helpers
    // ----------------------------------------------------------

    private PayInfo mockPayInfo(Long id, Integer status) {
        PayInfo pay = new PayInfo();
        pay.setId(id); pay.setPayNo("P001"); pay.setStatus(status);
        pay.setAmount(new BigDecimal("100"));
        return pay;
    }

    /** 待支付、金额 100 的支付单（挂在 ORD001 上） */
    private PayInfo pendingPay() {
        PayInfo pay = mockPayInfo(1L, 0);
        pay.setOrderNo("ORD001");
        pay.setOutTradeNo("P001");
        return pay;
    }

    /** 已关闭（订单已取消）的支付单：关单补偿/悬挂款退款的输入 */
    private PayInfo closedPay() {
        PayInfo pay = mockPayInfo(1L, 3);
        pay.setOrderNo("ORD001");
        pay.setOutTradeNo("P001");
        return pay;
    }

    /** 查单节流放行（第一次查渠道必然拿到许可） */
    private void givenQueryPermit() {
        when(redissonClient.<String>getBucket("pay:query:P001")).thenReturn(queryBucket);
        when(queryBucket.trySet(eq("1"), anyLong(), any())).thenReturn(true);
    }

    /** 入账链路前置：锁拿到 + 状态机成功 + 订单可同步 + 商家余额入账 */
    private void givenBookingSucceeds() throws InterruptedException {
        when(redissonClient.getLock("pay:notify:P001")).thenReturn(rLock);
        when(rLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(payInfoMapper.markSuccess(any(), anyString(), anyString())).thenReturn(1);
        when(orderPayMapper.markPaid("ORD001")).thenReturn(1);
        when(orderPayMapper.selectMerchantId("ORD001")).thenReturn(100L);
        when(orderPayMapper.selectItems("ORD001")).thenReturn(Collections.emptyList());
    }

    private OrderPayMapper.OrderPayView mockOrderPayView(Integer status) {
        OrderPayMapper.OrderPayView order = new OrderPayMapper.OrderPayView();
        order.setStatus(status); order.setPayAmount(new BigDecimal("100"));
        return order;
    }

    /** 构造渠道下单返回的支付凭证 */
    private PayCreateResult voucher(String payNo, String orderNo, boolean realChannel) {
        return new PayCreateResult(payNo, orderNo, "100.00",
                realChannel ? "https://openapi-sandbox.dl.alipaydev.com/gateway.do?x=1" : "http://localhost:5173/pay/" + orderNo,
                realChannel);
    }

    /** 构造支付宝异步通知参数（金额与支付单一致，便于入账用例） */
    private Map<String, String> notifyParams(String tradeStatus) {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", "P001");
        params.put("trade_no", "TRADE001");
        params.put("trade_status", tradeStatus);
        params.put("total_amount", "100");
        params.put("app_id", "2021000000000000");
        params.put("sign", "MOCK_SIGN");
        params.put("sign_type", "RSA2");
        return params;
    }
}
