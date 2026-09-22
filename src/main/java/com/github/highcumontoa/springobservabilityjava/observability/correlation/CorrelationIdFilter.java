package com.github.highcumontoa.springobservabilityjava.observability.correlation;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 入站请求关联标识过滤器。
 *
 * <ul>
 *   <li>请求头 {@value #HEADER_NAME} 携带且符合 {@link #ACCEPTED_VALUE} 规则时，原样沿用，
 *       来源记为 {@link CorrelationIdSource#INBOUND}；</li>
 *   <li>缺失或不合规（空、过长、含空白/控制字符，存在头注入风险）时，生成随机 UUID，
 *       来源记为 {@link CorrelationIdSource#GENERATED}，两种情况通过响应头
 *       {@value #SOURCE_HEADER_NAME} 与日志 MDC 字段可明确区分；</li>
 *   <li>过滤链结束后清理 ThreadLocal 与 MDC，保证线程池复用时不串号。</li>
 * </ul>
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String SOURCE_HEADER_NAME = "X-Correlation-ID-Source";

    private static final int MAX_LENGTH = 128;
    /** 仅接受可打印 ASCII、不含空白与分隔符，长度 1~128 的标识值。 */
    private static final Pattern ACCEPTED_VALUE = Pattern.compile("[!-~]{1," + MAX_LENGTH + "}");

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER_NAME);
        String correlationId;
        CorrelationIdSource source;
        if (inbound != null && ACCEPTED_VALUE.matcher(inbound).matches()) {
            correlationId = inbound;
            source = CorrelationIdSource.INBOUND;
        } else {
            correlationId = UUID.randomUUID().toString();
            source = CorrelationIdSource.GENERATED;
            if (inbound != null) {
                // 不打印原始值，避免把畸形/注入载荷写进日志；只记录被拒绝这一事实
                log.warn("inbound correlation id rejected (length={}); generated a new one",
                        inbound.length());
            }
        }

        response.setHeader(HEADER_NAME, correlationId);
        response.setHeader(SOURCE_HEADER_NAME, source.name());

        try (CorrelationContext.Scope ignored = CorrelationContext.open(correlationId, source)) {
            log.info("request start: method={}, uri={}, source={}",
                    request.getMethod(), request.getRequestURI(), source);
            filterChain.doFilter(request, response);
            log.info("request end: status={}", response.getStatus());
        }
    }
}
