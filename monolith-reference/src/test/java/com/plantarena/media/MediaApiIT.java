package com.plantarena.media;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.support.AbstractIntegrationTest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 2: сценарии раздела 6 требований (файлы)
 * через HTTP на Testcontainers. Заголовок задаётся строковой константой,
 * чтобы IT не зависел от реализации. Красный до Task 6.
 */
@DisplayName("Сценарии раздела 6 (файлы): загрузка, форматы, отпечатки, /files (media)")
class MediaApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void пользователь_загружает_png_и_получает_метаданные() throws Exception {
        UUID ownerId = createUserAsAdmin("media-owner@example.com", "Media Owner");
        byte[] reference = referenceBytes("red-8x8.png");

        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "red-8x8.png", MediaType.IMAGE_PNG_VALUE, reference))
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", containsString("/api/v1/files/")))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
            .andExpect(jsonPath("$.mimeType").value("image/png"))
            .andExpect(jsonPath("$.width").value(8))
            .andExpect(jsonPath("$.height").value(8))
            .andExpect(jsonPath("$.byteSize").value(reference.length))
            .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("storageKey");
        assertThat(response).doesNotContain("rawSha256");
        assertThat(response).doesNotContain("imageFingerprint");
    }

    @Test
    void гость_не_может_загружать_файлы() throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "red-8x8.png", MediaType.IMAGE_PNG_VALUE,
                    referenceBytes("red-8x8.png"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void фактический_формат_проверяется_текстовые_байты_отклоняются() throws Exception {
        UUID ownerId = createUserAsAdmin("media-format@example.com", "Media Format");

        mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "fake.png", MediaType.IMAGE_PNG_VALUE,
                    "это не изображение, просто текст".getBytes()))
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_FORMAT"));
    }

    @Test
    void файл_больше_10MiB_отклоняется() throws Exception {
        UUID ownerId = createUserAsAdmin("media-size@example.com", "Media Size");
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        java.util.Arrays.fill(tooLarge, (byte) 'x');

        mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "big.png", MediaType.IMAGE_PNG_VALUE, tooLarge))
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void изображение_больше_20Mpx_отклоняется() throws Exception {
        UUID ownerId = createUserAsAdmin("media-pixels@example.com", "Media Pixels");

        mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "huge.png", MediaType.IMAGE_PNG_VALUE, hugePng()))
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @Test
    void отпечаток_игнорирует_метаданные_файла() throws Exception {
        UUID ownerId = createUserAsAdmin("media-fingerprint@example.com", "Media Fingerprint");
        UUID firstId = uploadAs(ownerId, referenceBytes("red-8x8.png"), "red-8x8.png");
        UUID secondId = uploadAs(ownerId, referenceBytes("red-8x8-with-comment.png"),
            "red-8x8-with-comment.png");

        String firstFingerprint = fingerprintOf(firstId);
        String secondFingerprint = fingerprintOf(secondId);
        String firstRaw = rawSha256Of(firstId);
        String secondRaw = rawSha256Of(secondId);

        assertThat(firstFingerprint).isEqualTo(secondFingerprint); // пиксели одинаковые
        assertThat(firstRaw).isNotEqualTo(secondRaw);              // байты разные
        assertThat(firstFingerprint).hasSize(64).matches("[0-9a-f]+");
        assertThat(fingerprintVersionOf(firstId)).isEqualTo(1);
    }

    @Test
    void повторная_загрузка_тех_же_байтов_создаёт_новый_asset() throws Exception {
        UUID ownerId = createUserAsAdmin("media-reupload@example.com", "Media Reupload");
        byte[] reference = referenceBytes("red-8x8.png");

        UUID firstId = uploadAs(ownerId, reference, "red-8x8.png");
        UUID secondId = uploadAs(ownerId, reference, "red-8x8.png");

        assertThat(firstId).isNotEqualTo(secondId); // перезапись невозможна (раздел 6)
    }

    @Test
    void владелец_скачивает_своё_изображение() throws Exception {
        UUID ownerId = createUserAsAdmin("media-download@example.com", "Media Download");
        UUID assetId = uploadAs(ownerId, referenceBytes("red-8x8.png"), "red-8x8.png");

        byte[] content = mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentType(MediaType.IMAGE_PNG))
            .andReturn().getResponse().getContentAsByteArray();

        assertThat(content).containsExactly(referenceBytes("red-8x8.png"));
    }

    @Test
    void чужой_файл_скрыт_от_другого_пользователя() throws Exception {
        UUID ownerId = createUserAsAdmin("media-hidden@example.com", "Media Hidden");
        UUID otherId = createUserAsAdmin("media-other@example.com", "Media Other");
        UUID assetId = uploadAs(ownerId, referenceBytes("red-8x8.png"), "red-8x8.png");

        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, otherId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/files/" + assetId))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void владелец_удаляет_файл() throws Exception {
        UUID ownerId = createUserAsAdmin("media-delete@example.com", "Media Delete");
        UUID assetId = uploadAs(ownerId, referenceBytes("red-8x8.png"), "red-8x8.png");

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void админ_удаляет_файл() throws Exception {
        UUID ownerId = createUserAsAdmin("media-admin-delete@example.com", "Media Admin Delete");
        UUID assetId = uploadAs(ownerId, referenceBytes("red-8x8.png"), "red-8x8.png");

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isNoContent());
    }

    @Test
    void чужой_пользователь_не_удаляет_файл() throws Exception {
        UUID ownerId = createUserAsAdmin("media-no-delete@example.com", "Media No Delete");
        UUID otherId = createUserAsAdmin("media-no-delete-other@example.com", "Media No Delete Other");
        UUID assetId = uploadAs(ownerId, referenceBytes("red-8x8.png"), "red-8x8.png");

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, otherId.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void запрос_без_части_file_отклоняется() throws Exception {
        UUID ownerId = createUserAsAdmin("media-part@example.com", "Media Part");

        mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("attachment", "note.txt", MediaType.TEXT_PLAIN_VALUE,
                    "не та часть".getBytes()))
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_PART"));
    }

    @Test
    void не_multipart_запрос_отклоняется() throws Exception {
        UUID ownerId = createUserAsAdmin("media-not-multipart@example.com", "Media Not Multipart");

        mockMvc.perform(post("/api/v1/files")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    private UUID uploadAs(UUID userId, byte[] content, String filename) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", filename, MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private String fingerprintOf(UUID assetId) {
        return jdbcTemplate.queryForObject(
            "select image_fingerprint from media.media_asset where id = ?", String.class, assetId);
    }

    private String rawSha256Of(UUID assetId) {
        return jdbcTemplate.queryForObject(
            "select raw_sha256 from media.media_asset where id = ?", String.class, assetId);
    }

    private int fingerprintVersionOf(UUID assetId) {
        return jdbcTemplate.queryForObject(
            "select fingerprint_version from media.media_asset where id = ?", Integer.class, assetId);
    }

    private byte[] referenceBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/media/reference/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Эталонный файл не найден: " + name);
            }
            return in.readAllBytes();
        }
    }

    private byte[] hugePng() throws IOException {
        BufferedImage image = new BufferedImage(5000, 4200, BufferedImage.TYPE_INT_RGB); // 21M пикселей
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 5000, 4200);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private UUID adminId() {
        return userIdByEmail("admin@plantarena.local");
    }

    private UUID userIdByEmail(String email) {
        return jdbcTemplate.queryForObject(
            "select id from identity.app_user where email_normalized = ?", UUID.class, email);
    }

    private UUID createUserAsAdmin(String email, String displayName) throws Exception {
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
