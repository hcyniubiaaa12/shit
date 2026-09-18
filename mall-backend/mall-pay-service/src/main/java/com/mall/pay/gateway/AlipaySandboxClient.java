package com.mall.pay.gateway;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayRequest;
import com.alipay.api.AlipayResponse;
import com.alipay.api.CertAlipayRequest;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.mall.common.BizException;
import com.mall.pay.config.AlipayKeyInspector;
import com.mall.pay.config.AlipayProperties;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 支付宝开放平台沙箱渠道（真实接入，非 Mock）。
 *
 * <p>能力：
 * <ul>
 *   <li>{@code alipay.trade.page.pay} —— 电脑网站支付，生成跳转支付宝收银台的表单</li>
 *   <li>{@code alipay.trade.refund} —— 退款（out_request_no 幂等）</li>
 *   <li>{@code alipay.trade.close} —— 关单（订单超时取消后防止继续付款）</li>
 *   <li>{@link #verifyNotify} —— 异步通知 RSA2 验签（回调安全的第一道门）</li>
 * </ul>
 *
 * <p>线程安全：{@link AlipayClient} 内部持连接池，可单例复用。
 * 本类不依赖 Spring，方便单元测试直接 new（见 PayServiceTest 同目录测试）。
 */
@Slf4j
public class AlipaySandboxClient implements AlipayGatewayClient {

    private final AlipayProperties props;
    private final AlipayClient client;
    /** 归一化后的支付宝公钥（验签用；SDK 不接受 PEM/多行格式） */
    private final String normalizedAlipayPublicKey;
    private final String normalizedCharset;
    private final String normalizedSignType;
    /** 是否证书模式（决定调用 execute 还是 certificateExecute） */
    private final boolean certMode;

    /** 从返回的表单 HTML 里抠出支付宝网关地址（action="..."） */
    private static final Pattern FORM_ACTION = Pattern.compile("action=\"([^\"]+)\"");

    /** 页面跳转必须用 GET：见 {@link #buildGatewayUrl} 的说明（POST 表单的 action 缺 biz_content） */
    private static final String PAGE_HTTP_METHOD_GET = "GET";

    /** 渠道交易有效期：与订单 30 分钟超时自动取消保持一致（否则会出现"订单已取消却付了款"） */
    private static final String TRADE_TIMEOUT_EXPRESS = "30m";

    public AlipaySandboxClient(AlipayProperties props) {
        this.props = props;
        // 密钥归一化：支付宝密钥生成工具导出的是 PEM（多行 + 头尾标记），
        // 而 alipay-sdk 的 AlipaySignature 只接受单行 Base64（带换行/头尾会导致验签静默失败）。
        // 实测确认：直接粘贴 PEM 会验签失败，故此处统一剥掉头尾与所有空白字符。
        String privateKey = normalizeKey(props.getPrivateKey());
        String publicKey = normalizeKey(props.getAlipayPublicKey());
        this.normalizedAlipayPublicKey = publicKey;
        this.normalizedCharset = props.getCharset();
        this.normalizedSignType = props.getSignType();
        this.certMode = props.certMode();

        // 启动预检：把"能启动、跳到支付宝才报 invalid-signature"的配置错误提前暴露（见 AlipayKeyInspector）
        preflight(privateKey, publicKey);

        if (certMode) {
            // 证书模式：支付宝控制台提示"资金支出类接口必须使用证书模式"。
            // 三个证书由支付宝签发，与 APPID 强绑定，不存在手工上传公钥可能出现的错配。
            CertAlipayRequest certReq = new CertAlipayRequest();
            certReq.setServerUrl(props.getGatewayUrl());
            certReq.setAppId(props.getAppId());
            certReq.setPrivateKey(privateKey);
            certReq.setFormat("json");
            certReq.setCharset(props.getCharset());
            certReq.setSignType(props.getSignType());
            certReq.setCertPath(props.getAppCertPath());
            certReq.setAlipayPublicCertPath(props.getAlipayPublicCertPath());
            certReq.setRootCertPath(props.getAlipayRootCertPath());
            try {
                this.client = new DefaultAlipayClient(certReq);
            } catch (AlipayApiException e) {
                throw new BizException("支付宝证书模式初始化失败（检查三个证书路径与内容）: " + e.getMessage());
            }
            log.info("[ALIPAY] 沙箱渠道已启用(证书模式): appId={}, gateway={}, notifyUrl={}",
                    props.getAppId(), props.getGatewayUrl(), props.resolveNotifyUrl());
        } else {
            this.client = new DefaultAlipayClient(
                    props.getGatewayUrl(),
                    props.getAppId(),
                    privateKey,
                    "json",
                    props.getCharset(),
                    publicKey,
                    props.getSignType());
            log.info("[ALIPAY] 沙箱渠道已启用(公钥模式): appId={}, gateway={}, notifyUrl={}",
                    props.getAppId(), props.getGatewayUrl(), props.resolveNotifyUrl());
        }
        if (!privateKey.equals(props.getPrivateKey())
                || (publicKey != null && !publicKey.equals(props.getAlipayPublicKey()))) {
            log.info("[ALIPAY] 检测到 PEM 格式密钥，已自动归一化为单行 Base64");
        }
    }

    /**
     * 把 PEM / 带换行的密钥归一化成 SDK 要求的单行 Base64。
     * 已经是单行 Base64 时原样返回。
     */
    static String normalizeKey(String key) {
        if (key == null) {
            return null;
        }
        return key.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
    }

    /**
     * 启动预检：本地能判定的密钥/证书错误一律在启动期抛错，并打印可人工比对的指纹。
     *
     * <p>检查项：
     * <ol>
     *   <li>应用私钥能解析成 RSA/PKCS8（复制坏一个字符就会被抓出来）</li>
     *   <li><b>证书模式</b>：应用私钥与应用公钥证书（{@code appPublicCert.crt}）必须配对 ——
     *       证书模式支付宝是拿证书里的公钥验签的，两者不配对必然 {@code invalid-signature}
     *       （实测踩过：控制台换成证书模式后仍粘着旧密钥对）</li>
     *   <li><b>证书模式</b>：支付宝公钥证书可解析（回调验签要用它）</li>
     *   <li><b>公钥模式</b>：打印本私钥对应的「应用公钥」，便于与控制台登记值逐字比对</li>
     *   <li>异步通知地址是否指向本机（支付宝访问不到 → 付款成功但订单不会自动变已支付）</li>
     * </ol>
     */
    private void preflight(String privateKey, String publicKey) {
        RSAPrivateCrtKey appKey;
        try {
            appKey = AlipayKeyInspector.privateKey(privateKey);
        } catch (RuntimeException e) {
            throw new IllegalStateException("mall.alipay.private-key 配置错误: " + e.getMessage(), e);
        }
        log.info("[ALIPAY] 预检: 应用私钥已就绪，模数指纹={}", AlipayKeyInspector.fingerprint(appKey));

        if (certMode) {
            RSAPublicKey certKey;
            try {
                certKey = AlipayKeyInspector.certPublicKey(props.getAppCertPath());
            } catch (RuntimeException e) {
                throw new IllegalStateException("mall.alipay.app-cert-path 配置错误: " + e.getMessage(), e);
            }
            if (!AlipayKeyInspector.sameKeyPair(appKey, certKey)) {
                throw new IllegalStateException("mall.alipay 配置错误：应用私钥与应用公钥证书不是同一对密钥。"
                        + "私钥模数指纹=" + AlipayKeyInspector.fingerprint(appKey)
                        + "，证书公钥模数指纹=" + AlipayKeyInspector.fingerprint(certKey)
                        + "。证书模式下支付宝用证书里的公钥验签，不配对必然返回 invalid-signature(40002)；"
                        + "请到沙箱控制台重新生成密钥对并下载三个证书，或把与该证书配对的私钥填入 private-key。");
            }
            try {
                AlipayKeyInspector.certPublicKey(props.getAlipayPublicCertPath());
            } catch (RuntimeException e) {
                throw new IllegalStateException("mall.alipay.alipay-public-cert-path 配置错误: " + e.getMessage(), e);
            }
            log.info("[ALIPAY] 预检通过: 应用私钥 ↔ 应用公钥证书配对一致（证书主体 {}），支付宝公钥证书可解析",
                    AlipayKeyInspector.certSubject(props.getAppCertPath()));
        } else {
            log.info("[ALIPAY] 预检: 本私钥对应的应用公钥（须与控制台「接口加签方式 → 应用公钥」一致）={}",
                    AlipayKeyInspector.appPublicKeyBase64(appKey));
            if (publicKey != null && !publicKey.isBlank()) {
                try {
                    AlipayKeyInspector.publicKey(publicKey);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("mall.alipay.alipay-public-key 配置错误: " + e.getMessage(), e);
                }
            }
        }

        String notifyUrl = props.resolveNotifyUrl();
        if (AlipayKeyInspector.isLocalhost(notifyUrl)) {
            log.warn("[ALIPAY] notify-url 指向本机（{}），支付宝服务器访问不到：付款可以成功，"
                    + "但订单不会自动变为已支付（前端会一直显示待支付）。"
                    + "请用内网穿透把网关 8090 暴露成公网地址后填入 notify-base，"
                    + "见 docs/支付宝沙箱接入指南.md 第 4 节。", notifyUrl);
        }
    }

    /**
     * 统一的接口调用入口：证书模式必须用 {@code certificateExecute}，
     * 公钥模式用 {@code execute}（SDK 检测到证书参数时会抛
     * "请改为调用 certificateExecute"）。
     */
    private <T extends AlipayResponse> T exec(AlipayRequest<T> req) throws AlipayApiException {
        return certMode ? client.certificateExecute(req) : client.execute(req);
    }

    @Override
    public PayCreateResult createPay(String payNo, String orderNo, String subject, BigDecimal amount) {
        AlipayTradePagePayRequest req = new AlipayTradePagePayRequest();
        req.setReturnUrl(returnUrlWithOrderNo(orderNo));
        req.setNotifyUrl(props.resolveNotifyUrl());
        // 金额必须两位小数字符串，避免 1.0 被支付宝判为非法
        String amountStr = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String biz = "{"
                + "\"out_trade_no\":\"" + payNo + "\","
                + "\"total_amount\":\"" + amountStr + "\","
                + "\"subject\":\"" + escape(subject) + "\","
                // 渠道交易与订单超时（30 分钟）同步到期：过期后收银台直接不可支付。
                // 没有这个参数时支付宝交易会长期有效，订单早已取消用户却还能付款成功，
                // 产生"钱付了、订单没了"的悬挂款（实测踩过，见 PayService#refundStranded）。
                + "\"timeout_express\":\"" + TRADE_TIMEOUT_EXPRESS + "\","
                + "\"product_code\":\"FAST_INSTANT_TRADE_PAY\""
                + "}";
        req.setBizContent(biz);
        String payUrl = buildGatewayUrl(req);
        log.info("[ALIPAY] 创建沙箱支付: payNo={}, orderNo={}, amount={}, 交易有效期={}",
                payNo, orderNo, amountStr, TRADE_TIMEOUT_EXPRESS);
        return new PayCreateResult(payNo, orderNo, amountStr, payUrl, true);
    }

    /**
     * 同步跳转地址上挂业务订单号。
     *
     * <p>支付宝跳回来只会带它自己的参数（{@code out_trade_no} 是本系统的<b>支付单号</b>），
     * 落地页拿不到业务订单号；把 {@code orderNo} 挂在自己的 return_url 上，
     * 支付宝会原样带回（它只追加自己的参数）。后端也兼容只传支付单号的情况，
     * 见 {@code PayService#findForSync}。
     */
    String returnUrlWithOrderNo(String orderNo) {
        String base = props.getReturnUrl();
        if (base == null || base.isBlank() || orderNo == null || orderNo.isBlank()) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?")
                + "orderNo=" + URLEncoder.encode(orderNo, StandardCharsets.UTF_8);
    }

    @Override
    public void closePay(String outTradeNo) {
        AlipayTradeCloseRequest req = new AlipayTradeCloseRequest();
        req.setBizContent("{\"out_trade_no\":\"" + outTradeNo + "\"}");
        try {
            AlipayTradeCloseResponse resp = exec(req);
            // 关单失败不阻断业务：可能已支付/已关闭，记日志即可
            if (!resp.isSuccess()) {
                log.warn("[ALIPAY] 关单未成功: outTradeNo={}, code={}, msg={}",
                        outTradeNo, resp.getSubCode(), resp.getSubMsg());
            } else {
                log.info("[ALIPAY] 关单成功: outTradeNo={}", outTradeNo);
            }
        } catch (AlipayApiException e) {
            log.warn("[ALIPAY] 关单异常: outTradeNo={}, err={}", outTradeNo, e.getMessage());
        }
    }

    /**
     * 主动查单（{@code alipay.trade.query}）——"通知为主、查单为辅"里的辅。
     *
     * <p>典型场景：本机开发没有公网回调地址，异步通知永远到不了，
     * 光靠通知订单会一直停在"待支付"；前端轮询时调本方法即可确认真实收款结果。
     * 交易不存在（{@code ACQ.TRADE_NOT_EXIST}）是<b>正常情况</b>（用户还没在收银台付钱），
     * 不当作错误，只返回"未支付"。
     */
    @Override
    public PayQueryResult queryPay(String outTradeNo) {
        AlipayTradeQueryRequest req = new AlipayTradeQueryRequest();
        req.setBizContent("{\"out_trade_no\":\"" + outTradeNo + "\"}");
        try {
            AlipayTradeQueryResponse resp = exec(req);
            PayQueryResult result = toQueryResult(outTradeNo, resp);
            log.info("[ALIPAY] 主动查单: outTradeNo={}, exists={}, tradeStatus={}, tradeNo={}, amount={}",
                    outTradeNo, result.exists(), result.tradeStatus(), result.tradeNo(), result.totalAmount());
            return result;
        } catch (AlipayApiException e) {
            throw new BizException("支付宝查单异常: " + e.getMessage());
        }
    }

    /**
     * 查单响应 → 查单结果（抽成静态方法，便于不联网络的单测覆盖映射逻辑）。
     */
    static PayQueryResult toQueryResult(String outTradeNo, AlipayTradeQueryResponse resp) {
        if (!resp.isSuccess()) {
            // 交易不存在是正常情况（用户尚未付款）；其他失败码说明调用本身有问题，需要留痕
            if (!"ACQ.TRADE_NOT_EXIST".equals(resp.getSubCode())) {
                log.warn("[ALIPAY] 查单未成功: outTradeNo={}, code={}, subCode={}, msg={}",
                        outTradeNo, resp.getCode(), resp.getSubCode(), resp.getSubMsg());
            }
            return PayQueryResult.notPaid(outTradeNo, resp.getTradeStatus());
        }
        String amount = resp.getTotalAmount();
        return new PayQueryResult(outTradeNo, resp.getTradeNo(), resp.getTradeStatus(),
                amount == null || amount.isBlank() ? null : new BigDecimal(amount),
                true);
    }

    @Override
    public String refundPay(String outTradeNo, String outRequestNo, BigDecimal amount, String reason) {
        AlipayTradeRefundRequest req = new AlipayTradeRefundRequest();
        String amountStr = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        StringBuilder biz = new StringBuilder("{")
                .append("\"out_trade_no\":\"").append(outTradeNo).append("\",")
                .append("\"refund_amount\":\"").append(amountStr).append("\",")
                // out_request_no 是支付宝侧退款幂等号：同号重复请求只退一次
                .append("\"out_request_no\":\"").append(outRequestNo).append("\"");
        if (reason != null && !reason.isBlank()) {
            biz.append(",\"refund_reason\":\"").append(escape(reason)).append("\"");
        }
        biz.append("}");
        req.setBizContent(biz.toString());
        try {
            AlipayTradeRefundResponse resp = exec(req);
            if (!resp.isSuccess()) {
                throw new BizException("支付宝退款失败: code=" + resp.getSubCode() + ", msg=" + resp.getSubMsg());
            }
            log.info("[ALIPAY] 退款成功: outTradeNo={}, outRequestNo={}, amount={}, tradeNo={}",
                    outTradeNo, outRequestNo, amountStr, resp.getTradeNo());
            // 支付宝未返回 refund_no 时用幂等号兜底，保证本系统有流水号可存
            return resp.getTradeNo() != null ? resp.getTradeNo() : outRequestNo;
        } catch (AlipayApiException e) {
            throw new BizException("支付宝退款异常: " + e.getMessage());
        }
    }

    @Override
    public int channelCode() {
        return 0;
    }

    @Override
    public boolean isRealChannel() {
        return true;
    }

    /**
     * 异步通知验签（回调安全第一道门）。
     * 必须用<b>原始参数 Map</b>（Spring 的 @RequestParam Map 会保留全部参数），
     * 且验签前不能对参数做任何加工，否则签名必然不通过。
     *
     * @return 验签通过返回 true
     */
    public boolean verifyNotify(Map<String, String> params) {
        try {
            if (certMode) {
                // 证书模式：验签公钥在支付宝公钥证书里。
                // 控制台切到证书模式后通常不再给出"支付宝公钥"文本，若这里仍用 rsaCheckV1(null)
                // 会导致**每一条真实回调都验签失败**（订单永远停在待支付）。
                return AlipaySignature.rsaCertCheckV1(params, props.getAlipayPublicCertPath(),
                        normalizedCharset, normalizedSignType);
            }
            // 必须用归一化后的公钥：SDK 不接受 PEM / 多行格式，否则验签静默失败
            return AlipaySignature.rsaCheckV1(params, normalizedAlipayPublicKey,
                    normalizedCharset, normalizedSignType);
        } catch (AlipayApiException e) {
            log.warn("[ALIPAY] 验签异常: {}", e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------

    /**
     * 生成支付宝网关跳转 URL（前端 window.open 直接用这个地址打开收银台）。
     *
     * <p><b>必须用 {@code pageExecute(req, "GET")}，不能用默认的 {@code pageExecute(req)}。</b>
     * 两者的差别是本模块踩过的最大坑，实测结论如下：
     *
     * <ul>
     *   <li>默认的 {@code pageExecute(req)} 返回的是给浏览器 <b>POST 提交</b>用的 HTML 表单：
     *       其 {@code action} 的 query 里 <b>只有协议参数</b>
     *       （app_id / charset / method / notify_url / return_url / sign_type / timestamp /
     *       version / alipay_sdk / format + sign），<b>{@code biz_content} 在隐藏域里</b>，
     *       而 sign 是按"含 biz_content"算出来的。</li>
     *   <li>只把 action 当 URL 打开 → 支付宝收到的请求里没有 biz_content →
     *       网关重算待签串（回显里恰好只少 biz_content）→ 与实际签名不一致 →
     *       收银台白页报 {@code invalid-signature(40002)}。前端表现就是"跳转不对"。</li>
     *   <li>{@code pageExecute(req, "GET")} 返回的 body <b>本身就是完整 URL</b>：
     *       含 biz_content，签名与参数集自洽，可直接 GET 打开。</li>
     * </ul>
     *
     * <p>证书模式同样适用（SDK 会补上 {@code app_cert_sn} / {@code alipay_root_cert_sn}）。
     * 末尾的 {@code biz_content} 校验是防止该缺陷回归的硬闸门。
     */
    private String buildGatewayUrl(AlipayTradePagePayRequest req) {
        try {
            String body = execPage(req).getBody();
            if (body == null || body.isBlank()) {
                throw new BizException("支付宝返回空表单，请检查 appId/私钥配置");
            }
            String url = body.trim();
            if (!url.startsWith("http")) {
                // 兜底：万一 SDK 仍返回 HTML 表单，退化为取 action，靠下面的 biz_content 校验拦住错误地址
                log.warn("[ALIPAY] pageExecute(GET) 返回的不是 URL 而是 HTML 表单，退化为解析 action");
                url = extractAction(url);
            }
            url = htmlUnescape(url);
            if (!url.contains("biz_content")) {
                throw new BizException("支付宝跳转地址缺少 biz_content，浏览器打开必然被回 invalid-signature；"
                        + "请使用 pageExecute(req, \"GET\") 取跳转地址（见 docs/支付宝沙箱接入指南.md 第 6 节）");
            }
            return url;
        } catch (AlipayApiException e) {
            throw new BizException("支付宝下单异常: " + e.getMessage());
        }
    }

    /** GET 方式的页面跳转（证书模式与公钥模式共用；证书参数由 SDK 自行补齐） */
    private AlipayTradePagePayResponse execPage(AlipayTradePagePayRequest req) throws AlipayApiException {
        return client.pageExecute(req, PAGE_HTTP_METHOD_GET);
    }

    /** 从 HTML 表单里抠出 action 并反转义 */
    private String extractAction(String form) {
        Matcher m = FORM_ACTION.matcher(form);
        if (m.find()) {
            return htmlUnescape(m.group(1));
        }
        log.warn("[ALIPAY] 未从表单中解析出 action，返回原始表单");
        return form;
    }

    /** 反转义 HTML 实体：&amp; &lt; &gt; &quot; &#39; */
    static String htmlUnescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
