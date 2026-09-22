package com.github.highcumontoa.springobservabilityjava.tests;

import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationContext;
import com.github.highcumontoa.springobservabilityjava.observability.error.ErrorCategory;
import com.github.highcumontoa.springobservabilityjava.observability.error.ExceptionClassifier;
import com.github.highcumontoa.springobservabilityjava.observability.metrics.ErrorClass;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 异常归因单元测试：深层包装、成环 cause、客户端/服务端区分、脱敏文案。
 */
class ExceptionClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(ExceptionClassificationTest.class);

    private final ExceptionClassifier classifier = new ExceptionClassifier();

    @Test
    void deeplyWrappedClientErrorIsUnwrappedTo404() {
        Exception wrapped = new RuntimeException("outer",
                new IllegalStateException("middle",
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "secret resource id 42")));
        ExceptionClassifier.Classification result = classifier.classify(wrapped);

        // 在模拟请求上下文中打印：关联标识 + 错误分类（满足测试日志可解释性）
        try (CorrelationContext.Scope ignored = CorrelationContext.open("test-corr-404",
                com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdSource.GENERATED)) {
            log.info("classification check: errorCategory={}, errorClass={}, httpStatus={}",
                    result.category(), result.errorClass(), result.httpStatus());
        }

        assertEquals(ErrorCategory.NOT_FOUND, result.category());
        assertEquals(ErrorClass.CLIENT_ERROR, result.errorClass());
        assertEquals(404, result.httpStatus());
    }

    @Test
    void conflictAndValidationAreDistinctClientCategories() {
        ExceptionClassifier.Classification conflict = classifier.classify(
                new ResponseStatusException(HttpStatus.CONFLICT, "conflict"));
        ExceptionClassifier.Classification invalidArg = classifier.classify(
                new IllegalArgumentException("bad param"));

        assertEquals(ErrorCategory.CONFLICT, conflict.category());
        assertEquals(409, conflict.httpStatus());
        assertEquals(ErrorCategory.VALIDATION, invalidArg.category());
        assertEquals(400, invalidArg.httpStatus());
        // 同属客户端错误，但失败原因可区分
        assertFalse(conflict.category().equals(invalidArg.category()));
    }

    @Test
    void serverErrorDetailsAreNeverExposed() {
        Exception wrapped = new RuntimeException("outer",
                new IllegalStateException("middle",
                        new IllegalStateException("db password=hunter2 token=abc123 failed")));
        ExceptionClassifier.Classification result = classifier.classify(wrapped);

        assertEquals(ErrorCategory.SERVER_OTHER, result.category());
        assertEquals(ErrorClass.SERVER_ERROR, result.errorClass());
        assertEquals(500, result.httpStatus());
        assertEquals("internal server error", result.publicReason());
        assertFalse(result.publicReason().contains("hunter2"));
        assertFalse(result.publicReason().contains("password"));
    }

    @Test
    void cyclicCauseChainDoesNotHangAndDefaultsToServerError() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b); // 人为构造 cause 环

        ExceptionClassifier.Classification result = classifier.classify(a);
        assertEquals(ErrorCategory.SERVER_OTHER, result.category());
        assertEquals(500, result.httpStatus());
    }

    @Test
    void nullThrowableMapsToServerError() {
        ExceptionClassifier.Classification result = classifier.classify(null);
        assertEquals(ErrorClass.SERVER_ERROR, result.errorClass());
    }
}
