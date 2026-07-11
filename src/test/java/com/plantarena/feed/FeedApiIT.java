package com.plantarena.feed;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 8: лента (раздел 9) через HTTP на Testcontainers.
 * Классификатор детерминированный (зелёный PNG → APPROVED). Тайминги
 * глобального режима длинные (PT2M) — окна не закрываются посреди тестов;
 * закрытие окна проверяется отдельным сценарием на закрытом турнире.
 * Каждый тест строит свой мир (свежие пользователи/почта).
 *
 * <p>Важно (допущение 4): участник закрытого турнира не имеет активной
 * глобальной заявки — одно изображение (green-8x8.png) не участвует в двух
 * турнирах одновременно, поэтому глобальные и закрытые участники не
 * пересекаются.
 */
@TestPropertySource(properties = {
    "plantarena.global.epoch-duration=PT2M",
    "plantarena.global.final-window-duration=PT2M",
    "plantarena.geo.geohash-precision=4"
})
@DisplayName("Сценарии раздела 9: лента — смешение, права, keyset, гости")
class FeedApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";
    private static final String GUEST_TOKEN_HEADER = "X-Guest-Token";
    private static final double LAT_MOSCOW = 55.7558;
    private static final double LON_MOSCOW = 37.6173;
    private static final double LAT_SPB = 59.9375;
    private static final double LON_SPB = 30.3086;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class DeterministicClassifierConfig {

        @Bean
        @Primary
        PlantClassifier deterministicPlantClassifier() {
            return new DeterministicPlantClassifier();
        }
    }

    /**
     * Свежий мир на каждый тест (раздел 9): глобальный турнир — синглтон, и
     * открытая эпоха (PT2M) не даст следующему тесту открыть свою — чистим
     * мир турниров/проекции целиком, как контрактные IT (урок итерации 2).
     */
    @BeforeEach
    void очистить_мир_турниров() {
        jdbcTemplate.update("delete from feed.feed_card");
        jdbcTemplate.update("delete from tournaments.vote");
        jdbcTemplate.update("delete from tournaments.window_participant");
        jdbcTemplate.update("delete from tournaments.voting_window");
        jdbcTemplate.update("delete from geo.cluster_member");
        jdbcTemplate.update("delete from geo.cluster_snapshot");
        jdbcTemplate.update("delete from tournaments.qualification_epoch");
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        // глобальный турнир — синглтон (bootstrap): строка остаётся, мир вокруг чист
        jdbcTemplate.update("delete from tournaments.tournament where id <> '"
            + "00000007-10ba-4000-8000-000000000001'");
        jdbcTemplate.update("delete from tournaments.tag");
    }

    @Test
    @DisplayName("лента пользователя смешивает глобальные и закрытый турнир; гость и посторонний — только глобальные; без total")
    void лента_смешивает_и_фильтрует_по_правам() throws Exception {
        // глобальный турнир: u2 (Москва) и u3 (СПб) — два кластера, два окна
        UUID u2 = newUser("mix-u2@example.com", "Mix2");
        UUID u3 = newUser("mix-u3@example.com", "Mix3");
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u3, LAT_SPB, LON_SPB);
        UUID p2 = approvedPlantOf(u2, "Фикус u2");
        UUID p3 = approvedPlantOf(u3, "Фикус u3");
        UUID e2 = submitGlobalEntry(u2, p2);
        UUID e3 = submitGlobalEntry(u3, p3);
        awaitGlobalCards(2);

        // закрытый турнир: u1 + u4 (без глобальных заявок — допущение 4)
        UUID u1 = newUser("mix-u1@example.com", "Mix1");
        UUID u4 = newUser("mix-u4@example.com", "Mix4");
        UUID p1 = approvedPlantOf(u1, "Фикус u1");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID tournamentId = createPrivateTournament(600);
        invite(tournamentId, u1);
        invite(tournamentId, u4);
        acceptInvitation(u1, p1);
        acceptInvitation(u4, p4);
        awaitRunning(tournamentId);

        // u1 — участник закрытого: глобальные карточки u2/u3 + закрытая u4; своих нет
        String feed = feedOf(u1);
        assertThat(entryIds(feed)).hasSize(3);
        assertThat(entryIds(feed)).doesNotContain(entryIdOfTournament(tournamentId, u1).toString());
        assertThat(entryIds(feed)).contains(e2.toString(), e3.toString(),
            entryIdOfTournament(tournamentId, u4).toString());

        // форма карточки (раздел 9): windowId, scope, ids, title, image URL, публичный владелец, closesAt
        List<Map<String, Object>> qualification = JsonPath.read(feed,
            "$.items[?(@.scope == 'QUALIFICATION')]");
        assertThat(qualification).hasSize(2);
        Map<String, Object> card = qualification.get(0);
        assertThat(card).containsKeys("windowId", "tournamentId", "entryId", "plantId",
            "title", "imageUrl", "owner", "closesAt");
        assertThat((String) card.get("imageUrl")).startsWith("/api/v1/files/");
        assertThat((Map<String, Object>) card.get("owner")).containsKeys("userId", "displayName");

        // посторонний (не участник закрытого) — только глобальные
        UUID stranger = newUser("mix-stranger@example.com", "Stranger");
        String strangerFeed = feedOf(stranger);
        assertThat(entryIds(strangerFeed)).containsExactlyInAnyOrder(e2.toString(), e3.toString());

        // гость — только глобальные, без total (раздел 13)
        String token = createGuestSession();
        String guestFeed = mockMvc.perform(get("/api/v1/feed")
                .header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Total-Count"))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertThat(entryIds(guestFeed)).containsExactlyInAnyOrder(e2.toString(), e3.toString());
    }

    @Test
    @DisplayName("голосование гостя: глобальные окна LIKE/смена/удаление; закрытое окно — 404; без токена — 401; USER при обоих заголовках")
    void голосование_гостя() throws Exception {
        UUID u1 = newUser("gvote-u1@example.com", "GVote1");
        UUID u2 = newUser("gvote-u2@example.com", "GVote2");
        UUID stranger = newUser("gvote-stranger@example.com", "Stranger");
        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(stranger, LAT_MOSCOW, LON_MOSCOW);
        UUID p1 = approvedPlantOf(u1, "Фикус u1");
        UUID p2 = approvedPlantOf(u2, "Фикус u2");
        UUID e1 = submitGlobalEntry(u1, p1);
        UUID e2 = submitGlobalEntry(u2, p2);
        awaitGlobalCards(2);
        UUID windowId = UUID.fromString(JsonPath.read(feedOf(u1), "$.items[0].windowId"));

        String token = createGuestSession();
        String votePath = "/api/v1/windows/" + windowId + "/entries/" + e2 + "/vote";
        String myVotePath = "/api/v1/windows/" + windowId + "/entries/" + e2 + "/my-vote";

        // LIKE → 1; смена на DISLIKE → −1 (дельта −2); удаление → 0 (раздел 9)
        mockMvc.perform(put(votePath).header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1));
        mockMvc.perform(get(myVotePath).header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("LIKE"));
        mockMvc.perform(put(votePath).header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"DISLIKE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(-1));
        mockMvc.perform(delete(votePath).header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isNoContent());
        mockMvc.perform(get(myVotePath).header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(nullValue()));

        // без токена и с неизвестным токеном — 401
        mockMvc.perform(put(votePath)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put(votePath).header(GUEST_TOKEN_HEADER, "not-a-real-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isUnauthorized());

        // идентифицированный пользователь при обоих заголовках — субъект USER (раздел 9)
        mockMvc.perform(put(votePath)
                .header(DEMO_HEADER, stranger.toString())
                .header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1));
        mockMvc.perform(get(myVotePath).header(DEMO_HEADER, stranger.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("LIKE"));

        // закрытое окно для гостя — 404 (турнир скрыт, раздел 13)
        UUID u3 = newUser("gvote-u3@example.com", "GVote3");
        UUID u4 = newUser("gvote-u4@example.com", "GVote4");
        UUID p3 = approvedPlantOf(u3, "Фикус u3");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID privateTournament = createPrivateTournament(600);
        invite(privateTournament, u3);
        invite(privateTournament, u4);
        acceptInvitation(u3, p3);
        acceptInvitation(u4, p4);
        awaitRunning(privateTournament);
        UUID e4 = entryIdOfTournament(privateTournament, u4);
        String privateFeed = feedOf(u3);
        UUID privateWindowId = windowIdOf(privateFeed, u4);
        mockMvc.perform(put("/api/v1/windows/" + privateWindowId + "/entries/" + e4 + "/vote")
                .header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TOURNAMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("keyset-пагинация: limit+1 → hasNext/nextCursor, страницы не пересекаются; limit вне 1–50 — 400")
    void пагинация_keyset() throws Exception {
        UUID u1 = newUser("page-u1@example.com", "Page1");
        UUID u2 = newUser("page-u2@example.com", "Page2");
        UUID u3 = newUser("page-u3@example.com", "Page3");
        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u3, LAT_SPB, LON_SPB);
        // растения пре-апрувятся до подач: иначе шедулер границ (fixedDelay 2с)
        // откроет эпоху между подачами — и оставшиеся заявки застрянут в QUEUED
        UUID p1 = approvedPlantOf(u1, "Фикус u1");
        UUID p2 = approvedPlantOf(u2, "Фикус u2");
        UUID p3 = approvedPlantOf(u3, "Фикус u3");
        submitGlobalEntry(u1, p1);
        submitGlobalEntry(u2, p2);
        submitGlobalEntry(u3, p3);
        awaitGlobalCards(3);

        // u4 — участник закрытого (u4+u5): 4-я карточка
        UUID u4 = newUser("page-u4@example.com", "Page4");
        UUID u5 = newUser("page-u5@example.com", "Page5");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID p5 = approvedPlantOf(u5, "Фикус u5");
        UUID tournamentId = createPrivateTournament(600);
        invite(tournamentId, u4);
        invite(tournamentId, u5);
        acceptInvitation(u4, p4);
        acceptInvitation(u5, p5);
        awaitRunning(tournamentId);

        String page1 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        String page2 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString())
                .queryParam("limit", "2").queryParam("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        Set<String> union = Set.copyOf(entryIds(page1));
        Set<String> union2 = Set.copyOf(entryIds(page2));
        assertThat(union).hasSize(2);
        assertThat(union2).hasSize(2);
        assertThat(union.stream().filter(union2::contains)).isEmpty(); // без дублей и пропусков
        assertThat(union.size() + union2.size()).isEqualTo(4);

        // limit вне 1–50 — 400 (раздел 13)
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "0"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "51"))
            .andExpect(status().isBadRequest());

        // невалидный курсор — 400
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("cursor", "garbage"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FEED_CURSOR_INVALID"));

        // курсор u4 не даёт прав на ленту u5 (раздел 9)
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u5.toString()).queryParam("cursor", cursor))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FEED_CURSOR_INVALID"));
    }

    @Test
    @DisplayName("оцененные субъектом карточки не предлагаются; новые после snapshotCutoff — после обновления ленты")
    void оцененные_и_новые_после_cutoff() throws Exception {
        UUID u1 = newUser("cut-u1@example.com", "Cut1");
        UUID u2 = newUser("cut-u2@example.com", "Cut2");
        UUID u3 = newUser("cut-u3@example.com", "Cut3");
        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u3, LAT_SPB, LON_SPB);
        // растения пре-апрувятся до подач: иначе шедулер границ (fixedDelay 2с)
        // откроет эпоху между подачами — и оставшиеся заявки застрянут в QUEUED
        UUID p1 = approvedPlantOf(u1, "Фикус u1");
        UUID p2 = approvedPlantOf(u2, "Фикус u2");
        UUID p3 = approvedPlantOf(u3, "Фикус u3");
        UUID e1 = submitGlobalEntry(u1, p1);
        UUID e2 = submitGlobalEntry(u2, p2);
        UUID e3 = submitGlobalEntry(u3, p3);
        awaitGlobalCards(3);

        UUID u4 = newUser("cut-u4@example.com", "Cut4");
        // u4 голосует за e2 — карточка исчезает из его ленты (раздел 9);
        // окно ищем по владельцу u2: порядок карточек псевдослучайный
        UUID windowId = windowIdOf(feedOf(u4), u2);
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + e2 + "/vote")
                .header(DEMO_HEADER, u4.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk());
        Set<String> before = Set.copyOf(entryIds(feedOf(u4))); // {e1, e3}
        assertThat(before).containsExactlyInAnyOrder(e1.toString(), e3.toString());

        // страница 1 фиксирует snapshotCutoff
        String page1 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        // новый закрытый турнир ПОСЛЕ cutoff: u4 + u5
        UUID u5 = newUser("cut-u5@example.com", "Cut5");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID p5 = approvedPlantOf(u5, "Фикус u5");
        UUID tournamentId = createPrivateTournament(600);
        invite(tournamentId, u4);
        invite(tournamentId, u5);
        acceptInvitation(u4, p4);
        acceptInvitation(u5, p5);
        awaitRunning(tournamentId);

        // продолжение курсора: только карточки до cutoff, без новых
        String page2 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andReturn().getResponse().getContentAsString();
        Set<String> continuation = new java.util.HashSet<>(entryIds(page1));
        continuation.addAll(entryIds(page2));
        assertThat(continuation).isEqualTo(before); // новые не появились, старые не потеряны

        // свежая лента содержит новую карточку u5 (после обновления ленты)
        String fresh = feedOf(u4);
        assertThat(entryIds(fresh)).hasSize(3);
        assertThat(entryIds(fresh)).contains(entryIdOfTournament(tournamentId, u5).toString());
    }

    @Test
    @DisplayName("закрытие окна убирает карточки: раунд закрыт — карточек турнира нет, проигравший погиб")
    void закрытие_окна_убирает_карточки() throws Exception {
        UUID u6 = newUser("close-u6@example.com", "Close6");
        UUID u7 = newUser("close-u7@example.com", "Close7");
        UUID p6 = approvedPlantOf(u6, "Фикус u6");
        UUID p7 = approvedPlantOf(u7, "Фикус u7");
        UUID tournamentId = createPrivateTournament(3); // раунд 3 секунды
        invite(tournamentId, u6);
        invite(tournamentId, u7);
        acceptInvitation(u6, p6);
        acceptInvitation(u7, p7);
        awaitRunning(tournamentId);

        // u7 голосует за u6 — u6 выживает при закрытии (tie-break иначе непредсказуем)
        UUID e6 = entryIdOfTournament(tournamentId, u6);
        String feed6 = feedOf(u6);
        UUID e7 = UUID.fromString(entryIdOf(feed6, u7));
        UUID windowId = windowIdOf(feed6, u7);
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + e6 + "/vote")
                .header(DEMO_HEADER, u7.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk());

        // до закрытия карточка u7 в ленте u6
        assertThat(entryIds(feedOf(u6))).contains(e7.toString());

        // закрытие раунда: u7 выбывает, растение гибнет (PERMANENT), карточки исчезают
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                mockMvc.perform(get("/api/v1/plants/" + p7).header(DEMO_HEADER, u7.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lifeStatus").value("DEAD"));
            });
        List<?> tournamentCards = JsonPath.read(feedOf(u6),
            "$.items[?(@.tournamentId == '" + tournamentId + "')]");
        assertThat(tournamentCards).isEmpty();
    }

    // ---------- helpers ----------

    private String feedOf(UUID user) throws Exception {
        return mockMvc.perform(get("/api/v1/feed").header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private List<String> entryIds(String feedJson) {
        return JsonPath.read(feedJson, "$.items[*].entryId");
    }

    private UUID windowIdOf(String feedJson, UUID ownerId) {
        List<Map<String, Object>> items = JsonPath.read(feedJson,
            "$.items[?(@.owner.userId == '" + ownerId + "')]");
        assertThat(items).hasSize(1);
        return UUID.fromString((String) items.get(0).get("windowId"));
    }

    private String entryIdOf(String feedJson, UUID ownerId) {
        List<Map<String, Object>> items = JsonPath.read(feedJson,
            "$.items[?(@.owner.userId == '" + ownerId + "')]");
        assertThat(items).hasSize(1);
        return (String) items.get(0).get("entryId");
    }

    private UUID entryIdOfTournament(UUID tournamentId, UUID user) throws Exception {
        String body = mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/entries")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> mine = JsonPath.read(body,
            "$[?(@.userId == '" + user + "')]");
        assertThat(mine).hasSize(1);
        return UUID.fromString((String) mine.get(0).get("id"));
    }

    private String createGuestSession() throws Exception {
        String body = mockMvc.perform(post("/api/v1/guest-sessions"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private void awaitGlobalCards(int expected) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                String feed = mockMvc.perform(get("/api/v1/feed")
                        .header(DEMO_HEADER, adminId().toString()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
                assertThat(entryIds(feed)).hasSize(expected);
            });
    }

    private UUID createPrivateTournament(int roundDurationSeconds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tournaments")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Закрытый %s","registrationDeadline":"%s","roundDurationSeconds":%d,
                     "eliminationFraction":0.5,"minParticipants":2}
                    """.formatted(UUID.randomUUID(), Instant.now().plusSeconds(2),
                        roundDurationSeconds)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID tournamentId = UUID.fromString(JsonPath.read(body, "$.id"));
        // DRAFT → REGISTRATION_OPEN: без этого accept приглашений — 409 REGISTRATION_CLOSED
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/open-registration")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REGISTRATION_OPEN"));
        return tournamentId;
    }

    private void invite(UUID tournamentId, UUID userId) throws Exception {
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/invitations")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\"}".formatted(userId)))
            .andExpect(status().isCreated());
    }

    private void acceptInvitation(UUID user, UUID plantId) throws Exception {
        String mine = mockMvc.perform(get("/api/v1/me/invitations")
                .header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID invitationId = UUID.fromString(JsonPath.read(mine, "$[0].id"));
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/accept")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"));
    }

    private void awaitRunning(UUID tournamentId) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                mockMvc.perform(get("/api/v1/tournaments/" + tournamentId)
                        .header(DEMO_HEADER, adminId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RUNNING"));
            });
    }

    private void setLocation(UUID user, double latitude, double longitude) throws Exception {
        mockMvc.perform(put("/api/v1/me/location")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":%s,\"longitude\":%s}".formatted(latitude, longitude)))
            .andExpect(status().isOk());
    }

    private UUID submitGlobalEntry(UUID user, UUID plantId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void runDue() throws Exception {
        mockMvc.perform(post("/api/v1/internal/demo/jobs/run-due")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk());
    }

    private UUID approvedPlantOf(UUID user, String title) throws Exception {
        UUID plantId = createPlantOf(user, title);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                    .header(DEMO_HEADER, user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("APPROVED")));
        return plantId;
    }

    private UUID createPlantOf(UUID user, String title) throws Exception {
        UUID assetId = uploadAs(user, referenceBytes("green-8x8.png"));
        String body = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"%s\"}".formatted(assetId, title)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID uploadAs(UUID userId, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "reference.png",
                    MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private byte[] referenceBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/media/reference/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Эталонный файл не найден: " + name);
            }
            return in.readAllBytes();
        }
    }

    private UUID adminId() {
        return jdbcTemplate.queryForObject(
            "select id from identity.app_user where email_normalized = ?",
            UUID.class, "admin@plantarena.local");
    }

    private UUID newUser(String email, String displayName) throws Exception {
        String response = mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"password-9","displayName":"%s"}
                    """.formatted(email, displayName)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }
}
