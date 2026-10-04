# Spring Boot 请求全链路可观测能力（关联标识 / 路由指标 / 耗时分布 / 异常归因 / 统计重置）

在 Spring Boot 3.4（Java 21）骨架上实现的一套进程内可观测能力，**不依赖任何外部服务或凭据**：
每个入站请求都有唯一关联标识并透传到异步线程；指标按路由模板聚合且基数有界；
每条路由额外提供固定档位的耗时分布；异常（含多层包装、含异步任务失败）被归一为稳定的错误类别，
并在日志、指标、HTTP 响应三处保持一致；统计可随时原子重置为一个全新批次，便于隔离压测/回归的这一批请求。

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

## 2. 指标维度、耗时分布与基数约束

- 端点：`GET /internal/metrics`（进程内快照，JSON，含当前批次号 `generation`）。
- **维度只有两个**：路由模板（如 `/api/items/{itemId}`）与错误分类（`NONE/CLIENT_ERROR/SERVER_ERROR`）。
  路径参数值（`/items/1`、`/items/2`）永远不会成为维度值，不同参数归入同一模板。
- **基数上界明确**：维度数 = 应用注册的路由模板数（编译期可见、有界）+ 一个固定桶，
  不随 URL/参数/用户输入增长。
- **无法归属路由的请求**（未匹配处理器、404）统一计入固定桶 `UNMAPPED`，
  既不丢失计数，也**绝不按原始 URI 新建维度**（该类请求不经过拦截器，由全局异常处理器补记）。
- **统计内容**：总请求数、按错误分类计数、总耗时、最大耗时（纳秒）、以及下面的**耗时分布**。
- **并发正确性**：计数使用 `LongAdder`（分段累加），最大耗时用 `AtomicLong` CAS 更新；
  注册表用 `ConcurrentHashMap#computeIfAbsent` 原子建桶。并发写入不丢失、不重复。

### 2.1 耗时分布（固定档位直方图）

每条路由额外维护一个**固定 8 档**的耗时直方图，用于定位慢请求占比。档位在编译期写死，
左开右闭（首档含 0），边界精确到纳秒：

| 档位 | 区间（d = 请求耗时） |
|---|---|
| `<=1ms` | `0 ≤ d ≤ 1ms` |
| `1ms-5ms` | `1ms < d ≤ 5ms` |
| `5ms-10ms` | `5ms < d ≤ 10ms` |
| `10ms-25ms` | `10ms < d ≤ 25ms` |
| `25ms-50ms` | `25ms < d ≤ 50ms` |
| `50ms-100ms` | `50ms < d ≤ 100ms` |
| `100ms-500ms` | `100ms < d ≤ 500ms` |
| `>500ms` | `d > 500ms` |

- **恰好落一档**：`DurationBucket.forNanos` 对任意非负耗时返回唯一档位（首个满足 `d ≤ 上界` 的档），
  因此并发下每个请求不多不少正好计入一个档位，**各档计数之和恒等于该路由总请求数**；
  分档计数与总计数、总耗时、最大值在同一次 `record` 内一起更新，彼此自洽。
- **内存 / 基数约束**：每条路由的内存开销 = 固定 8 个 `LongAdder`，与请求量、URL 数量无关；
  路由数本身仍有上界。不使用随观测值增长的分位数结构或样本蓄水池。
- **同一路由不被参数拆开**：档位计数挂在路由模板的 `RouteMetrics` 上，
  `/items/1` 与 `/items/2` 的分布合并在 `/api/items/{itemId}` 同一组档位里。
- **兜底请求也有真实耗时**：计时起点由最先执行的 `RequestTimingFilter`（过滤器层，先于路由匹配）
  盖在请求属性上，拦截器与全局异常处理器读同一处起点。因此未匹配路由、进入 `UNMAPPED`
  固定桶的请求同样统计真实耗时与档位，**不再出现耗时恒为 0 的情况**。
