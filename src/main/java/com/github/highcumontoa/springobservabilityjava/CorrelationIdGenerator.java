package com.github.highcumontoa.springobservabilityjava;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 入站关联标识的校验、沿用与生成。
 *
 * <p>接受规则（全部满足才原样沿用，否则明确替换为服务端生成值）：
 * <ul>
 *   <li>非空且长度 ≤ {@value #MAX_LENGTH}；</li>
 *   <li>去除首尾空白后仍非空；</li>
 *   <li>仅由可见 ASCII 字符组成：字母、数字、{@code -_.:=}。</li>
 * </ul>
 * 注意：带首尾空白的值不会被“修剪后沿用”，而是判定为不可用并替换，
 * 以保证响应中回显的标识与外部原始字符串的差异可被调用方识别。
 */
@Component
public class CorrelationIdGenerator {

    static final int MAX_LENGTH = 64;
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9\\-_.]{1,64}");

    /**
     * 解析入站标识。
     *
     * @param inboundHeader 原始请求头值
     * @param outSource     长度为 1 的输出参数，{@code [0]} 写入来源
     *                      ({@code inbound}/{@code generated})
     * @return 本请求实际使用的关联标识
     */
    public String resolveInbound(String inboundHeader, String[] outSource) {
        if (inboundHeader != null && ACCEPTED.matcher(inboundHeader).matches()) {
            if (outSource != null && outSource.length > 0) {
                outSource[0] = RequestContext.SOURCE_INBOUND;
            }
            return inboundHeader;
        }
        if (outSource != null && outSource.length > 0) {
            outSource[0] = RequestContext.SOURCE_GENERATED;
        }
        return generate();
    }

    /** 生成新标识：无前缀 UUID，长度 36，满足自身的接受规则。 */
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
