package com.mall.pay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付宝沙箱配置。
 *
 * <p>密钥获取步骤（沙箱控制台 → https://open.alipay.com/develop/sandbox/app）：
 * <ol>
 *   <li>appId / 支付宝网关：控制台「沙箱应用」页直接可见</li>
 *   <li>应用私钥：用「支付宝密钥生成工具」生成 RSA2 密钥对，把<b>应用私钥</b>填到
 *       {@code private-key}（PKCS8，单行 Base64，不含头尾）</li>
 *   <li>支付宝公钥：把上一步生成的<b>应用公钥</b>填进控制台，控制台会给出
 *       <b>支付宝公钥</b>，填到 {@code alipay-public-key}（PKIX，单行 Base64）</li>
 * </ol>
 *
 * <p>建议用环境变量注入，不要把私钥提交进仓库：
 * <pre>
 * MALL_ALIPAY_APP_ID / MALL_ALIPAY_PRIVATE_KEY / MALL_ALIPAY_PUBLIC_KEY / MALL_ALIPAY_NOTIFY_BASE
 * </pre>
 * 或本地覆盖文件 {@code application-local.yml}（已加入 .gitignore）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.alipay")
public class AlipayProperties {

    /** 总开关：false 时走 Mock 渠道（本地无密钥也能跑通全流程） */
    private boolean enabled = false;

    /** 应用 APPID（沙箱应用页可见） */
    private String appId;

    /** 应用私钥（RSA2 / PKCS8，单行 Base64） */
    private String privateKey;

    /** 支付宝公钥（RSA2 / PKIX，单行 Base64，用于回调验签）。公钥模式必填；证书模式下不需要（验签公钥取自 alipay-public-cert-path） */
    private String alipayPublicKey;

    /**
     * 加签模式：{@code publicKey}（默认，公钥模式）或 {@code cert}（证书模式）。
     *
     * <p>选择依据：支付宝控制台提示<b>资金支出类接口（退款等）必须使用证书模式</b>，
     * 故项目默认推荐 {@code cert}。两种模式本项目都已实测可用：
     * 公钥模式配好"应用公钥 ↔ 应用私钥"同样能收款。
     *
     * <p>⚠️ 历史误判更正：早期迭代记录里写过"沙箱中公钥模式会被拒(invalid-signature)"，
     * 那是**误判**——真正的原因是跳转地址取错了（POST 表单的 action 里没有 biz_content，
     * 见 {@code AlipaySandboxClient#buildGatewayUrl}），与加签模式无关。
     * 换证书模式之所以曾经"看起来也不行"，是因为当时粘的私钥与下载到的应用公钥证书不是同一对
     * （现由启动预检 {@code AlipayKeyInspector} 直接拦下并打印双方指纹）。
     */
    private String signMode = "publicKey";

    /** 证书模式-应用公钥证书路径（appPublicCert.crt） */
    private String appCertPath;

    /** 证书模式-支付宝公钥证书路径（alipayPublicCert.crt） */
    private String alipayPublicCertPath;

    /** 证书模式-支付宝根证书路径（alipayRootCert.crt） */
    private String alipayRootCertPath;

    /** 支付宝网关：沙箱用 openapi-sandbox，生产用 openapi */
    private String gatewayUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

    /** 签名算法 */
    private String signType = "RSA2";

    /** 字符集 */
    private String charset = "UTF-8";

    /** 支付成功同步跳转地址（用户付款后浏览器跳回本系统） */
    private String returnUrl = "http://localhost:5173/pay/result";

    /**
     * 异步通知基地址：必须是<b>支付宝服务器能访问到</b>的地址。
     * 本机开发用内网穿透（ngrok / cpolar）把 8090 暴露出去后填公网地址，
     * 例如 {@code https://xxxx.ngrok-free.app}，实际通知地址为
     * {@code {notifyBase}/api/pay/alipay/notify}。
     */
    private String notifyBase = "http://localhost:8090";

    /** 支付宝异步通知地址（留空则用 notifyBase 拼接） */
    private String notifyUrl;

    /** 发起支付时默认的商品标题前缀 */
    private String subjectPrefix = "Mall-X 订单 ";

    /** 是否证书模式 */
    public boolean certMode() {
        return "cert".equalsIgnoreCase(signMode);
    }

    /** 证书模式所需三项是否齐全 */
    public boolean certFilesPresent() {
        return notBlank(appCertPath) && notBlank(alipayPublicCertPath) && notBlank(alipayRootCertPath);
    }

    /**
     * 是否已具备真实渠道条件。
     * 公钥模式需 appId + 应用私钥 + 支付宝公钥；证书模式需 appId + 应用私钥 + 三个证书
     * （证书模式不需要"支付宝公钥"文本：验签公钥取自支付宝公钥证书，见 AlipaySandboxClient#verifyNotify）。
     */
    public boolean configured() {
        if (!notBlank(appId) || !notBlank(privateKey)) {
            return false;
        }
        return certMode() ? certFilesPresent() : notBlank(alipayPublicKey);
    }

    /** 真实沙箱渠道是否生效 */
    public boolean effective() {
        return enabled && configured();
    }

    public String resolveNotifyUrl() {
        if (notBlank(notifyUrl)) {
            return notifyUrl;
        }
        String base = notifyBase == null ? "" : notifyBase.replaceAll("/+$", "");
        return base + "/api/pay/alipay/notify";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