- 代码入口：
  - `observability/metrics/DurationBucket.java`
  - `observability/metrics/RequestTiming.java` / `RequestTimingFilter.java`
  - `observability/metrics/MetricsRegistry.java`
  - `observability/metrics/RouteMetrics.java`
  - `observability/metrics/RouteMetricsInterceptor.java`

### 2.2 统计重置（批次隔离，压测/回归用）

- 端点：`POST /internal/metrics/reset`。把统计起点**原子地**重置到当前时刻：
  返回被冻结的上一批次视图（`generation / startedAt / resetAt / 各路由数据`），
  随后的请求在序号 +1 的全新批次里从零累计；`GET /internal/metrics` 的 `generation`
  可用来确认"这一批"。
- **操作入口不自统计**：`/internal/**`（查看/重置统计）在拦截器配置层被排除，
  这两个操作本身不会计入任何批次——重置后立刻查看，新批次一定是干净的零，
  不会混进"重置"和"查看"这两个请求自身的记录。
- **批次归属按"进入时刻"判定**：请求进入时（最先执行的 `RequestTimingFilter`）
  就把所属批次钉在请求属性上，完成时计入该批次。因此：
  - 重置**之后**才进入的请求 → 只累计在新批次；
  - 重置**之前**已进入、但重置后才结束的在途请求 → **只留在被冻结的旧批次里**，
    无论它多久之后才结束，都不会混入新批次；
  - 重置返回之后，新一批只反映重置之后真正进入的业务请求。
- **冻结批次是实时视图**：旧批次对"新进入的请求"关闭，但会继续吸收重置前已进入、
  重置后才完成的在途请求（包括因迟到完成才首次建桶的路由）。因此任何一笔请求都
  恰好属于一个批次，不会出现"哪一批都没算上"的漏算。注意：重置接口的**响应体**
  在返回那一刻序列化，是重置时刻的切面；之后才结束的在途请求不可能出现在已发出的
  响应体里，但会留存在旧批次数据中（冻结视图后续读取可见）。
- **并发边界（不丢、不重、不混批）**：注册表内部以"代（generation）"保存计数器，
  重置 = 整体替换当前代；并用一把 `ReentrantReadWriteLock` 协调——
  归属判定（`currentBatch()`）与即时记录持共享读锁（请求之间仍完全并发），
  `reset` 持独占写锁。这保证归属判定**整体**地发生在重置之前或之后，
  不存在"重置前读到旧代、重置后才归属"的跨边界半截判定，因此：
  不丢计数、不重复计数、同一笔请求不会同时落到两批里；
  连续重置任意多次，批次号严格连续（0..N），结果可预期。
- 代码入口：
  - `observability/metrics/Generation.java`
  - `observability/metrics/RegistrySnapshot.java` / `RouteSnapshot.java`（冻结批次的实时视图）
  - `observability/metrics/MetricsRegistry.java`（`currentBatch()` / `reset()`）
  - `observability/metrics/RequestBatch.java`（进入时钉批次）
  - `observability/metrics/RequestTimingFilter.java` / `RouteMetricsInterceptor.java`
  - `observability/config/WebObservabilityConfig.java`（`/internal/**` 排除）
  - `observability/web/MetricsController.java`

- **取舍说明**：指标只在内存中保存、进程重启归零，目的是本地可复现验证；
  耗时分档为固定档位而非精确百分位，以严格保证内存有界。

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

### 3.1 异步失败的归因规则

请求触发的 `@Async` 任务一旦失败，**失败与成功明确区分，且日志、指标、响应状态三处同分类**：

1. 工作线程上的 `AsyncWorkService` 捕获到失败时，先在**工作线程**打一条 ERROR 日志
   （携带还原后的同一 `correlationId`、完整堆栈），再把异常通过 `CompletableFuture` 抛出；
