package com.plantarena.shared.web;

import com.plantarena.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Ошибки API возвращаются в виде ProblemDetail с traceId")
class ApiErrorHandlingIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void неизвестный_путь_даёт_404_с_телом_ProblemDetail_и_traceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/no-such-resource"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Not Found"))
            .andReturn();

        String traceIdHeader = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(traceIdHeader).isNotBlank();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .contains("\"code\":\"RESOURCE_NOT_FOUND\"")
            .contains("\"traceId\":\"" + traceIdHeader + "\"");
    }
}
