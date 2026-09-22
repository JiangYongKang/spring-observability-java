package com.github.highcumontoa.springobservabilityjava.tests;

import com.github.highcumontoa.springobservabilityjava.observability.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入站关联标识过滤器测试：原样沿用 vs 明确替换，两种来源可区分；响应头回写。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilterTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void inboundCorrelationIdIsPreservedAsIs() throws Exception {
        String inbound = "req-abc-12345";
        MvcResult result = mockMvc.perform(get("/api/items/7")
                        .header(CorrelationIdFilter.HEADER_NAME, inbound))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(inbound, result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME));
        assertEquals("INBOUND", result.getResponse().getHeader(CorrelationIdFilter.SOURCE_HEADER_NAME));
        assertTrue(result.getResponse().getContentAsString().contains(inbound));
        log.info("inbound preserved check: route=/api/items/{{itemId}}, errorClass=NONE, correlationId={}",
                inbound);
    }

    @Test
    void missingCorrelationIdIsGeneratedAndMarked() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/items/8"))
                .andExpect(status().isOk())
                .andReturn();

        String generated = result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(generated);
        assertTrue(generated.length() >= 16, "generated id should be a uuid-like value");
        assertEquals("GENERATED", result.getResponse().getHeader(CorrelationIdFilter.SOURCE_HEADER_NAME));
        log.info("generated check: route=/api/items/{{itemId}}, errorClass=NONE, correlationId={}",
                generated);
    }

    @Test
    void malformedCorrelationIdIsRejectedAndReplaced() throws Exception {
        String malformed = "bad id with spaces\r\nX-Injected: 1";
        MvcResult result = mockMvc.perform(get("/api/items/9")
                        .header(CorrelationIdFilter.HEADER_NAME, malformed))
                .andExpect(status().isOk())
                .andReturn();

        String replaced = result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(replaced);
        assertEquals("GENERATED", result.getResponse().getHeader(CorrelationIdFilter.SOURCE_HEADER_NAME));
        assertTrue(!replaced.equals(malformed), "malformed value must not be reused");
        assertTrue(!replaced.contains(" "), "replacement must be a clean uuid");
        log.info("rejected check: route=/api/items/{{itemId}}, errorClass=NONE, correlationId={}",
                replaced);
    }
}
