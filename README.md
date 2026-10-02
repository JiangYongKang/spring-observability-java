# Spring Boot 请求全链路可观测能力（关联标识 / 路由指标 / 耗时分布 / 异常归因 / 统计重置）

在 Spring Boot 3.4（Java 21）骨架上实现的一套进程内可观测能力，**不依赖任何外部服务或凭据**：
每个入站请求都有唯一关联标识并透传到异步线程（含异步失败）；指标按路由模板聚合且基数有界，
并提供**固定档位的耗时分布**用于定位慢请求；异常（含多层包装）被归一为稳定的错误类别，
在日志、指标、HTTP 响应三处保持一致；指标可把统计起点**重置到一个明确时刻**，
用于压测/回归时按"一批"核对，且重置与并发在途请求互不污染。

## 1. 关联标识的生成与透传规则

- **请求头**：`X-Correlation-ID`。
- **沿用（情况一）**：请求头存在，且值匹配正则 `[!-~]{1,128}`
  （1~128 个可打印 ASCII 字符、不含空白）时**原样沿用**，来源记为 `INBOUND`。
- **替换（情况二）**：请求头缺失，或为空、超长、含空白/控制字符（存在 HTTP 头注入风险）时，
  服务端生成随机 UUID 替换，来源记为 `GENERATED`。畸形值本身不写入日志，只记录"被拒绝"这一事实。
- **两种情况如何区分**：响应头 `X-Correlation-ID` 与 `X-Correlation-ID-Source`；
  日志 MDC 字段 `correlationId` 与 `correlationSource`；错误响应体中的 `correlationId` 字段。
- **同步链路**：`CorrelationIdFilter`（最高优先级）在请求开始时把标识放入 ThreadLocal + MDC，
  请求结束后恢复线程进入前的状态。
- **异步链路**：业务线程池 `observabilityTaskExecutor` 装配了 `CorrelationTaskDecorator`：
  在**任务提交时**捕获标识快照、在**工作线程执行时**还原、执行完毕恢复现场。
  刻意**不使用 `InheritableThreadLocal`**——池化线程只在创建时继承一次，线程复用会导致串号。
- 代码入口：`observability/correlation/`、`observability/config/AsyncConfig.java`。

## 2. 指标维度、耗时分布与基数/内存约束

- 端点：`GET /internal/metrics`（进程内快照，JSON）；`POST /internal/metrics/reset`（重置，见第 5 节）。
- **维度只有**：路由模板（如 `/api/items/{itemId}`）、错误分类（`NONE/CLIENT_ERROR/SERVER_ERROR`）、
  固定耗时档位（见下）。路径参数值（`/items/1`、`/items/2`）永远不会成为维度值，不同参数归入同一模板。
- **基数上界明确**：维度数 = 应用注册的路由模板数（编译期可见、有界）+ 一个固定桶，
  不随 URL/参数/用户输入增长。
- **无法归属路由的请求**（未匹配处理器、404）统一计入固定桶 `UNMAPPED`，既不丢失计数，
  也**绝不按原始 URI 新建维度**；其耗时由最外层 `MetricsObservationFilter` 用**真实耗时**记录
  （计时起点在过滤器，覆盖"拦截器链不执行"的未匹配请求），不会出现该桶耗时恒为 0。

### 2.1 耗时分布（固定档位直方图）

每条路由额外维护 8 个**写死的固定档位**（`LatencyBucket`，单位纳秒，左闭右开，末档上不封顶）：

| 档位 | 区间 | 含义 |
|---|---|---|
| `LT_1MS` | [0, 1ms) | 极快 |
| `LT_5MS` | [1ms, 5ms) | |
| `LT_10MS` | [5ms, 10ms) | |
| `LT_25MS` | [10ms, 25ms) | |
| `LT_50MS` | [25ms, 50ms) | |
| `LT_100MS` | [50ms, 100ms) | |
| `LT_1000MS` | [100ms, 1s) | 偏慢 |
| `GE_1000MS` | ≥ 1s | 慢请求长尾 |

> 完整区间以代码常量为准（`LatencyBucket`），相邻档位首尾相接、互不重叠且覆盖 `[0,+∞)`，
> 因此**每个非负耗时恰好落入一个档位**。慢请求占比直接看 `GE_1000MS` / `count`。

- **每请求恰好落一档**：`record` 在同一次调用里递增总数、错误分类计数、唯一命中的档位计数，
  累加总耗时、CAS 更新最大值。因此**所有档位计数之和恒等于总请求数**；
  总耗时 = 各请求耗时之和；最大值 = 各请求耗时最大值（并发测试逐项校验自洽）。
- **内存/基数约束**：档位数量编译期固定（8 个），与请求量、URL 数量无关。
  总内存为 `O(路由模板数 × (错误分类数 + 固定档位数))`，全部是定长计数器
  （`LongAdder` 分段累加 + `AtomicLong` 最大值），**不保存任何单次请求样本**，无直方图桶扩张、
  无百分位估算结构，进程内存有明确上界。
