package com.mall.pay.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@code application.yml} 能正确绑定到 {@link AlipayProperties}。
 *
 * <p>为什么单独测这个：配置项写错（缩进错、属性名拼错、占位符写错）是启动期最常见的坑，
 * 而编译期完全发现不了。本测试读取<b>真实的</b> application.yml，把嵌套结构拍平成
 * {@code key=value} 交给 Spring 做属性绑定与占位符解析——等价于启动时的绑定过程，
 * 但不需要 Nacos / MySQL / Redis。
 *
 * <p>同时锁定「默认走 Mock」这一行为：{@code enabled=false} 且密钥为空，
 * 保证 clone 下来不配任何密钥就能跑通全流程。
 */
class AlipayPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(AlipayProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(flattenApplicationYml());

    /**
     * 把 src/main/resources/application.yml 展开成扁平的 {@code key=value}，
     * 保留 {@code ${ENV:default}} 占位符原文，交给 Spring 的占位符解析器处理。
     */
    @SuppressWarnings("unchecked")
    private static String[] flattenApplicationYml() {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            List<String> flat = new ArrayList<>();
            flatten("", root, flat);
            return flat.toArray(new String[0]);
        } catch (IOException e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, List<String> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map) {
                flatten(key, (Map<String, Object>) v, out);
            } else if (v != null) {
                out.add(key + "=" + v);
            }
        }
    }

    @Test
    void applicationYml_bindsAlipayProperties_withMockDefaults() {
        runner.run(ctx -> {
            AlipayProperties props = ctx.getBean(AlipayProperties.class);
            // 默认必须是 Mock：不配密钥也能本地跑通
            assertThat(props.isEnabled()).isFalse();
            assertThat(props.configured()).isFalse();
            assertThat(props.effective()).isFalse();
            // 但默认值要完整，切沙箱时只需补三个密钥
            assertThat(props.getSignType()).isEqualTo("RSA2");
            assertThat(props.getCharset()).isEqualTo("UTF-8");
            assertThat(props.getGatewayUrl())
                    .isEqualTo("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
            assertThat(props.getReturnUrl()).isEqualTo("http://localhost:5173/pay/result");
            assertThat(props.getNotifyBase()).isEqualTo("http://localhost:8090");
        });
    }

    @Test
    void applicationYml_resolvesEnvOverrides() {
        // 模拟运维用环境变量开启沙箱：占位符必须能被解析并绑定到属性
        runner.withPropertyValues(
                        "MALL_ALIPAY_ENABLED=true",
                        "MALL_ALIPAY_APP_ID=2021000000000000",
                        "MALL_ALIPAY_PRIVATE_KEY=FAKE_PRIVATE_KEY",
                        "MALL_ALIPAY_PUBLIC_KEY=FAKE_PUBLIC_KEY",
                        "MALL_ALIPAY_NOTIFY_BASE=https://demo.ngrok-free.app/")
                .run(ctx -> {
                    AlipayProperties props = ctx.getBean(AlipayProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getAppId()).isEqualTo("2021000000000000");
                    assertThat(props.getPrivateKey()).isEqualTo("FAKE_PRIVATE_KEY");
                    assertThat(props.getAlipayPublicKey()).isEqualTo("FAKE_PUBLIC_KEY");
                    assertThat(props.configured()).isTrue();
                    assertThat(props.effective()).isTrue();
                    // 末尾斜杠需归一化，避免拼出 //api/pay/alipay/notify
                    assertThat(props.resolveNotifyUrl())
                            .isEqualTo("https://demo.ngrok-free.app/api/pay/alipay/notify");
                });
    }

    @Test
    void applicationYml_bindsServiceAndDataSource() {
        runner.run(ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.application.name")).isEqualTo("mall-pay-service");
            // 端口与库名是服务约定，写错会导致注册失败或连错库
            assertThat(env.getProperty("server.port")).isEqualTo("8015");
            assertThat(env.getProperty("spring.datasource.url")).contains("/mall_x");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("root");
        });
    }
}
