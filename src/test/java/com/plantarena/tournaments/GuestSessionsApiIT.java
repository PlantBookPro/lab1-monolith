package com.plantarena.tournaments;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.support.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 8: гостевые сессии (раздел 9) через HTTP.
 * Лимит выдачи занижен до 3/мин; фиксированное окно 1 минута — тесты
 * класса укладываются в одно окно, цикл добивает до 429 независимо от
 * порядка методов. Красный до Task 4 (ручки /guest-sessions нет).
 */
@TestPropertySource(properties = {
    "plantarena.guests.session-creation-limit-per-minute=3"
})
@DisplayName("Сценарии раздела 9: гостевые сессии — токен один раз, лимит 429")
class GuestSessionsApiIT extends AbstractIntegrationTest {

    private static final String GUEST_TOKEN_HEADER = "X-Guest-Token";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("создание: публично, 201 с токеном и expiresAt; токены непредсказуемы (разные)")
    void создание_сессии() throws Exception {
        String body1 = createSession();
        String body2 = createSession();

        String token1 = JsonPath.read(body1, "$.token");
        String token2 = JsonPath.read(body2, "$.token");
        assertThat(token1).isNotBlank();
        assertThat(token2).isNotBlank().isNotEqualTo(token1);
        assertThat(Instant.parse(JsonPath.read(body1, "$.expiresAt")))
            .isAfter(Instant.now());
    }

    @Test
    @DisplayName("лимит выдачи с одного IP: 429 RATE_LIMITED + Retry-After (раздел 9)")
    void лимит_выдачи() throws Exception {
        boolean limited = false;
        for (int i = 0; i < 6 && !limited; i++) {
            MvcResult result = mockMvc.perform(post("/api/v1/guest-sessions")).andReturn();
            int status = result.getResponse().getStatus();
            if (status == 201) {
                continue;
            }
            if (status == 429) {
                limited = true;
                assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();
                String code = JsonPath.read(result.getResponse().getContentAsString(), "$.code");
                assertThat(code).isEqualTo("RATE_LIMITED");
            }
        }
        assertThat(limited)
            .as("превышение лимита выдачи должно давать 429 с Retry-After")
            .isTrue();
    }

    private String createSession() throws Exception {
        return mockMvc.perform(post("/api/v1/guest-sessions"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }
}
