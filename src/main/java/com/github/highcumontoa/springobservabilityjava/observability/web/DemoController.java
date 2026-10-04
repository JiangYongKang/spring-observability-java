package com.github.highcumontoa.springobservabilityjava.observability.web;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    @GetMapping("/items/{itemId}")
    public Map<String, Object> getItem(@PathVariable String itemId,
                                       @RequestParam(name = "fail", required = false) String fail) {
        log.info("getItem invoked: itemId={}", itemId);
        if (fail != null) {
            throw new IllegalArgumentException("invalid query parameter: fail=" + fail);
        }
        return Map.of("itemId", itemId, "correlationId", CorrelationContext.currentId());
    }

    /**
     * 慢请求演示端点：固定睡眠 {@code millis} 毫秒（上限 30 秒，防止误挂）。
     * 用于手工复现"统计重置时仍有在途请求"的场景：重置前进入的慢请求，
     * 即使在重置之后才结束，也只计入重置前的旧批次，不会混入新批次。
     */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(name = "millis", defaultValue = "1000") long millis)
            throws InterruptedException {
        long clamped = Math.max(0L, Math.min(millis, 30_000L));
        log.info("slow request started: millis={}", clamped);
        Thread.sleep(clamped);
        return Map.of("sleptMillis", clamped, "correlationId", CorrelationContext.currentId());
    }

    @GetMapping("/oops")
    public Map<String, Object> oops(@RequestParam(name = "kind", defaultValue = "server") String kind) {        if ("client".equals(kind)) {
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