- 代码入口：`observability/metrics/LatencyBucket.java`、`RouteMetrics.java`、
  `MetricsObservationFilter.java`、`RouteMetricsInterceptor.java`。

## 3. 错误分类规则与三处一致

归因沿 `getCause()` 链**深度优先解包**（最多 25 层，带 IdentityHashMap 成环保护），
链上第一个可识别异常决定类别；都不可识别时按服务端错误处理。

| 场景 | errorCategory（细类，日志/响应体） | errorClass（粗类，指标维度） | HTTP 状态 | 对外 reason |
|---|---|---|---|---|
| 成功 | — | `NONE` | 2xx | — |
| 参数校验/非法参数、请求体不可读 | `VALIDATION` | `CLIENT_ERROR` | 400 | 固定脱敏文案 |
| 资源不存在（含未匹配路由） | `NOT_FOUND` | `CLIENT_ERROR` | 404 | `resource not found` |
| 状态冲突 | `CONFLICT` | `CLIENT_ERROR` | 409 | `request conflicts with current state` |
| 其他 4xx | `CLIENT_OTHER` | `CLIENT_ERROR` | 对应状态 | `bad request` |
| 下游依赖不可用（503） | `DEPENDENCY` | `SERVER_ERROR` | 503 | `internal server error` |
| 其余未捕获异常 | `SERVER_OTHER` | `SERVER_ERROR` | 500 | `internal server error` |

- **三处一致的保证方式**：异常处理器用分类结果设置 HTTP 响应状态；指标观测层在请求结束时
  以**最终响应状态码**反推 `errorClass`。因此异常是否被包装、是否被处理器吞掉改写，
  日志的 `errorCategory`、指标的 `errorClass`、调用方看到的状态码始终描述同一件事。
- **内部细节不外泄**：5xx 响应体只含 `correlationId / errorCategory / 固定脱敏文案`；
  异常类型、原始 message、堆栈只写服务端日志（5xx 打完整堆栈，4xx 告警也附原始异常）。

## 4. 异步失败的归因规则

请求触发的 `@Async` 任务成功与失败被明确区分，且失败与同步请求共用同一套 `ExceptionClassifier`：

1. 异步任务在**工作线程**上失败时，先在工作线程按归一结果打结构化日志
   （`async job failed: errorCategory=, httpStatus=`，携带同一 `correlationId` 与完整异常堆栈），
   再把异常透传给返回的 `CompletableFuture`；
2. 若请求线程 `join()` 等到了结果，异常在请求线程被 `GlobalExceptionHandler` 按**同一分类**
   转成对外状态码与脱敏文案——`join` 抛出的 `CompletionException` 会沿 cause 链解包到内部异常；
3. 指标观测层按最终响应状态归类，因此**异步失败如实计入 `SERVER_ERROR`，不会被算成成功**；
   成功计入 `NONE`，两者在聚合视图中分别可查；
4. 工作线程上的关联标识由任务装饰器还原，日志中同步线程与 `obs-async-*` 线程可按同一
   `correlationId` 串联；内部异常细节（如伪造的 `dbToken`）只在服务端日志，不进响应体。
5. 演示：`POST /api/items/{itemId}/checkout?fail=true` 触发异步失败（默认无参为成功）。

## 5. 统计重置的语义与并发边界

`POST /internal/metrics/reset` 把统计起点重置到调用时刻，返回新批次信息
`{ "generation": <序号>, "startedAt": <ISO 时刻> }`。

**实现模型（只增代次 + 在途钉取）：**

- 注册表内部是一个**只增不改的代次列表**。每个请求在进入观测时（最外层过滤器、与 reset
  共用同一把生命周期锁）**钉住当时的最新代次**并把该代次"在途计数 +1"；请求记录只写被钉住
  的那个代次，结束时在途计数 -1。
- `reset()` 在锁内原子追加一个从零开始的新代次，然后等待所有老代次的在途请求结清
  （等待集合在持锁时快照；此后新请求只会进入新代次，老代次在途计数不可能再增长）。

**由此得到的边界保证：**

- 重置**之前**已进入的请求永远写老代次，重置**之后**进入的请求写新代次——两批在结构上不可能
  混在一起（不是事后从合计里扣除）；
- 不丢计数：钉取与切代次在同一把锁上全序，任何已 +1 的代次对象只增不删，迟到记录不会成孤儿；
- 不重复计数：一次请求从头到尾只持有一个代次引用，只写一次；
- `reset` 返回时，发布新代次前进入的在途请求已全部结清，故返回后看到的快照**严格只含**
  重置之后进入的请求；老批次冻结保留；
- 代次数 = 重置次数 + 1，由运维动作决定、有界，不随请求量或 URL 增长；
- `/internal/metrics` 与 `/internal/metrics/**` 不参与自身观测（既不进指标，也避免了
  "重置请求等待自己结零"的自锁）。

