# Spring Observability（请求全链路可观测）

基于 Spring Boot 3.4 / Java 21，从零实现一套贯穿请求处理全流程的可观测能力：

1. **请求关联标识（Correlation ID）**：生成、校验、透传、跨线程传播；
2. **按路由模板聚合的请求指标**：计数与耗时，维度有界；
3. **异常归因与稳定状态映射**：嵌套/包装异常归一分类，日志 / 指标 / 响应三处一致；
4. 不依赖任何外部服务或凭据（无 Micrometer 注册表、无 APM、无数据库）。

## 1. 模块结构

| 类 | 职责 |
| --- | --- |
| `CorrelationIdGenerator` | 入站标识的校验（沿用）或替换（生成） |
| `CorrelationIdFilter` (`@Order(1)`) | 建立 `RequestContext` + MDC，回写响应头，请求结束清理 |
| `RequestContext` | 请求线程 `ThreadLocal`；MDC key / 请求属性键常量 |
| `MdcAwareTaskDecorator` / `AsyncConfig` | 提交时刻拷贝 MDC 快照到工作线程，执行后还原，防复用串号 |
| `error/ExceptionClassifier` | 解包嵌套异常、映射 HTTP 状态、归一错误分类、生成对外安全消息 |
| `error/GlobalExceptionHandler` | 统一异常出口；同一分类写入日志、请求属性（指标读取）、响应体 |
| `error/ErrorCategory` | `NONE` / `CLIENT_ERROR` / `SERVER_ERROR` 三类稳定枚举 |
| `metric/MetricsRegistry` | 并发聚合（`LongAdder`/`LongAccumulator`），基数硬上限 |
| `metric/RouteMetricKey` / `RouteMetricAggregate` | 维度键与聚合计数 |
| `metric/MetricsFilter` (`@Order(2)`) | 取路由模板维度记录指标；无路由不记录；输出观测日志 |
| `metric/MetricsController` | `GET /internal/metrics` 查询、`POST /internal/metrics/reset` 重置 |
| `web/DemoController` / `AsyncDemoService` | 演示端点（成功 / 4xx / 5xx / 嵌套异常 / 异步） |

## 2. 关联标识的生成与透传规则

- 请求头：`X-Correlation-Id`；响应头回显实际使用值，并用
  `X-Correlation-Id-Source: inbound | generated` **区分“原样沿用”与“明确替换”**。
- **沿用条件（全部满足，否则一律替换）**：非空、长度 ≤ 64、完整匹配
  `[A-Za-z0-9\-_.]{1,64}`。含空格 / 控制字符 / 超长 / 缺失都判为不可用，
  服务端生成 UUID（无连字符替换的标准 36 位），来源标记为 `generated`。
  注意：不做“修剪后沿用”，`" abc "` 会被替换，保证外部值与服务端值差异可被识别。
- 同步处理全程通过 SLF4J **MDC** 携带标识，日志 pattern 固定输出
  `correlationId=...`；过滤器在 `finally` 中清理 MDC 与 `ThreadLocal`，
  Tomcat 线程池复用线程时不会残留上一请求的标识。
- **异步传播**：请求通过注入的 `observationExecutor` 提交任务时，
  `MdcAwareTaskDecorator` 在**提交线程**抓取 MDC 快照（而非工作线程上读取），
  工作线程执行前装入、`finally` 中还原其旧值。因此：
  - 同步日志与异步日志（`obs-async-*` 线程）携带同一标识；
  - 线程池只有 2 个线程承接大量并发任务时，仍不串号（有并发测试覆盖）。
- 请求线程私有的 `RequestContext` ThreadLocal **不**跨线程；异步代码需要标识时
  一律从 MDC 读取。

```bash
# 沿用
curl -i -H 'X-Correlation-Id: trace-ABC_123.xyz' http://localhost:8080/api/users/1
# X-Correlation-Id: trace-ABC_123.xyz / X-Correlation-Id-Source: inbound

# 非法（含空格）-> 替换
curl -i -H 'X-Correlation-Id: bad id' http://localhost:8080/api/users/1
# X-Correlation-Id: <uuid> / X-Correlation-Id-Source: generated
```

## 3. 指标维度与基数约束

- 指标维度只有三个：**HTTP 方法 + 路由模板 + 错误分类**，
  例如 `GET /api/users/{id} NONE`。路径参数在 Spring MVC 匹配后已归一，
  `/api/users/a` 与 `/api/users/b` 计入同一条指标。
- **无法归属路由的请求（404）不记录、不新建维度**——过滤器取不到
  `bestMatchingPattern` 时直接跳过；绝不使用原始 URI 或查询串做维度，
  因此不存在“每个参数值一个时间序列”的基数爆炸。
