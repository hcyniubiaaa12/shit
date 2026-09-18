package com.mall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.pay.entity.PayInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PayInfoMapper extends BaseMapper<PayInfo> {

    /** 状态机置成功：仅"待支付"可置（0 行=重复回调/已关闭，幂等） */
    @Update("UPDATE pay_info SET status = 1, trade_no = #{tradeNo}, notify_body = #{body}, notify_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int markSuccess(@Param("id") Long id, @Param("tradeNo") String tradeNo,
                    @Param("body") String body);

    /** 状态机置关闭：仅"待支付"可置（订单取消/超时取消时关闭渠道支付单） */
    @Update("UPDATE pay_info SET status = 3, notify_time = NOW(), update_time = NOW() WHERE id = #{id} AND status = 0")
    int markClosed(@Param("id") Long id);
}
