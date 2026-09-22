package com.github.highcumontoa.springobservabilityjava.error;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 将任意异常（含嵌套/包装异常）归一为稳定的错误分类。
 *
 * <p>分类规则只有两类业务类别：
 * <ul>
 *   <li>{@link ErrorCategory#CLIENT_ERROR}：根因是 4xx（客户端可纠正）；</li>
 *   <li>{@link ErrorCategory#SERVER_ERROR}：根因是 5xx 或未知内部错误。</li>
 * </ul>
 * 日志、指标、响应必须使用同一个分类结果，因此所有派生映射都集中在本类。
 */
@Component
public class ExceptionClassifier {

    private static final String SERVER_FAILURE_MESSAGE = "Internal Server Error";

    /**
     * 剥离纯包装型异常，得到首个具备业务语义的根因。
     * 包装型：{@link CompletionException}、{@link ExecutionException}、
     * {@code RuntimeException}/{@code Exception} 本身（无消息且有 cause）。
     */
    public Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 32) {
            boolean pureWrapper =
                    current instanceof CompletionException
                            || current instanceof ExecutionException
                            || isBareJdkWrapper(current);
            if (current.getCause() == null || !pureWrapper) {
                return current;
            }
            current = current.getCause();
        }
        return current != null ? current : throwable;
    }

    private boolean isBareJdkWrapper(Throwable t) {
        // 仅剥裸 new RuntimeException(cause, ...) / new Exception(cause, ...)
        // 这类无语义包装（JDK 常自动生成带消息的包装）；自定义子类型保留，
        // 避免吞掉业务异常自身的语义。
        Class<?> type = t.getClass();
        boolean bareType = type == RuntimeException.class || type == Exception.class;
        return bareType && t.getCause() != null;
    }

    /** 根因异常 -> HTTP 状态。 */
    public HttpStatus toStatus(Throwable throwable) {
        Throwable root = unwrap(throwable);
        if (root instanceof ApiException api) {
            return api.getStatus();
        }
        if (root instanceof ResponseStatusException rse) {
            int code = rse.getStatusCode().value();
            HttpStatus resolved = HttpStatus.resolve(code);
            return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (root instanceof NoHandlerFoundException) {
            // 无路由：客户端错误，但不产生指标维度
            return HttpStatus.NOT_FOUND;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** 根因异常 -> 错误分类。 */
    public ErrorCategory classify(Throwable throwable) {
        return classifyByStatus(toStatus(throwable).value());
    }

    /** 按最终 HTTP 状态归类：4xx 客户端错误，5xx 服务端错误，其余不分类。 */
    public ErrorCategory classifyByStatus(int status) {
        if (status >= 400 && status < 500) {
            return ErrorCategory.CLIENT_ERROR;
        }
        if (status >= 500 && status < 600) {
            return ErrorCategory.SERVER_ERROR;
        }
        return ErrorCategory.NONE;
    }

    /**
     * 生成对外安全消息：
     * 4xx 可返回可操作的业务消息；5xx 一律返回固定文案，绝不回显内部异常消息。
     */
    public String safeMessage(Throwable throwable, HttpStatus status, String correlationId) {
        if (status.is4xxClientError()) {
            Throwable root = unwrap(throwable);
            if (root instanceof ApiException api && api.getMessage() != null) {
                return api.getMessage();
            }
            String reason = status.getReasonPhrase();
            return reason != null ? reason : "Bad Request";
        }
        return SERVER_FAILURE_MESSAGE;
    }
}
