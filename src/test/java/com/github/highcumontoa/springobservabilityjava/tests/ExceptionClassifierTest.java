package com.github.highcumontoa.springobservabilityjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.highcumontoa.springobservabilityjava.error.ApiException;
import com.github.highcumontoa.springobservabilityjava.error.ErrorCategory;
import com.github.highcumontoa.springobservabilityjava.error.ExceptionClassifier;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/** 纯单测：嵌套/包装异常的归一分类与安全消息。 */
class ExceptionClassifierTest {

    private static final Logger log = LoggerFactory.getLogger(ExceptionClassifierTest.class);

    private final ExceptionClassifier classifier = new ExceptionClassifier();

    private void observe(String caseName, Throwable t) {
        Throwable root = classifier.unwrap(t);
        HttpStatus status = classifier.toStatus(t);
        ErrorCategory category = classifier.classify(t);
        // 单测日志打印错误分类（关联标识/路由在集成测试日志中体现）
        log.info("case={} root={} status={} errorCategory={} safeMessage='{}'",
                caseName, root.getClass().getSimpleName(), status.value(),
                category.name(), classifier.safeMessage(t, status, "test-cid"));
    }

    @Test
    void directClientExceptionIsClientError() {
        ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, "bad input");
        observe("directClient", ex);

        assertThat(classifier.classify(ex)).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(classifier.toStatus(ex)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(classifier.safeMessage(ex, HttpStatus.BAD_REQUEST, "c1"))
                .isEqualTo("bad input");
    }

    @Test
    void deepWrappedClientExceptionIsStillClientError() {
        // 业务 4xx 被裸 RuntimeException + CompletionException 包了两层
        Throwable wrapped = new CompletionException(
                new RuntimeException(new ApiException(HttpStatus.NOT_FOUND, "missing")));
        observe("deepWrappedClient", wrapped);

        assertThat(classifier.unwrap(wrapped)).isInstanceOf(ApiException.class);
        assertThat(classifier.classify(wrapped)).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(classifier.toStatus(wrapped)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownExceptionIsServerErrorAndMessageIsSanitized() {
        IllegalStateException ex = new IllegalStateException("secret: db password=hunter2");
        observe("server", ex);

        assertThat(classifier.classify(ex)).isEqualTo(ErrorCategory.SERVER_ERROR);
        String safe = classifier.safeMessage(ex, HttpStatus.INTERNAL_SERVER_ERROR, "c1");
        assertThat(safe).isEqualTo("Internal Server Error");
        assertThat(safe).doesNotContain("hunter2");
    }

    @Test
    void wrappedServerExceptionIsStillServerError() {
        Throwable wrapped = new CompletionException(
                new RuntimeException(new RuntimeException(new IllegalStateException("boom"))));
        observe("deepWrappedServer", wrapped);

        assertThat(classifier.unwrap(wrapped)).isInstanceOf(IllegalStateException.class);
        assertThat(classifier.classify(wrapped)).isEqualTo(ErrorCategory.SERVER_ERROR);
    }

    @Test
    void classificationByStatusStable() {
        assertThat(classifier.classifyByStatus(200)).isEqualTo(ErrorCategory.NONE);
        assertThat(classifier.classifyByStatus(404)).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(classifier.classifyByStatus(422)).isEqualTo(ErrorCategory.CLIENT_ERROR);
        assertThat(classifier.classifyByStatus(500)).isEqualTo(ErrorCategory.SERVER_ERROR);
        assertThat(classifier.classifyByStatus(503)).isEqualTo(ErrorCategory.SERVER_ERROR);
    }
}