- 维度基数理论上 ≈ 路由数 × 3（分类），天然有界；注册表另设**硬上限 200**，
  超限的新维度被丢弃并计入 `droppedRecords`（宁可显式丢弃，不静默膨胀）。
  这是有意的取舍：牺牲任意维度组合（如按调用方、按状态码明细），换取可预测的内存占用。
- 并发安全：计数与总耗时用 `LongAdder`，最小/最大耗时用 `LongAccumulator`，
  高并发写入分段累加无丢失；每个请求在过滤器 `finally` 中恰好 `record` 一次，
  不重复计数。

```bash
curl http://localhost:8080/internal/metrics
# cardinality / maxCardinality / droppedRecords / metrics[{method,routeTemplate,
# errorCategory,count,totalNanos,minNanos,maxNanos,avgNanos}]
curl -X POST http://localhost:8080/internal/metrics/reset
```

## 4. 错误分类规则

`ExceptionClassifier` 是唯一分类来源，规则集中、可解释：

1. **解包**：剥离 `CompletionException`、`ExecutionException` 以及裸
   `RuntimeException`/`Exception`（最多 32 层防环），保留自定义异常类型与
   `ResponseStatusException`/`ApiException` 的语义。
2. **状态映射**：`ApiException` 取其显式 `HttpStatus`；`ResponseStatusException`
   取其状态码；`NoHandlerFoundException` → 404；其余未知异常 → 500。
3. **归一分类**：4xx → `CLIENT_ERROR`，5xx → `SERVER_ERROR`，其余 → `NONE`。
4. **一致性**：`GlobalExceptionHandler` 计算一次分类，同时写入
   MDC（错误日志）、请求属性（`MetricsFilter` 读取后作为指标维度）、响应体
   `category` 字段——同一异常三处分类必然一致。
5. **不泄露内部细节**：5xx 响应体固定为 `Internal Server Error`，堆栈与内部
   异常消息只进服务端日志；4xx 允许返回可操作的业务消息。响应体：

```json
{ "correlationId": "...", "status": 500, "category": "SERVER_ERROR",
  "message": "Internal Server Error" }
```

`/api/errors/*` 与 `/api/async?label=fail` 提供直接错误、多层包装错误、
异步 `join()` 包装（`CompletionException`）错误的对照。

## 5. 演示端点

| 端点 | 说明 |
| --- | --- |
| `GET /api/users/{id}` | 成功；`id=missing` 返回 404 |
| `GET /api/orders/{orderId}/items/{itemId}` | 多路径参数模板归一 |
| `GET /api/errors/client` | 400 CLIENT_ERROR |
| `GET /api/errors/wrapped-client` | 两层包装的 422，解包后仍为 CLIENT_ERROR |
| `GET /api/errors/server` | 500，内部消息不出现在响应 |
| `GET /api/errors/wrapped-server` | 三层包装的 500 |
| `GET /api/async?label=ok|fail` | 请求触发异步；`fail` 的 4xx 经 `join` 包装后仍正确归类 |
| `GET /internal/metrics` / `POST /internal/metrics/reset` | 指标查询 / 重置 |

## 6. 本地验证方法

无需任何外部服务或凭据。

```bash
# 运行全部测试（23 个：单测 + 随机端口集成测试）
mvn test

# 启动应用手动验证
mvn spring-boot:run
# 或：mvn -DskipTests package && java -jar target/spring-observability-java-1.0-SNAPSHOT.jar

# 观察日志：每行固定含 correlationId / routeTemplate / errorCategory
curl -H 'X-Correlation-Id: demo-1' http://localhost:8080/api/async?label=ok
```

测试覆盖：

- **关联标识并发边界**（`CorrelationIdConcurrencyTest`）：沿用/生成/拒绝三态；
  32 线程 × 25 = 800 个并发请求（远超 Tomcat 工作线程数）回显标识零串号。
- **指标并发与聚合**（`MetricsAggregationTest`）：路径参数归一、404 不建维度、
  分类维度分离；1600 并发 HTTP 请求计数与请求总数一致；注册表原语 40,000 次
  直接并发写入无丢失无重复。
- **嵌套异常归类**（`ExceptionClassifierTest` / `ExceptionClassificationTest`）：
  多层包装的 4xx/5xx 正确解包归类；响应/指标分类一致；5xx 内部细节不外泄；
  异步 `join` 包装异常正确归类。
- **异步执行边界**（`AsyncPropagationIntegrationTest`）：2 个工作线程承接
  64 个不同标识的并发异步请求零串号；同步、异步日志与响应三者标识一致。

单测/集成测试日志均打印关联标识、路由模板与错误分类三字段
（见 `src/test/resources/logback-test.xml`）。
