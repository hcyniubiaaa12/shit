package com.mall.order.vo;

import com.mall.order.entity.OrderItem;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单视图（列表/详情共用） */
@Data
public class OrderVO {

    private Long id;

    private String orderNo;

    private Long userId;

    private Long merchantId;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    private BigDecimal freight;

    private Integer status;

    private LocalDateTime payTime;

    private LocalDateTime cancelTime;

    private String remark;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String logisticsCompany;

    private String trackingNo;

    private LocalDateTime sendTime;

    private LocalDateTime receiveTime;

    private LocalDateTime createTime;

    /**
     * 待支付剩余秒数（仅 status=0 返回；服务端时间为基准，供前端倒计时展示）。
     * 到 0 只代表展示到期，真实取消以后端 MQ 消费端的状态机为准。
     */
    private Long remainSeconds;

    private List<OrderItem> items;
}
