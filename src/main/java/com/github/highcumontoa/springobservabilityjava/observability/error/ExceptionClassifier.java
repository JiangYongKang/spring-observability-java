package com.github.highcumontoa.springobservabilityjava.observability.error;

import java.util.IdentityHashMap;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 异常归因器：把任意（可能被多层包装的）异常归一为一个稳定的 {@link Classification}。
 *
 * <p>同一异常的分类结果同时用于：结构化日志（errorCategory）、指标（{@link ErrorClass}）、
 * HTTP 响应（httpStatus + 脱敏 reason），三处一致。</p>
 *
 * <p>归因规则：</p>
 * <ol>
 *   <li>沿 {@code getCause()} 链深度优先解包（含循环保护），链上第一个可识别的异常决定类别；</li>
 *   <li>Spring MVC 的参数/消息体错误 → 400 VALIDATION；</li>
 *   <li>{@link ResponseStatusException} 按其状态码归入对应类别；</li>
 *   <li>{@link IllegalArgumentException} → 400 VALIDATION；</li>
 *   <li>其余异常 → 500 SERVER_OTHER；</li>
 *   <li>所有 5xx 的对外文案统一为固定脱敏消息，内部细节仅写服务端日志。</li>
 * </ol>
 */
@Component
public class ExceptionClassifier {

    private static final int MAX_CAUSE_DEPTH = 25;
    private static final String SERVER_PUBLIC_REASON = "internal server error";

    public Classification classify(Throwable throwable) {
        if (throwable == null) {
            return new Classification(ErrorCategory.SERVER_OTHER, ErrorClass.SERVER_ERROR, 500,
                    SERVER_PUBLIC_REASON);
        }
        Throwable cause = firstRecognized(throwable);

        if (cause instanceof MethodArgumentNotValidException
                || cause instanceof HttpMessageNotReadableException) {
            return new Classification(ErrorCategory.VALIDATION, ErrorClass.CLIENT_ERROR, 400,
                    "request validation failed");
        }
        // 未匹配任何处理器/静态资源：稳定映射为 404 NOT_FOUND
        if (cause instanceof NoResourceFoundException || cause instanceof NoHandlerFoundException) {
            return new Classification(ErrorCategory.NOT_FOUND, ErrorClass.CLIENT_ERROR, 404,
                    "resource not found");
        }
        if (cause instanceof ResponseStatusException rse) {
            return classifyByStatus(rse.getStatusCode().value());
        }
        if (cause instanceof IllegalArgumentException) {
            return new Classification(ErrorCategory.VALIDATION, ErrorClass.CLIENT_ERROR, 400,
                    "invalid request parameter");
        }
        return new Classification(ErrorCategory.SERVER_OTHER, ErrorClass.SERVER_ERROR, 500,
                SERVER_PUBLIC_REASON);
    }

    /** 遍历 cause 链，返回第一个可识别的异常；都不可识别时返回链上最深的异常。 */
    private Throwable firstRecognized(Throwable throwable) {
        Throwable current = throwable;
        Throwable deepest = throwable;
        var seen = new IdentityHashMap<Throwable, Boolean>();
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (seen.putIfAbsent(current, Boolean.TRUE) != null) {
                break; // cause 链成环，停止解包
            }
            deepest = current;
            if (isRecognized(current)) {
                return current;
            }
            current = current.getCause();
        }
        return deepest;
    }

    private boolean isRecognized(Throwable throwable) {
        return throwable instanceof MethodArgumentNotValidException
                || throwable instanceof HttpMessageNotReadableException
                || throwable instanceof NoResourceFoundException
                || throwable instanceof NoHandlerFoundException
                || throwable instanceof ResponseStatusException
                || throwable instanceof IllegalArgumentException;
    }

    private Classification classifyByStatus(int status) {
        if (status == 404) {
            return new Classification(ErrorCategory.NOT_FOUND, ErrorClass.CLIENT_ERROR, status,
                    "resource not found");
        }
        if (status == 409) {
            return new Classification(ErrorCategory.CONFLICT, ErrorClass.CLIENT_ERROR, status,
                    "request conflicts with current state");
        }
        if (status >= 400 && status < 500) {
            return new Classification(ErrorCategory.CLIENT_OTHER, ErrorClass.CLIENT_ERROR, status,
                    "bad request");
        }
        if (status == 503) {
            return new Classification(ErrorCategory.DEPENDENCY, ErrorClass.SERVER_ERROR, status,
                    SERVER_PUBLIC_REASON);
        }
        if (status >= 500) {
            return new Classification(ErrorCategory.SERVER_OTHER, ErrorClass.SERVER_ERROR,
                    status, SERVER_PUBLIC_REASON);
        }
        return new Classification(ErrorCategory.CLIENT_OTHER, ErrorClass.CLIENT_ERROR, status,
                "bad request");
    }

    /** 归因结果：细分类别、指标粗分类、对外 HTTP 状态、对外（已脱敏）原因文案。 */
    public record Classification(ErrorCategory category, ErrorClass errorClass, int httpStatus,
                                 String publicReason) {
    }
}
