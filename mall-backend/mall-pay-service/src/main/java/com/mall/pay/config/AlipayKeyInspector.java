package com.mall.pay.config;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 支付宝密钥/证书自检工具（启动预检 + 联调排错用，不是业务逻辑）。
 *
 * <p>为什么需要它：密钥/证书配错时，支付宝**不会**在启动期报错，而是在用户点「前往支付宝付款」
 * 跳到收银台后才回一个 {@code invalid-signature(40002)} 的白页错误——排查成本极高。
 * 把能本地判定的错误提前到启动期、并打印可比对的指纹，是本类的唯一目的。
 *
 * <p>判定原理：RSA 密钥对的身份就是模数 n。私钥（PKCS8）与证书里的公钥（X.509）模数一致，
 * 才说明"支付宝用证书里的公钥能验通这把私钥签的名"。
 */
public final class AlipayKeyInspector {

    private AlipayKeyInspector() {
    }

    /**
     * 解析 PKCS8 私钥（入参须为单行 Base64，PEM 头尾/空白由
     * {@code AlipaySandboxClient.normalizeKey} 事先剥掉）。
     *
     * @throws IllegalArgumentException 格式非法（含被复制坏了一两个字符、多了全角标点等）
     */
    public static RSAPrivateCrtKey privateKey(String base64) {
        try {
            return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("应用私钥不是合法的 RSA/PKCS8 单行 Base64："
                    + e.getClass().getSimpleName()
                    + "（常见原因：末尾混入引号/全角标点、复制不完整、把应用公钥粘到了 private-key）", e);
        }
    }

    /** 解析 X.509/SPKI 公钥（单行 Base64） */
    public static RSAPublicKey publicKey(String base64) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("支付宝公钥不是合法的 RSA/X.509 单行 Base64：" + e.getMessage(), e);
        }
    }

    /** 读取证书文件（.crt / .pem）里的 RSA 公钥 */
    public static RSAPublicKey certPublicKey(String certPath) {
        if (certPath == null || certPath.isBlank()) {
            throw new IllegalArgumentException("证书路径为空");
        }
        Path p = Path.of(certPath);
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("证书文件不存在: " + certPath);
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(p))) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            return (RSAPublicKey) cert.getPublicKey();
        } catch (Exception e) {
            throw new IllegalArgumentException("证书解析失败(" + certPath + "): " + e.getMessage(), e);
        }
    }

    /** 证书主体名（CN=应用ID-APPID，日志里用于确认拿的是这个应用的证书） */
    public static String certSubject(String certPath) {
        Path p = Path.of(certPath);
        try (ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(p))) {
            return ((X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in)).getSubjectX500Principal().getName();
        } catch (Exception e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }

    /**
     * 私钥与公钥是否同一对（只比模数 n 与公钥指数 e）。
     * RSA 的私钥可导出公钥部分，因此"私钥 ↔ 公钥证书"也能用同一方法判定。
     */
    public static boolean sameKeyPair(PrivateKey priv, PublicKey pub) {
        if (!(priv instanceof RSAPrivateCrtKey p) || !(pub instanceof RSAPublicKey q)) {
            return false;
        }
        return p.getModulus().equals(q.getModulus())
                && p.getPublicExponent().equals(q.getPublicExponent());
    }

    /**
     * 由应用私钥导出**支付宝控制台要求的「应用公钥」**（单行 Base64，X.509/SPKI 格式）。
     *
     * <p>公钥模式下支付宝用控制台登记的应用公钥验签，把本串与控制台逐字核对即可确认配对。
     * 这里刻意交给 JDK 做 X.509 编码（而不是手写 DER），保证与证书/控制台格式逐字节一致。
     */
    public static String appPublicKeyBase64(RSAPrivateCrtKey key) {
        try {
            RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
            return Base64.getEncoder().encodeToString(pub.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("由私钥导出应用公钥失败: " + e.getMessage(), e);
        }
    }

    /**
     * 模数指纹（前 14 字节的 hex）。用于日志里比对"是不是同一对密钥"，
     * 不需要打印完整密钥，也不泄露私钥。
     */
    public static String fingerprint(PublicKey key) {
        return key instanceof RSAPublicKey rsa ? fingerprint(rsa.getModulus()) : "(非RSA公钥)";
    }

    /** 指纹重载：私钥（取模数，与对应公钥的指纹一致） */
    public static String fingerprint(PrivateKey key) {
        return key instanceof RSAPrivateCrtKey rsa ? fingerprint(rsa.getModulus()) : "(非RSA私钥)";
    }

    private static String fingerprint(BigInteger modulus) {
        byte[] b = modulus.toByteArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(14, b.length); i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    /**
     * 该地址是否指向本机 —— 支付宝服务器访问不到，异步通知必失败
     * （付款能成功，但订单不会自动变"已支付"）。
     */
    public static boolean isLocalhost(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String host;
        try {
            host = URI.create(url.trim()).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) {
            return true;
        }
        String h = host.toLowerCase();
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")
                || h.equals("0.0.0.0") || h.startsWith("192.168.") || h.startsWith("10.");
    }
}
