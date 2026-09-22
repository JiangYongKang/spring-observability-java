package com.github.highcumontoa.springobservabilityjava.web;

import com.github.highcumontoa.springobservabilityjava.RequestContext;
import com.github.highcumontoa.springobservabilityjava.error.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示路由：成功、客户端错误、服务端错误、多层包装异常、请求触发的异步执行。
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final AsyncDemoService asyncDemoService;

    public DemoController(AsyncDemoService asyncDemoService) {
        this.asyncDemoService = asyncDemoService;
    }

    /** 路径参数路由：不同 id 归入同一模板指标。 */
    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable String id) {
        log.info("fetching user id={}", id);
        if ("missing".equals(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "user not found: " + id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("correlationId", RequestContext.getCorrelationId());
        return body;
    }

    /** 多路径参数路由，用于验证模板归一。 */
    @GetMapping("/orders/{orderId}/items/{itemId}")
    public Map<String, Object> getOrderItem(@PathVariable String orderId,
                                            @PathVariable String itemId) {
        return Map.of("orderId", orderId, "itemId", itemId);
    }

    /** 直接抛出的客户端错误（4xx）。 */
    @GetMapping("/errors/client")
    public Map<String, Object> clientError() {
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid parameter 'q'");
    }

    /** 多层包装的客户端错误：解包后仍应归类 CLIENT_ERROR。 */
    @GetMapping("/errors/wrapped-client")
    public Map<String, Object> wrappedClientError() {
        Exception nested = new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "validation failed for nested resource");
        throw new CompletionException(new RuntimeException(nested));
    }

    /** 未预期内部错误：归类 SERVER_ERROR，细节只进日志不进响应。 */
    @GetMapping("/errors/server")
    public Map<String, Object> serverError() {
        throw new IllegalStateException("db password is hunter2 (internal detail)");
    }

    /** 多层包装的服务端错误：解包后仍应归类 SERVER_ERROR。 */
    @GetMapping("/errors/wrapped-server")
    public Map<String, Object> wrappedServerError() {
        Exception root = new IllegalStateException("NPE deep inside at secret-table");
        throw new CompletionException(new RuntimeException(new RuntimeException(root)));
    }

    /**
     * 请求触发的异步执行：工作线程日志须携带同一关联标识。
     * label=fail 时异步任务抛 4xx，join 包装为 CompletionException 后仍应正确归类。
     */
    @GetMapping("/async")
    public Map<String, Object> asyncEndpoint(@RequestParam(defaultValue = "ok") String label) {
        CompletableFuture<String> future = asyncDemoService.runAsyncStep(label);
        String result = future.join();
        log.info("async step joined result={}", result);
        return Map.of("result", result, "correlationId", RequestContext.getCorrelationId());
    }
}
