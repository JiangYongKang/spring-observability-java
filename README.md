# Spring Boot 请求全链路可观测能力（关联标识 / 路由指标 / 异常归因）

在 Spring Boot 3.4（Java 21）骨架上实现的一套进程内可观测能力，**不依赖任何外部服务或凭据**：
每个入站请求都有唯一关联标识并透传到异步线程；指标按路由模板聚合且基数有界；
异常（含多层包装）被归一为稳定的错误类别，并在日志、指标、HTTP 响应三处保持一致。

## 1. 关联标识的生成与透传规则

- **请求头**：`X-Correlation-ID`。
- **沿用（可区分情况一）**：请求头存在，且值匹配正则 `[!-~]{1,128}`
  （1~128 个可打印 ASCII 字符、不含空白）时**原样沿用**，来源记为 `INBOUND`。
- **替换（可区分情况二）**：请求头缺失，或为空、超长、含空白/控制字符（存在 HTTP 头注入风险）时，
  服务端生成随机 UUID 替换，来源记为 `GENERATED`。畸形值本身不写入日志，只记录"被拒绝"这一事实。
- **两种情况如何区分**：
  - 响应头 `X-Correlation-ID`（最终使用的标识）与 `X-Correlation-ID-Source`（`INBOUND`/`GENERATED`）；
  - 日志 MDC 字段 `correlationId` 与 `correlationSource`；
  - 错误响应体中的 `correlationId` 字段。
- **同步链路**：`CorrelationIdFilter`（最高优先级）在请求开始时把标识放入 ThreadLocal + SLF4J MDC，
  请求结束后恢复线程进入前的状态，Tomcat 线程池复用时不会把上一请求的标识带进来。
- **异步链路**：业务线程池 `observabilityTaskExecutor` 装配了 `CorrelationTaskDecorator`：
  在**任务提交时**捕获标识快照、在**工作线程执行时**还原、执行完毕恢复现场。
  刻意**不使用 `InheritableThreadLocal`**——池化线程只在创建时继承一次，线程复用会导致串号。
- 代码入口：
  - `observability/correlation/CorrelationContext.java`
  - `observability/correlation/CorrelationIdFilter.java`
  - `observability/correlation/CorrelationTaskDecorator.java`
  - `observability/config/AsyncConfig.java`

## 2. 指标维度与基数约束

- 端点：`GET /internal/metrics`（进程内快照，JSON）。
- **维度只有两个**：路由模板（如 `/api/items/{itemId}`）与错误分类（`NONE/CLIENT_ERROR/SERVER_ERROR`）。
  路径参数值（`/items/1`、`/items/2`）永远不会成为维度值，不同参数归入同一模板。
- **基数上界明确**：维度数 = 应用注册的路由模板数（编译期可见、有界）+ 一个固定桶，
  不随 URL/参数/用户输入增长。
- **无法归属路由的请求**（未匹配处理器、404）统一计入固定桶 `UNMAPPED`，
  既不丢失计数，也**绝不按原始 URI 新建维度**（该类请求不经过拦截器，由全局异常处理器补记）。
- **统计内容**：总请求数、按错误分类计数、总耗时、最大耗时（纳秒）。
- **并发正确性**：计数使用 `LongAdder`（分段累加），最大耗时用 `AtomicLong` CAS 更新；
  注册表用 `ConcurrentHashMap#computeIfAbsent` 原子建桶。并发写入不丢失、不重复
  （单元测试以 16 线程 × 2000 次 = 32000 次写入校验总数与分类分项之和完全相等）。
- **取舍说明**：指标只在内存中保存、进程重启归零，目的是本地可复现验证；
  未引入百分位/直方图等需要额外内存结构的统计，避免无界内存增长。
- 代码入口：
  - `observability/metrics/MetricsRegistry.java`
  - `observability/metrics/RouteMetrics.java`
  - `observability/metrics/RouteMetricsInterceptor.java`

## 3. 错误分类规则

归因沿 `getCause()` 链**深度优先解包**（最多 25 层，带 IdentityHashMap 成环保护），
链上第一个可识别异常决定类别；都不可识别时按服务端错误处理。

| 场景 | errorCategory（细类，日志/响应体） | errorClass（粗类，指标维度） | HTTP 状态 | 对外 reason |
|---|---|---|---|---|
| 成功 | — | `NONE` | 2xx | — |
| 参数校验/非法参数、请求体不可读 | `VALIDATION` | `CLIENT_ERROR` | 400 | `request validation failed` / `invalid request parameter` |
| 资源不存在（含未匹配路由） | `NOT_FOUND` | `CLIENT_ERROR` | 404 | `resource not found` |
| 状态冲突 | `CONFLICT` | `CLIENT_ERROR` | 409 | `request conflicts with current state` |
| 其他 4xx | `CLIENT_OTHER` | `CLIENT_ERROR` | 对应状态 | `bad request` |
| 下游依赖不可用（503） | `DEPENDENCY` | `SERVER_ERROR` | 503 | `internal server error` |
| 其余未捕获异常 | `SERVER_OTHER` | `SERVER_ERROR` | 500 | `internal server error` |

