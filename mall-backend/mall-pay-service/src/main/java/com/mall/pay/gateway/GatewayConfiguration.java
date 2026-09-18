package com.mall.pay.gateway;

import com.mall.pay.config.AlipayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付渠道装配：沙箱与 Mock 二选一，保证 {@code AlipayGatewayClient} 始终只有一个实例。
 *
 * <p>切换开关（{@code mall.alipay.enabled}）：
 * <table border="1">
 *   <tr><th>enabled</th><th>密钥</th><th>结果</th></tr>
 *   <tr><td>false（默认）</td><td>任意</td><td>{@link MockAlipayClient}，本地演示闭环</td></tr>
 *   <tr><td>true</td><td>齐全</td><td>{@link AlipaySandboxClient}，真实沙箱收款</td></tr>
 *   <tr><td>true</td><td>缺失</td><td><b>启动失败</b>（宁可启动报错，也不要"以为在收真钱其实在 Mock"）</td></tr>
 * </table>
 *
 * <p>回调验签需要沙箱客户端，但 Mock 模式下它不存在，
 * 因此调用方必须用 {@code ObjectProvider} 可选注入，不能强依赖。
 */
@Slf4j
@Configuration
public class GatewayConfiguration {

    /**
     * 真实沙箱渠道：仅在 {@code mall.alipay.enabled=true} 时注册。
     */
    @Bean
    @ConditionalOnProperty(prefix = "mall.alipay", name = "enabled", havingValue = "true")
    public AlipayGatewayClient alipaySandboxGateway(AlipayProperties props) {
        if (!props.configured()) {
            throw new IllegalStateException(
                    "mall.alipay.enabled=true 但 appId / privateKey / alipayPublicKey 未配置齐全。"
                            + "请检查 application.yml 或环境变量 MALL_ALIPAY_APP_ID / MALL_ALIPAY_PRIVATE_KEY "
                            + "/ MALL_ALIPAY_PUBLIC_KEY；若只想本地演示，请把 mall.alipay.enabled 设为 false（默认值）。");
        }
        log.info("[PAY] 当前支付渠道：支付宝沙箱（真实接入）appId={}, gateway={}",
                props.getAppId(), props.getGatewayUrl());
        return new AlipaySandboxClient(props);
    }

    /**
     * Mock 兜底渠道：{@code mall.alipay.enabled} 不为 true（含未配置）时注册。
     * 与上面的沙箱 Bean 通过同一个属性互斥，不会同时存在。
     */
    @Bean
    @ConditionalOnProperty(prefix = "mall.alipay", name = "enabled", havingValue = "false", matchIfMissing = true)
    public AlipayGatewayClient mockAlipayGateway(AlipayProperties props) {
        log.info("[PAY] 当前支付渠道：Mock（本地演示，未启用支付宝沙箱）");
        return new MockAlipayClient(mockPayBase(props));
    }

    /** Mock 演示页地址：复用 returnUrl 的站点根（默认 http://localhost:5173） */
    private String mockPayBase(AlipayProperties props) {
        String returnUrl = props.getReturnUrl();
        if (returnUrl == null || returnUrl.isBlank()) {
            return "http://localhost:5173";
        }
        int idx = returnUrl.indexOf("/pay");
        return idx > 0 ? returnUrl.substring(0, idx) : returnUrl.replaceAll("/+$", "");
    }
}
