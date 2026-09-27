package com.plantarena.tournaments;

import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Конкурентные тесты раздела 14.2: одновременные голоса, гонка голоса и
 * закрытия, повторное закрытие. Реальный PostgreSQL, несколько потоков,
 * CountDownLatch; проверяются инварианты БД (score = сумма голосов, одна
 * гибель, один следующий раунд), а не только отсутствие исключений.
 * Scheduler (2 с) может закрыть окно параллельно — ассерты устойчивы к
 * этому (идемпотентность closeDue).
 */
@DisplayName("Конкурентность: голоса, гонка с закрытием, повторное закрытие")
class VotingConcurrencyIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VotingUseCase voting;

    @Autowired
    private CloseVotingWindowUseCase closeVotingWindow;

    @TestConfiguration
    static class DeterministicClassifierConfig {

        @Bean
        @Primary
        PlantClassifier deterministicPlantClassifier() {
            return new DeterministicPlantClassifier();
        }
    }

    @Test
    @DisplayName("параллельные голоса разных субъектов: score = сумма вкладов, по одному голосу на субъекта")
    void параллельные_голоса() throws Exception {
        Setup setup = setup(60); // окно на 60 с — закрытие не мешает
        int threads = 6; // 3 участника × 2 чужих entry
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> jobs = new ArrayList<>();
        for (UUID voter : List.of(setup.user1, setup.user2, setup.user3)) {
            for (UUID target : List.of(setup.entry1, setup.entry2, setup.entry3)) {
                if (target.equals(entryOf(setup, voter))) {
                    continue; // самоголосование запрещено
                }
                jobs.add(pool.submit(() -> {
                    start.await();
                    voting.cast(actor(voter), setup.windowId, target, "LIKE");
                    ok.incrementAndGet();
                    return null;
                }));
            }
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(ok.get()).isEqualTo(threads);

        // инвариант: score каждого участника = сумма вкладов его голосов в БД
        for (UUID entry : List.of(setup.entry1, setup.entry2, setup.entry3)) {
            Long score = jdbcTemplate.queryForObject(
                "select p.score from tournaments.window_participant p "
                    + "join tournaments.voting_window w on w.id = p.window_id "
                    + "where w.id = ? and p.entry_id = ?",
                Long.class, setup.windowId, entry);
            Long sum = jdbcTemplate.queryForObject(
                "select coalesce(sum(case v.value when 'LIKE' then 1 else -1 end), 0) "
                    + "from tournaments.vote v "
                    + "join tournaments.window_participant p on p.id = v.window_participant_id "
                    + "where p.window_id = ? and p.entry_id = ?",
                Long.class, setup.windowId, entry);
            assertThat(score).as("score = сумма голосов").isEqualTo(sum).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("гонка голоса и закрытия: голос либо применён и учтён, либо 409; окно закрывается один раз")
    void гонка_голоса_и_закрытия() throws Exception {
        Setup setup = setup(3); // окно на 3 с
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> voter = pool.submit(() -> {
            start.await();
            try {
                voting.cast(actor(setup.user1), setup.windowId, setup.entry2, "LIKE");
                accepted.incrementAndGet();
            } catch (com.plantarena.tournaments.application.VotingClosedException e) {
                rejected.incrementAndGet();
            }
            return null;
        });
        Future<?> closer = pool.submit(() -> {
            start.await();
            closeVotingWindow.closeDue(clockPlus(4), 10);
            return null;
        });
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        voter.get();
        closer.get();

        // окно закрыто ровно один раз; голос либо учтён, либо отклонён
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(statusOf(setup.windowId)).isEqualTo("CLOSED"));
        assertThat(accepted.get() + rejected.get()).isEqualTo(1);
        Long sum = jdbcTemplate.queryForObject(
            "select coalesce(sum(case v.value when 'LIKE' then 1 else -1 end), 0) "
                + "from tournaments.vote v "
                + "join tournaments.window_participant p on p.id = v.window_participant_id "
                + "where p.window_id = ? and p.entry_id = ?",
            Long.class, setup.windowId, setup.entry2);
        Long score = jdbcTemplate.queryForObject(
            "select p.score from tournaments.window_participant p where p.window_id = ? "
                + "and p.entry_id = ?",
            Long.class, setup.windowId, setup.entry2);
        assertThat(score).isEqualTo(sum); // инвариант при любой исходе гонки
        if (accepted.get() == 1) {
            assertThat(score).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("повторное закрытие: одна гибель, один запрет, один следующий раунд")
    void повторное_закрытие() throws Exception {
        Setup setup = setup(1); // окно на 1 с
        // оба вызова с «будущим» now закрывают OPEN-окно (единый источник
        // времени в closeDue) — реальная арбитраж FOR UPDATE; scheduler не
        // мешает: окно ещё не due по настоящему времени
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> jobs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            jobs.add(pool.submit(() -> {
                start.await();
                closeVotingWindow.closeDue(clockPlus(2), 10);
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (Future<?> job : jobs) {
            job.get(); // ошибка внутри задачи валит тест
        }

        // инварианты: окно закрыто, выбывших столько, сколько должен алгоритм,
        // следующий раунд ровно один, растение погибло с одним запретом
        assertThat(statusOf(setup.windowId)).isEqualTo("CLOSED");
        Integer eliminated = jdbcTemplate.queryForObject(
            "select count(*) from tournaments.tournament_entry where tournament_id = ? "
                + "and status = 'ELIMINATED'",
            Integer.class, setup.tournamentId);
        Integer windows = jdbcTemplate.queryForObject(
            "select count(*) from tournaments.voting_window where tournament_id = ?",
            Integer.class, setup.tournamentId);
        Integer restrictions = jdbcTemplate.queryForObject(
            "select count(*) from plants.image_restriction where owner_id = ?",
            Integer.class, eliminatedOwner(setup));
        assertThat(eliminated).isEqualTo(1); // 3 участника, f=0.5 → 1
        assertThat(windows).isEqualTo(2);    // закрытое + следующий раунд
        assertThat(restrictions).isEqualTo(1); // без дубля запрета
    }

    // ---------- setup ----------

    private record Setup(UUID tournamentId, UUID windowId, UUID entry1, UUID entry2, UUID entry3,
                         UUID user1, UUID user2, UUID user3, UUID plant1) {
    }

    private Setup setup(long roundDurationSeconds) throws Exception {
        // фиксированные email из плана конфликтуют между тестами класса (общая БД
        // Testcontainers) — делаем их уникальными на вызов setup
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID organizer = adminId();
        UUID u1 = createUserAsAdmin("conc-u1-" + suffix + "@example.com", "C1");
        UUID u2 = createUserAsAdmin("conc-u2-" + suffix + "@example.com", "C2");
        UUID u3 = createUserAsAdmin("conc-u3-" + suffix + "@example.com", "C3");
        UUID tournamentId = createTournamentAsAdmin("Конкурентный",
            Instant.now().plusSeconds(4), roundDurationSeconds);
        inviteAsAdmin(tournamentId, u1);
        inviteAsAdmin(tournamentId, u2);
        inviteAsAdmin(tournamentId, u3);
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/open-registration")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk());
        UUID plant1 = greenPlantOf(u1);
        acceptInvitation(u1, tournamentId, plant1);
        acceptInvitation(u2, tournamentId, greenPlantOf(u2));
        acceptInvitation(u3, tournamentId, greenPlantOf(u3));
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(tournamentStatus(tournamentId)).isEqualTo("RUNNING"));

        String rounds = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/tournaments/" + tournamentId + "/rounds")
                    .header(DEMO_HEADER, organizer.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID windowId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(rounds, "$[0].id"));
        UUID entry1 = entryIdOf(tournamentId, u1);
        UUID entry2 = entryIdOf(tournamentId, u2);
        UUID entry3 = entryIdOf(tournamentId, u3);
        return new Setup(tournamentId, windowId, entry1, entry2, entry3, u1, u2, u3, plant1);
    }

    private UUID entryIdOf(UUID tournamentId, UUID userId) {
        return jdbcTemplate.queryForObject(
            "select id from tournaments.tournament_entry where tournament_id = ? and user_id = ?",
            UUID.class, tournamentId, userId);
    }

    private UUID entryOf(Setup setup, UUID userId) {
        if (userId.equals(setup.user1)) {
            return setup.entry1;
        }
        return userId.equals(setup.user2) ? setup.entry2 : setup.entry3;
    }

    private String statusOf(UUID windowId) {
        return jdbcTemplate.queryForObject(
            "select status from tournaments.voting_window where id = ?",
            String.class, windowId);
    }

    private String tournamentStatus(UUID tournamentId) {
        return jdbcTemplate.queryForObject(
            "select status from tournaments.tournament where id = ?",
            String.class, tournamentId);
    }

    private UUID eliminatedOwner(Setup setup) {
        return jdbcTemplate.queryForObject(
            "select user_id from tournaments.tournament_entry where tournament_id = ? "
                + "and status = 'ELIMINATED'",
            UUID.class, setup.tournamentId);
    }

    /** now из Clock приложения + сдвиг (окна закрываются по closesAt, не по wall-clock теста). */
    private Instant clockPlus(long seconds) {
        return Instant.now().plusSeconds(seconds);
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }

    // ---------- HTTP-хелперы (по образцу VotingApiIT) ----------

    private UUID createTournamentAsAdmin(String name, Instant deadline,
                                         long roundDurationSeconds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tournaments")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"%s","description":"Описание","registrationDeadline":"%s",\
                    "roundDurationSeconds":%d,"eliminationFraction":0.5,\
                    "minParticipants":2,"tagIds":[]}
                    """.formatted(name, deadline, roundDurationSeconds)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    private void inviteAsAdmin(UUID tournamentId, UUID userId) throws Exception {
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/invitations")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\"}".formatted(userId)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated());
    }

    private void acceptInvitation(UUID user, UUID tournamentId, UUID plantId) throws Exception {
        String body = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/me/invitations")
                .header(DEMO_HEADER, user.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<?> items = com.jayway.jsonpath.JsonPath.read(body, "$[?(@.status=='INVITED')]");
        UUID invitationId = UUID.fromString(
            (String) ((java.util.Map<String, Object>) items.get(0)).get("id"));
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/accept")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk());
    }

    private UUID greenPlantOf(UUID user) throws Exception {
        UUID assetId = uploadAs(user, referenceBytes("green-8x8.png"));
        String body = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"Конкурентный фикус\"}"
                    .formatted(assetId)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    private UUID uploadAs(UUID userId, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "reference.png",
                    MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.id"));
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

    private UUID createUserAsAdmin(String email, String displayName) throws Exception {
        String response = mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"password-9","displayName":"%s"}
                    """.formatted(email, displayName)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.id"));
    }
}