- **三处一致的保证方式**：异常处理器用分类结果设置 HTTP 响应状态；指标拦截器在请求结束时
  以**最终响应状态码**反推 `errorClass`。因此异常是否被包装、是否被处理器吞掉改写，
  日志的 `errorCategory`、指标的 `errorClass`、调用方看到的状态码始终描述同一件事。
- **内部细节不外泄**：5xx 响应体只含 `correlationId / errorCategory / 固定脱敏文案`；
  异常类型、原始 message、堆栈只写服务端日志（5xx 打完整堆栈，4xx 打告警）。
- 代码入口：
  - `observability/error/ExceptionClassifier.java`
  - `observability/error/GlobalExceptionHandler.java`
  - `observability/error/ApiError.java`

## 4. 日志

日志格式（`logback-spring.xml`，测试使用 `src/test/resources/logback-test.xml`）固定携带：

```
时间 级别 [线程] [corrId=<关联标识>,src=<INBOUND|GENERATED>] logger - 消息
```

- 请求开始/结束、控制器、`@Async` 工作线程日志均带同一 `corrId`；
- 指标拦截器每个请求打印一行 `request observed: route=<模板>, status=, errorClass=, durationNanos=`，
  即"关联标识 + 路由模板 + 错误分类"同时出现在日志行中。

## 5. 演示端点

| 方法/路径 | 说明 |
|---|---|
| `GET /api/items/{itemId}` | 正常请求；加 `?fail=1` 触发 400 |
| `GET /api/oops?kind=client` | 三层包装的 404（验证解包归因） |
| `GET /api/oops?kind=conflict` | 409 |
| `GET /api/oops`（默认） | 三层包装的 500（含伪造敏感串，验证脱敏） |
| `POST /api/items/{itemId}/checkout` | 触发 `@Async`，验证异步标识透传 |
| `GET /internal/metrics` | 指标快照 |
| 任意未注册路径 | 404 且计入 `UNMAPPED` 桶 |

## 6. 本地验证方法

环境要求：JDK 21+、Maven 3.6+（依赖仅 spring-boot-starter-web/test，可离线构建）。

### 6.1 运行全部测试

```bash
mvn test
```

测试覆盖（共 16 个用例）：

| 测试类 | 覆盖点 |
|---|---|
| `ExceptionClassificationTest` | 深层包装解包、404/409/400 细类可区分、500 脱敏、cause 成环不死循环 |
| `RouteMetricsConcurrencyTest` | 16 线程并发写 32000 次计数精确相等；UNMAPPED 不产生新维度 |
| `CorrelationIdFilterTest` | 入站标识原样沿用、缺失生成、畸形替换；INBOUND/GENERATED 可区分 |
| `AsyncCorrelationTest` | 24 客户端 × 20 次真实 HTTP 并发（核心线程仅 2，强制线程复用），异步线程标识零串号，指标总数=请求总数 |
| `ErrorMappingIntegrationTest` | 嵌套异常在响应/指标一致、500 敏感信息不外泄、未知路径只进 UNMAPPED |

测试日志中可见 `corrId`、路由模板与 `errorClass`/`errorCategory`。

### 6.2 手工冒烟

```bash
mvn package -DskipTests
java -jar target/spring-observability-java-1.0-SNAPSHOT.jar --server.port=18080

# 沿用外部标识
curl -i -H 'X-Correlation-ID: my-trace-1' http://localhost:18080/api/items/42
# 缺失标识 → 自动生成（看响应头 X-Correlation-ID-Source）
curl -i http://localhost:18080/api/items/42
# 异步透传
curl -s -H 'X-Correlation-ID: async-trace-1' -X POST -d '{}' \
  -H 'Content-Type: application/json' http://localhost:18080/api/items/42/checkout
# 嵌套客户端异常 → 404 NOT_FOUND
curl -i 'http://localhost:18080/api/oops?kind=client'
# 嵌套服务端异常 → 500，响应体不含内部细节
curl -i http://localhost:18080/api/oops
# 未知路径 → 404，计入固定 UNMAPPED 桶
curl -i http://localhost:18080/some/random/path
# 指标快照（注意 /items/{itemId} 聚合了不同参数值，基数有界）
curl http://localhost:18080/internal/metrics
```

同时在应用控制台可按 `corrId` 串联 Tomcat 线程与 `obs-async-*` 工作线程的全部日志行。
