package com.mall.gateway;

import com.mall.common.JwtUtil;
import com.mall.common.MallConstants;
import com.mall.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 全局鉴权 + 限流过滤器：
 * 1. 游客白名单：登录/注册无需 token；商城前台浏览接口（类目/广告/商品列表/详情）游客可直访；
 * 2. 其余请求校验 Authorization: Bearer <token>，合法则转发 X-User-Id / X-User-Type / X-Username 头；
 * 3. 按"路径前缀 -> 允许身份类型"规则表鉴权（见表 RULES），不匹配前缀的请求通过（由下游细管）；
 * 4. 接口限流（大纲任务 12：接口限流/熔断降级基础配置）：内嵌固定窗口限流器，
 *    下单路径 QPS 20（演示观察友好），其余路径保护性 100——生产/完整版应接 Sentinel 网关适配器
 *    （本机私有镜像暂缺 sentinel-gateway-scg-adapter，见安装说明）；服务侧熔断降级见
 *    order-service @SentinelResource（createBlocked/createFallback）。
 * 下游服务不再解析 JWT，改读请求头（MallConstants.HEADER_*）。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${mall.jwt.secret}")
    private String secret;
    @Value("${mall.jwt.expire-seconds}")
    private long expireSeconds;

    private final AntPathMatcher matcher = new AntPathMatcher();

    /** 游客白名单：无需 token（登录/注册 + 商城浏览接口）；渠道回调无登录态，走白名单+金额核对/验签保护 */
    private static final String[] GUEST_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/register/merchant",
            "/api/portal/categories",
            "/api/portal/adverts",
            "/api/portal/products",
            "/api/portal/shops",
            "/api/pay/mock/callback",
            // 支付宝异步通知：由支付宝服务器直接 POST，没有也不可能有 JWT；
            // 安全性由下游 pay-service 的 RSA2 验签 + 金额核对保证（PayService.handleAlipayNotify）
            "/api/pay/alipay/notify"
    };

    /** 路径前缀 -> 允许的身份类型 */
    private static final Map<String, int[]> RULES = Map.of(
            "/api/portal/carts/", new int[]{MallConstants.TYPE_USER},
            "/api/order/", new int[]{MallConstants.TYPE_USER},
            "/api/pay/", new int[]{MallConstants.TYPE_USER},
            "/api/user/", new int[]{MallConstants.TYPE_USER},
            "/api/merchant/", new int[]{MallConstants.TYPE_MERCHANT},
            "/api/product/", new int[]{MallConstants.TYPE_MERCHANT},
            "/api/admin/", new int[]{MallConstants.TYPE_ADMIN},
            "/api/report/", new int[]{MallConstants.TYPE_ADMIN, MallConstants.TYPE_MERCHANT}
    );

    /** 固定窗口限流表：前缀 -> {限制窗口,QPS}。下单路径低阈值便于演示触发。 */
    private static final Map<String, Integer> QPS_LIMITS = Map.of(
            "/api/order/create", 20,
            "/api/portal/products", 100,
            "/api/admin/", 50
    );
    private static final long WINDOW_MILLIS = 1000L;
    private final java.util.concurrent.ConcurrentHashMap<String, WindowState> windows = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class WindowState {
        long windowStart = System.currentTimeMillis();
        int count = 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        for (String guest : GUEST_PATHS) {
            // 白名单均为无歧义前缀（/api/portal/products|adverts|categories 及后缀），startsWith 更可靠
            if (path.startsWith(guest)) {
                if (!allowRate(path)) {
                    return deny(exchange, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
                }
                return chain.filter(exchange);
            }
        }

        if (!allowRate(path)) {
            return deny(exchange, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return deny(exchange, HttpStatus.UNAUTHORIZED, "未登录或登录失效");
        }

        Map<String, Object> claims;
        try {
            claims = new JwtUtil(secret, expireSeconds).parse(authorization.substring(7));
        } catch (Exception e) {
            return deny(exchange, HttpStatus.UNAUTHORIZED, "token 无效或已过期");
        }

        Integer type = (Integer) claims.get("type");
        for (Map.Entry<String, int[]> rule : RULES.entrySet()) {
            if (path.startsWith(rule.getKey())) {
                boolean allowed = false;
                for (int t : rule.getValue()) {
                    if (t == type) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    return deny(exchange, HttpStatus.FORBIDDEN, "无权限访问");
                }
                break;
            }
        }

        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(MallConstants.HEADER_USER_ID, String.valueOf(claims.get("sub")))
                .header(MallConstants.HEADER_USER_TYPE, String.valueOf(type))
                .header(MallConstants.HEADER_USERNAME, String.valueOf(claims.get("username")))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ("{\"code\":" + Result.CODE_UNAUTHORIZED +
                ",\"message\":\"" + message + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** 固定窗口限流：按路径前缀匹配最接近的阈值（无匹配则放行） */
    private boolean allowRate(String path) {
        Integer limit = null;
        for (Map.Entry<String, Integer> e : QPS_LIMITS.entrySet()) {
            if (path.startsWith(e.getKey())) {
                limit = e.getValue();
                break;
            }
        }
        if (limit == null) {
            return true;
        }
        WindowState state = windows.computeIfAbsent(path, k -> new WindowState());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (now - state.windowStart >= WINDOW_MILLIS) {
                state.windowStart = now;
                state.count = 0;
            }
            state.count++;
            return state.count <= limit;
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