## 6. 日志

日志格式（`logback-spring.xml`，测试使用 `src/test/resources/logback-test.xml`）固定携带：

```
时间 级别 [线程] [corrId=<关联标识>,src=<INBOUND|GENERATED>] logger - 消息
```

- 请求开始/结束、控制器、`@Async` 工作线程（成功与失败）日志均带同一 `corrId`；
- 观测层每个请求打印一行 `request observed: route=<模板>, status=, errorClass=, durationNanos=`。

## 7. 演示端点

| 方法/路径 | 说明 |
|---|---|
| `GET /api/items/{itemId}` | 正常请求；加 `?fail=1` 触发 400 |
| `GET /api/oops?kind=client` | 三层包装的 404（验证解包归因） |
| `GET /api/oops?kind=conflict` | 409 |
| `GET /api/oops`（默认） | 三层包装的 500（含伪造敏感串，验证脱敏） |
| `POST /api/items/{itemId}/checkout` | 触发 `@Async`，验证异步标识透传 |
| `POST /api/items/{itemId}/checkout?fail=true` | 触发异步失败：日志/指标/响应三处一致、脱敏 |
| `GET /internal/metrics` | 当前批次指标快照（含耗时分档） |
| `POST /internal/metrics/reset` | 重置统计起点，返回新批次序号与起点时刻 |
| 任意未注册路径 | 404 且以真实耗时计入 `UNMAPPED` 桶 |

## 8. 本地验证方法

环境要求：JDK 21+、Maven 3.6+（依赖仅 spring-boot-starter-web/test）。

### 8.1 运行全部测试

```bash
mvn test
```

共 25 个用例：

| 测试类 | 覆盖点 |
|---|---|
| `ExceptionClassificationTest` | 深层包装解包、细类可区分、500 脱敏（**先断言敏感串确实在输入中，再断言不外泄**）、cause 成环 |
| `LatencyHistogramTest` | 分档区间不重不漏/边界归属唯一；8 线程 × 2500 并发：每请求恰好落一档、各档之和=总数、总耗时与最大值自洽 |
| `RouteMetricsConcurrencyTest` | 16 线程并发写计数精确相等；UNMAPPED 不产生新维度 |
| `MetricsResetTest` | 重置后从零且历史隔离；在途请求不被移入新批次；12 线程 × 1000 请求与 20 次重置并发下全局守恒（不丢不重不串批） |
| `MetricsResetIntegrationTest` | HTTP 重置后快照从零、历史不混入、新批次独立累计、分档之和=总数 |
| `CorrelationIdFilterTest` | 入站标识原样沿用、缺失生成、畸形替换；INBOUND/GENERATED 可区分 |
| `AsyncCorrelationTest` | 24 客户端 × 20 次真实 HTTP 并发，异步线程标识零串号，指标总数=请求总数 |
| `AsyncFailureClassificationTest` | 异步失败在工作线程日志/指标/响应三处分类一致、关联标识相同；失败计入 SERVER_ERROR 与成功可区分；敏感串只在服务端日志 |
| `ErrorMappingIntegrationTest` | 嵌套异常响应/指标一致；脱敏（**敏感串先证明出现在服务端异常日志，再证明不进响应**）；未知路径只进 UNMAPPED 且真实耗时 > 0 |

### 8.2 手工冒烟

```bash
mvn package -DskipTests
java -jar target/spring-observability-java-1.0-SNAPSHOT.jar --server.port=18080

curl -i -H 'X-Correlation-ID: my-trace-1' http://localhost:18080/api/items/42
curl -i http://localhost:18080/api/items/42
# 异步成功 / 异步失败（失败返回 500 固定脱敏文案，控制台工作线程日志含同一 corrId 与完整堆栈）
curl -s -H 'X-Correlation-ID: async-ok-1' -X POST -d '{}' \
  -H 'Content-Type: application/json' http://localhost:18080/api/items/42/checkout
curl -i -H 'X-Correlation-ID: async-bad-1' -X POST \
  'http://localhost:18080/api/items/42/checkout?fail=true'
# 嵌套异常
curl -i 'http://localhost:18080/api/oops?kind=client'
curl -i http://localhost:18080/api/oops
# 未知路径（真实耗时计入 UNMAPPED）
curl -i http://localhost:18080/some/random/path
# 压测前重置到一个干净批次
curl -s -X POST http://localhost:18080/internal/metrics/reset
# ... 对系统施压 ...
# 查看这一批：每条路由含 success/clientError/serverError、totalDurationNanos、
# maxDurationNanos 与 latencyBuckets（慢请求看 GE_1000MS 占比）
curl http://localhost:18080/internal/metrics
```

控制台可按 `corrId` 串联 Tomcat 线程与 `obs-async-*` 工作线程（含异步失败堆栈）的全部日志行。