2. 请求线程在 `future.join()` 处收到 `CompletionException`，交给 `GlobalExceptionHandler`；
   `ExceptionClassifier` 沿 `getCause()` 链解包（`CompletionException` 只是包装层），
   链上第一个可识别异常决定最终类别——异步深层包装的 404 仍归 `NOT_FOUND/CLIENT_ERROR/404`，
   内部失败归 `SERVER_OTHER/SERVER_ERROR/500`；
3. 因而：
   - **响应**：等待结果的请求直接收到该状态码与脱敏文案，响应头/体仍带同一关联标识；
   - **日志**：工作线程一条 ERROR（同 `corrId`、含堆栈）+ 处理器侧一条失败日志；
   - **指标**：该请求按最终状态计入 `CLIENT_ERROR`/`SERVER_ERROR`，**不会被算成成功**，
     同时按真实耗时落入一个耗时档位。
   - 内部异常 message（含密码、令牌）只进服务端日志，绝不回显给调用方。
- 代码入口：`observability/web/AsyncWorkService.java`、`observability/web/DemoController.java`
  （checkout 的 `asyncFail=client|server`）、复用第 3 节的 `ExceptionClassifier`/`GlobalExceptionHandler`。

## 4. 日志

日志格式（`logback-spring.xml`，测试使用 `src/test/resources/logback-test.xml`）固定携带：

```
时间 级别 [线程] [corrId=<关联标识>,src=<INBOUND|GENERATED>] logger - 消息
```

- 请求开始/结束、控制器、`@Async` 工作线程日志均带同一 `corrId`；
- 指标拦截器每个请求打印一行 `request observed: route=<模板>, status=, errorClass=, durationNanos=`，
  即"关联标识 + 路由模板 + 错误分类"同时出现在日志行中；
- 异步任务失败时，`obs-async-*` 工作线程会打 `async job failed` ERROR 行，携带同一 `corrId`。

## 5. 演示端点

| 方法/路径 | 说明 |
|---|---|
| `GET /api/items/{itemId}` | 正常请求；加 `?fail=1` 触发 400 |
| `GET /api/slow?millis=N` | 慢请求（睡眠 N 毫秒，上限 30s），用于复现"重置时仍有在途请求" |
| `GET /api/oops?kind=client` | 三层包装的 404（验证解包归因；含唯一敏感令牌验证脱敏） |
| `GET /api/oops?kind=conflict` | 409 |
| `GET /api/oops`（默认） | 三层包装的 500（含伪造敏感串，验证脱敏） |
| `POST /api/items/{itemId}/checkout` | 触发 `@Async`，验证异步标识透传 |
| `POST /api/items/{itemId}/checkout?asyncFail=client` | 异步任务深层包装 404（验证异步失败归因、失败不计成功） |
| `POST /api/items/{itemId}/checkout?asyncFail=server` | 异步任务内部失败 500（验证脱敏 + 工作线程 ERROR 日志） |
| `GET /internal/metrics` | 当前批次指标快照（含 `generation` 与 `durationHistogram`） |
| `POST /internal/metrics/reset` | 原子重置统计起点，返回冻结的上一批次 |
| 任意未注册路径 | 404 且计入 `UNMAPPED` 桶（含真实耗时） |

## 6. 本地验证方法

环境要求：JDK 21+、Maven 3.6+（依赖仅 spring-boot-starter-web/test，可离线构建）。

### 6.1 运行全部测试

```bash
mvn test
```

测试覆盖（共 32 个用例）：

