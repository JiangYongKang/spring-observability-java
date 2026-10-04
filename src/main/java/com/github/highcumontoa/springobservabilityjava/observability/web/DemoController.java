package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 演示用端点：
 * <ul>
 *   <li>{@code GET /api/items/{itemId}}：正常路径，路径参数聚合到同一路由模板；</li>
 *   <li>{@code GET /api/oops?kind=client|conflict|server}：抛出深层包装异常，验证归因；</li>
 *   <li>{@code POST /api/items/{itemId}/checkout}：触发 {@code @Async} 异步处理，
 *       异步线程日志携带同一关联标识。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final AsyncWorkService asyncWorkService;

    public DemoController(AsyncWorkService asyncWorkService) {
        this.asyncWorkService = asyncWorkService;
    }

    /**
     * 可控的慢端点（用于本地核对统计重置的在途边界，无生产用途）：
     * 请求进入后阻塞在 {@code gate} 上直到被放开，从而可以稳定地制造"重置前进入、重置后才结束"。
     */
    private static volatile CountDownLatch slowGate = new CountDownLatch(0);
    private static volatile CountDownLatch slowEntered = new CountDownLatch(0);

    /** 让下一个慢请求阻塞，直到调用 {@link #releaseSlowRequest()}；并复位"已进入"信号。 */
    public static void armSlowRequest() {
        slowGate = new CountDownLatch(1);
        slowEntered = new CountDownLatch(1);
    }

    /** 阻塞直到被 arm 的慢请求真正进入控制器（已完成进入时的批次领取）。 */
    public static void awaitSlowRequestEntered() throws InterruptedException {
        slowEntered.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 放开被 arm 的慢请求。 */
    public static void releaseSlowRequest() {
        slowGate.countDown();
    }

    @GetMapping("/slow")
    public Map<String, Object> slow() throws InterruptedException {
        log.info("slow request entered, waiting for gate");
        slowEntered.countDown();
        slowGate.await();
        log.info("slow request released");
        return Map.of("done", true, "correlationId", CorrelationContext.currentId());
    }

    @GetMapping("/items/{itemId}")
    public Map<String, Object> getItem(@PathVariable String itemId,
                                       @RequestParam(name = "fail", required = false) String fail) {
        log.info("getItem invoked: itemId={}", itemId);
        if (fail != null) {
            throw new IllegalArgumentException("invalid query parameter: fail=" + fail);
        }
        return Map.of("itemId", itemId, "correlationId", CorrelationContext.currentId());
    }

    @GetMapping("/oops")
    public Map<String, Object> oops(@RequestParam(name = "kind", defaultValue = "server") String kind) {
        if ("client".equals(kind)) {
            // 深层包装的客户端错误：cause 链第 3 层才是可识别异常。
            // 消息中携带唯一敏感令牌，仅应出现在服务端日志；若响应回显异常 message 就会泄漏它
            throw new RuntimeException("outer wrapper",
                    new IllegalStateException("middle wrapper",
                            new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "missing resource secret-token-3f9a1c7e-42")));
        }
        if ("conflict".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "state conflict");
        }
        // 默认：深层包装的服务端错误，内部细节不得出现在响应体中
        throw new RuntimeException("outer",
                new IllegalStateException("middle",
                        new IllegalStateException(
                                "db password=hunter2 token=server-secret-8b2d6e1f-99 connection failed")));
    }

    @PostMapping("/items/{itemId}/checkout")
    public Map<String, Object> checkout(@PathVariable String itemId,
                                        @RequestBody(required = false) String body,
                                        @RequestParam(name = "asyncFail", required = false) String asyncFail) {
        log.info("checkout requested: itemId={}, asyncFail={}", itemId, asyncFail);
        // 等到异步结果：异步任务失败时 join 抛出 CompletionException，由全局异常处理器解包归因，
        // 因此日志/指标/响应状态三处对这次异步失败使用同一分类，且不会被算成成功
        CompletableFuture<String> future = asyncWorkService.processCheckout(itemId, asyncFail);
        return Map.of("itemId", itemId, "accepted", true, "asyncJob", future.join());
    }
}
