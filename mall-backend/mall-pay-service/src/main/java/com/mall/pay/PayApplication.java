package com.mall.pay;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.mall.common.MyMetaObjectHandler;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 支付域服务：支付单创建（支付宝沙箱/Mock 渠道）、回调幂等处理（锁+唯一索引+状态机）、退款、
 * 主动查单补偿（通知到不了也能确认收款）、关单补偿（定时任务）。
 * 渠道抽象 AlipayGatewayClient：默认 Mock（可直接演示），配置密钥后切换真实沙箱实现。
 *
 * <p>{@code @EnableScheduling}：支撑关单补偿定时任务 {@code PayCloseSweeper}
 * （订单取消后关掉渠道交易，避免对已取消订单继续付款）。
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@MapperScan("com.mall.pay.mapper")
@Import(MyMetaObjectHandler.class)
@EnableScheduling
public class PayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayApplication.class, args);
    }
}