| 测试类 | 覆盖点 |
|---|---|
| `ExceptionClassificationTest` | 深层包装解包、404/409/400 细类可区分、500 脱敏、cause 成环不死循环；唯一令牌证伪脱敏 |
| `RouteMetricsConcurrencyTest` | 16 线程并发写 32000 次计数精确相等；UNMAPPED 不产生新维度 |
| `DurationHistogramTest` | 固定 8 档边界唯一归属；各档之和 = 总数；并发下每请求恰好一档；总耗时/最大值自洽 |
| `CorrelationIdFilterTest` | 入站标识原样沿用、缺失生成、畸形替换；INBOUND/GENERATED 可区分 |
| `AsyncCorrelationTest` | 24 客户端 × 20 次真实 HTTP 并发（核心线程仅 2，强制线程复用），异步线程标识零串号，指标总数=请求总数 |
| `AsyncFailureAttributionTest` | 异步 client/server 失败归因一致、失败不计成功、成功仍计成功、同关联标识、内部细节不外泄 |
| `AsyncFailureLoggingTest` | 异步失败在 `obs-async-*` 工作线程打 ERROR 且携带同一关联标识 |
| `ErrorMappingIntegrationTest` | 嵌套异常响应/指标一致、唯一令牌证明脱敏生效、未知路径只进 UNMAPPED 且有真实耗时 |
| `MetricsResetTest` | 重置后批次独立从零累计、历史冻结不变；重置时在途请求只留旧批次、不进新批次（冻结视图吸收迟到完成）；即时记录与"进入时定归属"两种模式下，高并发交错重置均不丢不重不混批、批次号连续 |
| `MetricsResetEndpointTest` | 重置端点冻结上一批、当前批次归零、重置后请求只进新批；查看/重置操作自身不计入任何批次；门控在途请求跨重置不污染新批次 |

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
# 异步失败：404（深层包装），失败计入 CLIENT_ERROR 而不是成功
curl -i -X POST -H 'X-Correlation-ID: async-404' \
  'http://localhost:18080/api/items/42/checkout?asyncFail=client'
# 异步失败：500，响应体不含 password/token；工作线程日志有 async job failed + 同一 corrId
curl -i -X POST -H 'X-Correlation-ID: async-500' \
  'http://localhost:18080/api/items/42/checkout?asyncFail=server'
# 嵌套客户端异常 → 404 NOT_FOUND
curl -i 'http://localhost:18080/api/oops?kind=client'
# 嵌套服务端异常 → 500，响应体不含内部细节
curl -i http://localhost:18080/api/oops
# 未知路径 → 404，计入固定 UNMAPPED 桶（带真实耗时）
curl -i http://localhost:18080/some/random/path
# 指标快照（generation + 每路由 durationHistogram；不同参数聚合在同一模板）
curl http://localhost:18080/internal/metrics
# 压测前重置：返回冻结的上一批，随后的请求进入 generation+1 的新批次
curl -s -X POST http://localhost:18080/internal/metrics/reset
# 再来一批请求后重新查看，确认只有重置后的计数
curl -H 'X-Correlation-ID: bench-1' http://localhost:18080/api/items/1
curl http://localhost:18080/internal/metrics
```

### 6.3 核对重置行为（操作入口不自统计 / 在途请求归属）

```bash
# 1) 操作入口不自统计：重置后立刻查看，新批次所有路由计数都是 0，
#    不会出现 /internal/metrics、/internal/metrics/reset 自身的记录
curl -s -X POST http://localhost:18080/internal/metrics/reset > /dev/null
curl -s http://localhost:18080/internal/metrics
#   → routes 里各项 count 全为 0（且没有任何 /internal/* 路由）

# 2) 在途请求只留旧批次：后台发一个 5 秒的慢请求，趁它没结束时重置
curl -s -H 'X-Correlation-ID: slow-1' 'http://localhost:18080/api/slow?millis=5000' &
sleep 1                                        # 确保慢请求已"进入"
curl -s -X POST http://localhost:18080/internal/metrics/reset   # 冻结旧批次
sleep 5                                        # 等慢请求在重置之后才结束
curl -s http://localhost:18080/internal/metrics
#   → 新批次里没有 /api/slow（它归属重置前的旧批次，不计入新一批）
curl -s -H 'X-Correlation-ID: bench-2' http://localhost:18080/api/items/2
curl -s http://localhost:18080/internal/metrics
#   → 新批次只有重置之后才进入的 /api/items/{itemId}，count=1
```

同时在应用控制台可按 `corrId` 串联 Tomcat 线程与 `obs-async-*` 工作线程的全部日志行。
