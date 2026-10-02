package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.concurrent.CompletableFuture;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 请求触发的异步处理服务。独立 Bean 保证 {@code @Async} 代理生效；
 * 工作线程上的关联标识由线程池上的 CorrelationTaskDecorator 还原。
 *
 * <p>失败语义：工作线程上抛出的异常（可能多层包装）在工作线程日志中记录后原样抛出，
 * 由 {@code join/wait} 侧的全局异常处理器沿 cause 链归因。因此一次异步失败在
 * 日志、指标、对外 HTTP 状态三处使用的是同一套分类，且失败不会被算成成功。</p>
 */
@Service
public class AsyncWorkService {

    private static final Logger log = LoggerFactory.getLogger(AsyncWorkService.class);

    /**
     * 执行异步结账任务。
     *
     * @param itemId 业务参数
     * @param failKind 失败模式：{@code null}/{@code none} 成功；
     *                 {@code client} 深层包装的 404 客户端失败；其他值为内部失败
     */
    @Async
    public CompletableFuture<String> processCheckout(String itemId, String failKind) {
        String id = CorrelationContext.currentId();
        var source = CorrelationContext.currentSource();
        log.info("async processing item: itemId={}, correlationId={}, source={}", itemId, id, source);

        if ("client".equals(failKind)) {
            // 深层包装的客户端错误：cause 链第 3 层才是可识别异常，验证工作线程异常也能被归因
            RuntimeException failure = new RuntimeException("async outer wrapper",
                    new IllegalStateException("async middle wrapper",
                            new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "async checkout missing item " + itemId)));
            log.error("async job failed: itemId={}, correlationId={}, kind=client", itemId, id, failure);
            return CompletableFuture.failedFuture(failure);
        }
        if (failKind != null && !"none".equals(failKind)) {
            // 内部失败：敏感细节只允许出现在服务端日志，绝不进入对外响应
            RuntimeException failure = new RuntimeException("async outer",
                    new IllegalStateException("async middle",
                            new IllegalStateException(
                                    "async inventory db password=hunter2 token=async-secret-7f3a9c token=zzz unreachable for item "
                                            + itemId)));
            log.error("async job failed: itemId={}, correlationId={}, kind=server", itemId, id, failure);
            return CompletableFuture.failedFuture(failure);
        }

        return CompletableFuture.completedFuture("job-for-" + itemId + "-" + id);
    }
}
