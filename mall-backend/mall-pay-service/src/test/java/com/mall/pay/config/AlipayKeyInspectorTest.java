package com.mall.pay.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AlipayKeyInspector} 单测：密钥配对判定与"控制台应用公钥"导出。
 *
 * <p>这两项是沙箱联调里最容易踩、又最难从支付宝报错里看出来的地方：
 * 证书模式粘了别的密钥对的私钥、公钥模式忘了把应用公钥登记到控制台，
 * 支付宝都只回一句 {@code invalid-signature}。
 */
class AlipayKeyInspectorTest {

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @BeforeAll
    static void genKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        otherKeyPair = gen.generateKeyPair();
    }

    @Test
    void sameKeyPair_trueForMatchingPair_falseForForeignKey() throws Exception {
        assertThat(AlipayKeyInspector.sameKeyPair(keyPair.getPrivate(), keyPair.getPublic())).isTrue();
        assertThat(AlipayKeyInspector.sameKeyPair(keyPair.getPrivate(), otherKeyPair.getPublic())).isFalse();
        // 证书模式判定走的就是这条路径：私钥 ↔ 证书里的公钥
        assertThat(AlipayKeyInspector.sameKeyPair(otherKeyPair.getPrivate(), keyPair.getPublic())).isFalse();
    }

    @Test
    void fingerprint_sameForPrivateAndItsPublic() {
        assertThat(AlipayKeyInspector.fingerprint(keyPair.getPrivate()))
                .isEqualTo(AlipayKeyInspector.fingerprint(keyPair.getPublic()))
                .isNotEqualTo(AlipayKeyInspector.fingerprint(otherKeyPair.getPublic()));
    }

    /** 私钥导出的「应用公钥」必须能被解析回同一对密钥（公钥模式下要粘到控制台） */
    @Test
    void appPublicKeyBase64_derivesPasteableAppPublicKey() {
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) keyPair.getPrivate();
        String appPublicKey = AlipayKeyInspector.appPublicKeyBase64(priv);

        assertThat(appPublicKey).doesNotContain("-----").doesNotContain("\n");
        assertThat(AlipayKeyInspector.sameKeyPair(priv, AlipayKeyInspector.publicKey(appPublicKey))).isTrue();
        // 与 JDK 的 X.509 编码一致，证书/控制台都认这个格式
        assertThat(appPublicKey).isEqualTo(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    @Test
    void privateKey_brokenBase64_throwsWithActionableMessage() {
        // 典型事故：末尾粘进了引号或全角标点，或复制不完整
        assertThatThrownBy(() -> AlipayKeyInspector.privateKey("MIIEvgIBADANBgkq\"abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("应用私钥");
        assertThatThrownBy(() -> AlipayKeyInspector.privateKey(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("应用私钥");
    }

    @Test
    void publicKey_brokenBase64_throws() {
        assertThatThrownBy(() -> AlipayKeyInspector.publicKey("not-a-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("支付宝公钥");
    }

    @Test
    void certPublicKey_missingFile_throws() {
        assertThatThrownBy(() -> AlipayKeyInspector.certPublicKey("C:/not/exist/appPublicCert.crt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("证书文件不存在");
    }

    /** notify-url 指向本机 = 支付宝访问不到，付款成功但订单不会自动变已支付 */
    @Test
    void isLocalhost_detectsUnreachableNotifyUrls() {
        assertThat(AlipayKeyInspector.isLocalhost("http://localhost:8090/api/pay/alipay/notify")).isTrue();
        assertThat(AlipayKeyInspector.isLocalhost("http://127.0.0.1:8090/x")).isTrue();
        assertThat(AlipayKeyInspector.isLocalhost("http://192.168.1.7:8090/x")).isTrue();
        assertThat(AlipayKeyInspector.isLocalhost("")).isTrue();
        assertThat(AlipayKeyInspector.isLocalhost("https://demo.ngrok-free.app/api/pay/alipay/notify")).isFalse();
        assertThat(AlipayKeyInspector.isLocalhost("https://api.mall-x.com/api/pay/alipay/notify")).isFalse();
    }
}
