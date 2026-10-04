package com.github.highcumontoa.springobservabilityjava.tests;

import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.springobservabilityjava.observability.web.DemoController;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 层的在途请求 / 重置边界测试：
 * 一个业务请求在重置之前已经进入（停在控制器里），重置必须等它结束并把它计入被冻结的旧批次，
 * 新批次只包含重置之后才进入的业务请求。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InFlightRequestResetBoundaryIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(InFlightRequestResetBoundaryIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private long routeCountInView(JsonNode view, String route) {
        for (JsonNode routeNode : view.get("routes")) {
            if (routeNode.get("route").asText().equals(route)) {
                return routeNode.get("count").asLong();
            }
        }
        return 0L;
    }

    @Test
    void inFlightRequestEnteredBeforeResetIsCountedInFrozenBatchNotNewBatch() throws Exception {
        // 干净基线
        mockMvc.perform(post("/internal/metrics/reset")).andExpect(status().isOk());

        // 让慢请求进入后停在控制器中（已完成进入时的批次领取，归属当前代）
        DemoController.armSlowRequest();
        AtomicReference<MvcResult> slowResult = new AtomicReference<>();
        Thread slowClient = new Thread(() -> {
            try {
                slowResult.set(mockMvc.perform(get("/api/slow")
                                .header("X-Correlation-ID", "in-flight-across-reset"))
                        .andExpect(status().isOk()).andReturn());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "slow-client");
        slowClient.start();
        DemoController.awaitSlowRequestEntered();

        // 慢请求在途期间发起重置：重置必须阻塞等待该请求结束。把重置放到另一个线程，
        // 主线程随后放开慢请求；两者都完成后核对归属。
        AtomicReference<JsonNode> frozenView = new AtomicReference<>();
        Thread resetter = new Thread(() -> {
            try {
                MvcResult r = mockMvc.perform(post("/internal/metrics/reset"))
                        .andExpect(status().isOk()).andReturn();
                frozenView.set(JSON.readTree(r.getResponse().getContentAsString()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "resetter");
        resetter.start();

        // 给重置一点时间进入"排空在途请求"阶段，然后放开慢请求
        Thread.sleep(150L);
        assertTrue(slowClient.isAlive(), "slow request must still be in flight while reset is pending");
        DemoController.releaseSlowRequest();

        slowClient.join(10_000L);
        resetter.join(10_000L);
        assertEquals(Thread.State.TERMINATED, slowClient.getState());
        assertEquals(Thread.State.TERMINATED, resetter.getState());

        JsonNode frozen = frozenView.get();
        long frozenSlow = routeCountInView(frozen, "/api/slow");
        assertEquals(1L, frozenSlow,
                "request entered before reset (finished after) must be frozen in the old batch");

        // 放开后：新批次不得包含这个在途请求；重置之后再进入的业务请求才进入新批次
        MvcResult currentViewResult = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode current = JSON.readTree(currentViewResult.getResponse().getContentAsString());
        assertEquals(0L, routeCountInView(current, "/api/slow"),
                "new batch must not contain the request that entered before the reset");
        assertTrue(current.get("generation").asLong() == frozen.get("nextGeneration").asLong());

        mockMvc.perform(get("/api/slow").header("X-Correlation-ID", "after-reset-slow"))
                .andExpect(status().isOk());
        MvcResult afterViewResult = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode after = JSON.readTree(afterViewResult.getResponse().getContentAsString());
        assertEquals(1L, routeCountInView(after, "/api/slow"),
                "only requests entered after reset accumulate in the new batch");

        log.info("http in-flight boundary: in-flight request frozenCount={}, newBatchCountAfterRelease=0, "
                        + "postResetRequestCount=1, frozenGen={}, currentGen={}",
                frozenSlow, frozen.get("generation").asLong(), after.get("generation").asLong());
    }
}
