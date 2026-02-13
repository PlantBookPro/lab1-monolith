# Итерация 2 — media: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Контекст media: неизменяемый агрегат `MediaAsset`, загрузка multipart с проверкой фактического формата/размеров (JPEG/PNG ≤10MiB ≤20Mpx), `rawSha256`, `ImageFingerprint` (нормализация, версия алгоритма, эталонные файлы), порт `FileStorage` + локальный адаптер, REST `/files`. DoD: отпечаток игнорирует метаданные; эталонные примеры в `src/test/resources`; сценарии раздела 6 (файлы) проходят через HTTP на Testcontainers.

**Architecture:** Внутри media: `domain` (агрегат `MediaAsset`, VO `ImageFingerprint`, `AnalyzedImage`, порты `ImageAnalyzer`/`FileStorage`, доменный сервис `ImageFingerprinter`, доменные исключения формата/разрешения) ← `application` (use cases загрузки/скачивания/удаления, `MediaAccessPolicy`, исключения `FileTooLargeException`/`MediaAssetNotFoundException`, порт `MediaAssetRepository`) ← `adapter` (`in.web` REST `/files` + `MediaExceptionHandler`, `out.image` javax.imageio, `out.storage` локальное хранилище, `out.persistence` JPA). Транзакции по разделу 12: файл сохраняется в хранилище ДО короткой транзакции регистрации метаданных; сбой регистрации компенсируется удалением сиротского файла. media — upstream для plants/moderation/feed: `media.api` появится в итерации 3, когда появится потребитель (YAGNI).

**Tech Stack:** без новых зависимостей: декодирование — `javax.imageio` (JDK), хэши — `java.security.MessageDigest` (JDK), multipart — Spring MVC (`spring.servlet.multipart`), тесты — JUnit 5 + AssertJ + MockMvc + Testcontainers (существующие).

## Global Constraints

- Все ограничения итераций 0–1 действуют: один Maven-модуль; версии закреплены; `domain` без Spring/JPA/Jackson (ArchUnit-правила зелёные после каждого task); Enum → VARCHAR + CHECK; время из `Clock`.
- Пакеты только по правилу 10.2: `media.domain`/`media.application` не импортируют другие контексты; `shared` не зависит от контекстов; `config` — единственное место, знающее несколько контекстов (в этой итерации новые бины media — `@Service`/`@Component`/`@Repository` внутри своего контекста, `config` не меняется).
- `MediaAsset` неизменяем после создания: мутаторов нет, повторная загрузка создаёт новый asset (раздел 6). Файлы нельзя перезаписывать.
- Формат проверяется по фактическому содержимому (javax.imageio reader), не по расширению/MIME клиента. Разрешены только JPEG и PNG.
- Лимиты (раздел 6): ≤10 MiB байт (проверка в application до анализа), ≤20 000 000 пикселей (проверка по заголовку изображения до декодирования растра).
- `storageKey` — внутренний: никогда не возвращается в ответах (раздел 6: «не возвращай внутренние пути хранилища»). В ответе POST /files: id, ownerId, mimeType, byteSize, width, height, createdAt — без хэшей и storageKey.
- Отпечаток v1 (ADR-007): SHA-256 по префиксу версии + ширина/высота int64 big-endian + байты R,G,B каждого пикселя row-major; альфа и метаданные игнорируются; EXIF-ориентация не применяется (зафиксировано в ADR).
- HTTP-коды (раздел 13): 201 + Location (создание), 200 (скачивание, с фактическим Content-Type), 204 (удаление), 400 (нет части `file`), 401 (гость), 403 (не владелец/не админ при удалении), 404 (не найден/скрыт приватностью — чужие файлы), 413 (`FILE_TOO_LARGE` — байты, `IMAGE_TOO_LARGE` — пиксели), 415 (`UNSUPPORTED_IMAGE_FORMAT` — содержимое; `UNSUPPORTED_MEDIA_TYPE` — не multipart запрос).
- Транзакции (раздел 12): не держать DB-транзакцию во время файловых операций; `repository.save` — короткая транзакция в адаптере; сбой регистрации метаданных → удаление сиротского файла.
- Git: ветка `feat/iteration-2-media` от `main`, Conventional Commits, красные тесты не в `main`, push — только по отдельной команде.
- `./mvnw verify` зелёный в конце итерации; JaCoCo LINE ≥ 70% (gate). В Tasks 1–5 достаточно `./mvnw test` (приёмочный `MediaApiIT` красный до Task 6 — failsafe не запускается в `test`).
- Отложено по roadmap (не делать сейчас): 409 при удалении задействованного файла (задействованность появится в plants, итерация 3); видимость файлов по связанным растениям (итерация 3: сейчас приватные незаявленные файлы — только владельцу); `media.api` фасад для plants (итерация 3, YAGNI); кэширование/ETag скачивания (не требуется).

---

### Task 1: Эталонные файлы + красный приёмочный IT — сценарии файлов через HTTP

**Files:**
- Create: `src/test/resources/media/reference/red-8x8.png`
- Create: `src/test/resources/media/reference/red-8x8-with-comment.png`
- Modify: `src/test/java/com/plantarena/support/AbstractIntegrationTest.java`
- Test: `src/test/java/com/plantarena/media/MediaApiIT.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (MockMvc + Testcontainers, профиль test, bootstrap-админ) из итерации 1; `IdentityApiIT`-хелперы по созданию пользователей через `POST /api/v1/users`.
- Produces: эталонные PNG с одинаковыми пикселями и разными метаданными (tEXt) в `src/test/resources/media/reference/`; `AbstractIntegrationTest` предоставляет всем IT свойство `plantarena.media.storage.root` (временный каталог на JVM); красный `MediaApiIT` — внешний цикл TDD, зелёный с Task 6. Изменять `IdentityApiIT` и другие существующие тесты нельзя.

- [ ] **Step 1: Создать ветку**

```bash
cd /Users/vovabag/Desktop/personal/plantBook/lab1-monolith
git checkout main && git checkout -b feat/iteration-2-media
```

- [ ] **Step 2: Сгенерировать эталонные PNG (одинаковые пиксели, разные метаданные)**

Скрипт на чистом Python 3 (stdlib: struct, zlib) пишет два PNG 8×8 сплошного красного цвета: первый — минимальный (IHDR, IDAT, IEND), второй — с дополнительным чанком tEXt `Comment`. IDAT обоих файлов байт-в-байт одинаков (одинаковые пиксели, одинаковый уровень zlib), файлы различаются только метаданными.

```bash
mkdir -p src/test/resources/media/reference && python3 - <<'EOF'
import os, struct, zlib

OUT = "src/test/resources/media/reference"

def chunk(ctype: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + ctype + data
            + struct.pack(">I", zlib.crc32(ctype + data) & 0xFFFFFFFF))

def png(width: int, height: int, rgb: tuple, text_comment: bytes | None = None) -> bytes:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit truecolor RGB
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    idat = zlib.compress(raw, 9)
    chunks = [chunk(b"IHDR", ihdr)]
    if text_comment is not None:
        chunks.append(chunk(b"tEXt", b"Comment\x00" + text_comment))
    chunks.append(chunk(b"IDAT", idat))
    chunks.append(chunk(b"IEND", b""))
    return b"\x89PNG\r\n\x1a\n" + b"".join(chunks)

red = (255, 0, 0)
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "red-8x8.png"), "wb") as f:
    f.write(png(8, 8, red))
with open(os.path.join(OUT, "red-8x8-with-comment.png"), "wb") as f:
    f.write(png(8, 8, red, b"plantarena reference"))
print("written")
EOF
```

Проверка: оба файла — валидные PNG 8×8, различаются по байтам, совпадают по пикселям.

```bash
file src/test/resources/media/reference/*.png
cmp src/test/resources/media/reference/red-8x8.png src/test/resources/media/reference/red-8x8-with-comment.png; echo "cmp exit: $?"
```

Ожидание: `... PNG image data, 8 x 8, 8-bit/color RGB, non-interlaced` для обоих; `cmp` сообщает отличие (exit 1) — файлы разные, пиксели одинаковые по построению (равенство отпечатков докажет IT в Task 6).

- [ ] **Step 3: Дать всем IT временный корень хранилища media**

Полное новое содержимое `src/test/java/com/plantarena/support/AbstractIntegrationTest.java` (добавляется только свойство `plantarena.media.storage.root`; остальное без изменений из итерации 1):

```java
package com.plantarena.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Базовый класс всех IT: Testcontainers PostgreSQL (singleton), профиль test
 * (демо-идентификация ADR-005) и bootstrap-админ из ENV (раздел 2 требований).
 * Хранилище media — временный каталог на JVM (не пишет в рабочую копию).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final Path MEDIA_STORAGE_ROOT = createMediaStorageRoot();

    private static Path createMediaStorageRoot() {
        try {
            return Files.createTempDirectory("plantarena-media-it");
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать временный каталог хранилища", e);
        }
    }

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("plantarena.bootstrap-admin.email", () -> "admin@plantarena.local");
        registry.add("plantarena.bootstrap-admin.password", () -> "admin-password-123");
        registry.add("plantarena.bootstrap-admin.display-name", () -> "Bootstrap Admin");
        registry.add("plantarena.media.storage.root", () -> MEDIA_STORAGE_ROOT.toString());
    }
}
```

- [ ] **Step 4: Написать красный приёмочный IT**

`src/test/java/com/plantarena/media/MediaApiIT.java`:

```java
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
```

- [ ] **Step 5: Убедиться, что IT красный**

```bash
./mvnw verify -Dit.test=MediaApiIT
```

Ожидание: BUILD FAILURE — все тесты `MediaApiIT` падают (ручки `/api/v1/files` не существуют: 404/RESOURCE_NOT_FOUND вместо 201 и т. п.). Это красный внешний цикл TDD; `IdentityApiIT` и остальные IT не запускаются (фильтр `-Dit.test`), unit-тесты зелёные.

- [ ] **Step 6: Коммит**

```bash
git add docs/plans/2026-09-24-iteration-2-media.md src/test/resources/media/reference \
  src/test/java/com/plantarena/support/AbstractIntegrationTest.java \
  src/test/java/com/plantarena/media/MediaApiIT.java
git commit -m "test: красный приёмочный IT MediaApiIT и эталонные PNG для отпечатков"
```

---

### Task 2: Домен media — MediaAsset, ImageFingerprint, порты, ImageFingerprinter

**Files:**
- Create: `src/main/java/com/plantarena/media/domain/ImageFormat.java`
- Create: `src/main/java/com/plantarena/media/domain/ImageFingerprint.java`
- Create: `src/main/java/com/plantarena/media/domain/AnalyzedImage.java`
- Create: `src/main/java/com/plantarena/media/domain/ImageAnalyzer.java`
- Create: `src/main/java/com/plantarena/media/domain/FileStorage.java`
- Create: `src/main/java/com/plantarena/media/domain/UnsupportedImageFormatException.java`
- Create: `src/main/java/com/plantarena/media/domain/ImageResolutionTooHighException.java`
- Create: `src/main/java/com/plantarena/media/domain/ImageFingerprinter.java`
- Create: `src/main/java/com/plantarena/media/domain/MediaAsset.java`
- Test: `src/test/java/com/plantarena/media/domain/ImageFingerprintTest.java`
- Test: `src/test/java/com/plantarena/media/domain/MediaAssetTest.java`

**Interfaces:**
- Consumes: ничего из итерации 1 (media.domain — чистый Java, без Spring/JPA).
- Produces (для Tasks 3–5): `ImageFormat` (`JPEG`/`PNG`, `mimeType()`, `extension()`, `fromMimeType(String)`); `ImageFingerprint(String value, int version)` — 64 hex, version ≥ 1; `AnalyzedImage(ImageFormat format, int width, int height, int[] argb)`; порты `ImageAnalyzer.analyze(byte[]) → AnalyzedImage` и `FileStorage.save(byte[], ImageFormat) → storageKey / read(String) → byte[] / delete(String)`; `ImageFingerprinter.fingerprint(AnalyzedImage) → ImageFingerprint` (v1, `ALGORITHM_VERSION = 1`) и `rawSha256(byte[]) → String`; `MediaAsset.uploaded(ownerId, storageKey, format, byteSize, width, height, rawSha256, fingerprint, createdAt)` (новый UUID) и `MediaAsset.restore(...)` (существующий id — для JPA); исключения `UnsupportedImageFormatException`, `ImageResolutionTooHighException`.

- [ ] **Step 1: Написать красные доменные тесты**

`src/test/java/com/plantarena/media/domain/ImageFingerprintTest.java`:

```java
package com.plantarena.media.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Отпечаток изображения: алгоритм v1 (ADR-007)")
class ImageFingerprintTest {

    private final ImageFingerprinter fingerprinter = new ImageFingerprinter();

    @Test
    void алгоритм_v1_зафиксирован_независимым_вычислением() throws Exception {
        // 1x1, пиксель RGB (0x12, 0x34, 0x56), альфа 0xFF — игнорируется
        AnalyzedImage image = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF123456});

        String fingerprint = fingerprinter.fingerprint(image).value();

        // Ожидание вычислено независимо от реализации: SHA-256 по документированной
        // раскладке ADR-007 — префикс версии, ширина/высота int64 big-endian, байты R,G,B.
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update("plantarena-image-fingerprint-v1".getBytes(StandardCharsets.US_ASCII));
        expected.update(ByteBuffer.allocate(16).putLong(1L).putLong(1L).array());
        expected.update(new byte[] {0x12, 0x34, 0x56});
        assertThat(fingerprint).isEqualTo(HexFormat.of().formatHex(expected.digest()));
    }

    @Test
    void альфа_канал_игнорируется() {
        AnalyzedImage opaque = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF102030});
        AnalyzedImage transparent = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0x00102030});

        assertThat(fingerprinter.fingerprint(opaque)).isEqualTo(fingerprinter.fingerprint(transparent));
    }

    @Test
    void порядок_каналов_зафиксирован_rgb() {
        AnalyzedImage image = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF123456});
        AnalyzedImage swapped = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF654321});

        assertThat(fingerprinter.fingerprint(image)).isNotEqualTo(fingerprinter.fingerprint(swapped));
    }

    @Test
    void размеры_входят_в_отпечаток() {
        // те же пиксели в другой геометрии (2x1 вместо 1x2) — другой отпечаток
        AnalyzedImage wide = new AnalyzedImage(ImageFormat.PNG, 2, 1,
            new int[] {0xFF000000, 0xFF112233});
        AnalyzedImage tall = new AnalyzedImage(ImageFormat.PNG, 1, 2,
            new int[] {0xFF000000, 0xFF112233});

        assertThat(fingerprinter.fingerprint(wide)).isNotEqualTo(fingerprinter.fingerprint(tall));
    }

    @Test
    void версия_алгоритма_равна_1() {
        AnalyzedImage image = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF123456});

        assertThat(fingerprinter.fingerprint(image).version()).isEqualTo(1);
    }

    @Test
    void rawSha256_вычисляется_по_исходным_байтам() {
        byte[] content = "plantarena".getBytes(StandardCharsets.UTF_8);

        assertThat(fingerprinter.rawSha256(content)).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void некорректный_отпечаток_невозможно_создать() {
        assertThatThrownBy(() -> new ImageFingerprint("not-hex", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("a".repeat(64), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/plantarena/media/domain/MediaAssetTest.java`:

```java
package com.plantarena.media.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MediaAsset: неизменяемый загруженный файл")
class MediaAssetTest {

    private MediaAsset asset() {
        return MediaAsset.uploaded(UUID.randomUUID(), "key.png", ImageFormat.PNG, 123,
            8, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1),
            Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    void повторная_загрузка_создаёт_новый_asset() {
        MediaAsset first = asset();
        MediaAsset second = asset();

        assertThat(first.id()).isNotEqualTo(second.id()); // перезапись невозможна (раздел 6)
    }

    @Test
    void восстановление_из_хранилища_сохраняет_id() {
        UUID id = UUID.randomUUID();

        MediaAsset restored = MediaAsset.restore(id, UUID.randomUUID(), "key.png",
            ImageFormat.PNG, 123, 8, 8, "a".repeat(64),
            new ImageFingerprint("b".repeat(64), 1), Instant.parse("2026-09-24T10:00:00Z"));

        assertThat(restored.id()).isEqualTo(id);
    }

    @Test
    void метаданные_доступны_только_для_чтения() {
        MediaAsset asset = asset();

        assertThat(asset.ownerId()).isNotNull();
        assertThat(asset.storageKey()).isEqualTo("key.png");
        assertThat(asset.format()).isEqualTo(ImageFormat.PNG);
        assertThat(asset.byteSize()).isEqualTo(123);
        assertThat(asset.width()).isEqualTo(8);
        assertThat(asset.height()).isEqualTo(8);
        assertThat(asset.rawSha256()).isEqualTo("a".repeat(64));
        assertThat(asset.fingerprint()).isEqualTo(new ImageFingerprint("b".repeat(64), 1));
        assertThat(asset.createdAt()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    void некорректные_метаданные_отклоняются() {
        assertThatThrownBy(() -> MediaAsset.uploaded(UUID.randomUUID(), "k", ImageFormat.PNG, 0,
            8, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1), Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaAsset.uploaded(UUID.randomUUID(), "k", ImageFormat.PNG, 10,
            0, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1), Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaAsset.uploaded(UUID.randomUUID(), "k", ImageFormat.PNG, 10,
            8, 8, "a".repeat(64), null, Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Убедиться, что тесты красные**

```bash
./mvnw test -Dtest='ImageFingerprintTest,MediaAssetTest'
```

Ожидание: COMPILATION ERROR — классы `media.domain` не существуют.

- [ ] **Step 3: Реализовать домен**

`src/main/java/com/plantarena/media/domain/ImageFormat.java`:

```java
package com.plantarena.media.domain;

/**
 * Форматы первого этапа (раздел 6): JPEG и PNG. MIME и расширение — часть
 * единого языка media; маппинг MIME ↔ формат используется JPA-адаптером.
 */
public enum ImageFormat {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png");

    private final String mimeType;
    private final String extension;

    ImageFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static ImageFormat fromMimeType(String mimeType) {
        for (ImageFormat format : values()) {
            if (format.mimeType.equals(mimeType)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Неизвестный MIME-тип изображения: " + mimeType);
    }
}
```

`src/main/java/com/plantarena/media/domain/ImageFingerprint.java`:

```java
package com.plantarena.media.domain;

import java.util.regex.Pattern;

/**
 * Отпечаток изображения (глоссарий, раздел 6): хэш нормализованных пикселей
 * с версией алгоритма. Алгоритм v1 — ADR-007. Игнорирует метаданные;
 * выявляет повторную загрузку тех же нормализованных пикселей.
 */
public record ImageFingerprint(String value, int version) {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    public ImageFingerprint {
        if (value == null || !HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("Отпечаток должен быть 64 hex-символами: " + value);
        }
        if (version < 1) {
            throw new IllegalArgumentException("Версия алгоритма отпечатка должна быть положительной");
        }
    }
}
```

`src/main/java/com/plantarena/media/domain/AnalyzedImage.java`:

```java
package com.plantarena.media.domain;

/**
 * Результат анализа фактического содержимого файла (порт ImageAnalyzer):
 * формат, размеры и пиксели ARGB (как декодированы, без ресайза).
 */
public record AnalyzedImage(ImageFormat format, int width, int height, int[] argb) {

    public AnalyzedImage {
        if (format == null) {
            throw new IllegalArgumentException("Формат изображения обязателен");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Размеры изображения должны быть положительными");
        }
        if (argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("Массив пикселей должен совпадать с размерами изображения");
        }
    }
}
```

`src/main/java/com/plantarena/media/domain/ImageAnalyzer.java`:

```java
package com.plantarena.media.domain;

/**
 * Порт анализа фактического содержимого изображения (раздел 6): определяет
 * формат по содержимому (не по расширению/MIME клиента) и размеры по заголовку
 * до декодирования растра. Реализация — media.adapter.out.image.
 */
public interface ImageAnalyzer {

    /**
     * @throws UnsupportedImageFormatException содержимое — не JPEG и не PNG
     * @throws ImageResolutionTooHighException больше 20 миллионов пикселей
     */
    AnalyzedImage analyze(byte[] content);
}
```

`src/main/java/com/plantarena/media/domain/FileStorage.java`:

```java
package com.plantarena.media.domain;

/**
 * Порт файлового хранилища (раздел 6): локальное на Docker volume в лабе №1,
 * S3-совместимый file-service в лабе №4 — меняется только адаптер.
 * Ключ генерирует хранилище; перезапись невозможна.
 */
public interface FileStorage {

    String save(byte[] content, ImageFormat format);

    byte[] read(String storageKey);

    void delete(String storageKey);
}
```

`src/main/java/com/plantarena/media/domain/UnsupportedImageFormatException.java`:

```java
package com.plantarena.media.domain;

/**
 * Фактическое содержимое файла — не JPEG и не PNG (HTTP 415, раздел 13).
 * Доменное исключение единого языка, не знает об HTTP.
 */
public class UnsupportedImageFormatException extends RuntimeException {

    public UnsupportedImageFormatException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/media/domain/ImageResolutionTooHighException.java`:

```java
package com.plantarena.media.domain;

/**
 * Изображение превышает 20 миллионов пикселей (HTTP 413, разделы 6 и 13).
 * Доменное исключение единого языка, не знает об HTTP.
 */
public class ImageResolutionTooHighException extends RuntimeException {

    public ImageResolutionTooHighException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/media/domain/ImageFingerprinter.java`:

```java
package com.plantarena.media.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Доменный сервис отпечатков (ADR-007, алгоритм v1): SHA-256 по нормализованным
 * пикселям — префикс версии, ширина/высота int64 big-endian, затем R,G,B каждого
 * пикселя построчно (row-major). Альфа-канал и метаданные игнорируются;
 * EXIF-ориентация не применяется (зафиксировано в ADR-007). Любое изменение
 * раскладки требует новой версии алгоритма.
 */
public final class ImageFingerprinter {

    public static final int ALGORITHM_VERSION = 1;
    private static final byte[] PREFIX =
        "plantarena-image-fingerprint-v1".getBytes(StandardCharsets.US_ASCII);

    public ImageFingerprint fingerprint(AnalyzedImage image) {
        MessageDigest digest = sha256();
        digest.update(PREFIX);
        digest.update(longBytes(image.width()));
        digest.update(longBytes(image.height()));
        for (int pixel : image.argb()) {
            digest.update((byte) (pixel >> 16)); // R
            digest.update((byte) (pixel >> 8));  // G
            digest.update((byte) pixel);         // B (альфа игнорируется)
        }
        return new ImageFingerprint(HexFormat.of().formatHex(digest.digest()), ALGORITHM_VERSION);
    }

    /** rawSha256 (раздел 6): хэш исходных байтов — выявляет одинаковые файлы. */
    public String rawSha256(byte[] content) {
        return HexFormat.of().formatHex(sha256().digest(content));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static byte[] longBytes(long value) {
        return new byte[] {
            (byte) (value >> 56), (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32),
            (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }
}
```

`src/main/java/com/plantarena/media/domain/MediaAsset.java`:

```java
package com.plantarena.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат media (раздел 6): неизменяемый загруженный файл и его метаданные.
 * Инвариант: неизменяем после создания — мутаторов нет; повторная загрузка
 * создаёт новый asset. storageKey — внутренний, наружу не публикуется.
 */
public final class MediaAsset {

    private final UUID id;
    private final UUID ownerId;
    private final String storageKey;
    private final ImageFormat format;
    private final long byteSize;
    private final int width;
    private final int height;
    private final String rawSha256;
    private final ImageFingerprint fingerprint;
    private final Instant createdAt;

    private MediaAsset(UUID id, UUID ownerId, String storageKey, ImageFormat format,
                       long byteSize, int width, int height, String rawSha256,
                       ImageFingerprint fingerprint, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.format = Objects.requireNonNull(format, "format");
        if (byteSize <= 0) {
            throw new IllegalArgumentException("byteSize должен быть положительным");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Размеры изображения должны быть положительными");
        }
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.rawSha256 = Objects.requireNonNull(rawSha256, "rawSha256");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static MediaAsset uploaded(UUID ownerId, String storageKey, ImageFormat format,
                                      long byteSize, int width, int height, String rawSha256,
                                      ImageFingerprint fingerprint, Instant createdAt) {
        return new MediaAsset(UUID.randomUUID(), ownerId, storageKey, format, byteSize,
            width, height, rawSha256, fingerprint, createdAt);
    }

    /** Восстановление из хранилища с сохранением id (JPA-адаптер). */
    public static MediaAsset restore(UUID id, UUID ownerId, String storageKey, ImageFormat format,
                                     long byteSize, int width, int height, String rawSha256,
                                     ImageFingerprint fingerprint, Instant createdAt) {
        return new MediaAsset(id, ownerId, storageKey, format, byteSize,
            width, height, rawSha256, fingerprint, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String storageKey() {
        return storageKey;
    }

    public ImageFormat format() {
        return format;
    }

    public long byteSize() {
        return byteSize;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String rawSha256() {
        return rawSha256;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
```

- [ ] **Step 4: Убедиться, что доменные тесты зелёные и ArchUnit доволен**

```bash
./mvnw test
```

Ожидание: BUILD SUCCESS — `ImageFingerprintTest` (8), `MediaAssetTest` (4) зелёные, все существующие тесты зелёные, ArchUnit-правила зелёные (`MediaApiIT` не запускается — failsafe).

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/plantarena/media src/test/java/com/plantarena/media/domain
git commit -m "feat(media): домен — MediaAsset, ImageFingerprint v1, порты ImageAnalyzer/FileStorage"
```

---

### Task 3: Application-слой media — use cases, AccessPolicy, фейки

**Files:**
- Create: `src/main/java/com/plantarena/media/application/port/in/UploadMediaUseCase.java`
- Create: `src/main/java/com/plantarena/media/application/port/in/DownloadMediaUseCase.java`
- Create: `src/main/java/com/plantarena/media/application/port/in/DeleteMediaUseCase.java`
- Create: `src/main/java/com/plantarena/media/application/port/out/MediaAssetRepository.java`
- Create: `src/main/java/com/plantarena/media/application/MediaAssetResult.java`
- Create: `src/main/java/com/plantarena/media/application/FileTooLargeException.java`
- Create: `src/main/java/com/plantarena/media/application/MediaAssetNotFoundException.java`
- Create: `src/main/java/com/plantarena/media/application/MediaAccessPolicy.java`
- Create: `src/main/java/com/plantarena/media/application/MediaAssetService.java`
- Create: `src/test/java/com/plantarena/media/application/support/InMemoryMediaAssetRepository.java`
- Create: `src/test/java/com/plantarena/media/application/support/FakeImageAnalyzer.java`
- Create: `src/test/java/com/plantarena/media/application/support/InMemoryFileStorage.java`
- Test: `src/test/java/com/plantarena/media/application/MediaAssetServiceTest.java`

**Interfaces:**
- Consumes: домен Task 2 (`MediaAsset`, `ImageAnalyzer`, `FileStorage`, `ImageFingerprinter`, `AnalyzedImage`, `ImageFormat`); `shared.security` (`CurrentActor` c `isGuest()`/`userId()`/`hasRole(AppRole)`, `NotIdentifiedException`, `AccessDeniedException`, `AppRole`); бин `java.time.Clock` из итерации 0.
- Produces (для Tasks 4–6): `UploadMediaUseCase.upload(CurrentActor, byte[]) → MediaAssetResult`; `DownloadMediaUseCase.download(CurrentActor, UUID) → DownloadedMedia(assetId, mimeType, content)`; `DeleteMediaUseCase.delete(CurrentActor, UUID)`; порт `MediaAssetRepository.save/findById/delete`; `MediaAssetResult(id, ownerId, mimeType, byteSize, width, height, createdAt)`; исключения `FileTooLargeException` (413), `MediaAssetNotFoundException` (404); `MediaAssetService.MAX_BYTES = 10 MiB` (package-private static — для теста); фейки `InMemoryMediaAssetRepository` (поле `assets`, `failureOnSave`), `FakeImageAnalyzer` (поля `result`, `failure`), `InMemoryFileStorage` (поля `files`, `deletedKeys`).

- [ ] **Step 1: Написать красный application-тест**

`src/test/java/com/plantarena/media/application/MediaAssetServiceTest.java`:

```java
package com.plantarena.media.application;

import com.plantarena.media.application.support.FakeImageAnalyzer;
import com.plantarena.media.application.support.InMemoryFileStorage;
import com.plantarena.media.application.support.InMemoryMediaAssetRepository;
import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use cases media: загрузка, скачивание, удаление")
class MediaAssetServiceTest {

    private final InMemoryMediaAssetRepository repository = new InMemoryMediaAssetRepository();
    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final FakeImageAnalyzer analyzer = new FakeImageAnalyzer(
        new AnalyzedImage(ImageFormat.PNG, 2, 1, new int[] {0xFF000000, 0xFF112233}));
    private final MediaAssetService service =
        new MediaAssetService(repository, analyzer, storage, new MediaAccessPolicy(), Clock.systemUTC());

    private final UUID ownerId = UUID.randomUUID();
    private final CurrentActor owner = CurrentActor.identified(ownerId, Set.of(AppRole.USER));
    private final CurrentActor other =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));
    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));

    @Test
    void загрузка_создаёт_asset_с_отпечатком_и_сырым_хэшем() {
        MediaAssetResult result = service.upload(owner, new byte[] {1, 2, 3});

        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(result.byteSize()).isEqualTo(3);
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(1);
        assertThat(storage.files).hasSize(1);
        var asset = repository.assets.get(result.id());
        assertThat(asset).isNotNull();
        assertThat(asset.fingerprint().version()).isEqualTo(1);
        assertThat(asset.rawSha256()).hasSize(64);
    }

    @Test
    void файл_больше_10MiB_отклоняется_без_обращения_к_хранилищу() {
        byte[] tooLarge = new byte[(int) MediaAssetService.MAX_BYTES + 1];

        assertThatThrownBy(() -> service.upload(owner, tooLarge))
            .isInstanceOf(FileTooLargeException.class);
        assertThat(storage.files).isEmpty();
    }

    @Test
    void гость_не_может_загружать() {
        assertThatThrownBy(() -> service.upload(CurrentActor.guest(), new byte[] {1}))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void сбой_регистрации_метаданных_удаляет_сиротский_файл() {
        repository.failureOnSave = new IllegalStateException("БД недоступна");

        assertThatThrownBy(() -> service.upload(owner, new byte[] {1, 2, 3}))
            .isInstanceOf(IllegalStateException.class);
        assertThat(storage.files).isEmpty();
        assertThat(storage.deletedKeys).hasSize(1); // компенсация (раздел 12)
    }

    @Test
    void владелец_скачивает_своё_изображение() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});

        var downloaded = service.download(owner, uploaded.id());

        assertThat(downloaded.assetId()).isEqualTo(uploaded.id());
        assertThat(downloaded.mimeType()).isEqualTo("image/png");
        assertThat(downloaded.content()).containsExactly(1, 2, 3);
    }

    @Test
    void чужой_файл_скрыт_от_другого_пользователя() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.download(other, uploaded.id()))
            .isInstanceOf(MediaAssetNotFoundException.class); // скрыт приватностью (раздел 13)
        assertThatThrownBy(() -> service.download(CurrentActor.guest(), uploaded.id()))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void несуществующий_файл_не_найден() {
        assertThatThrownBy(() -> service.download(owner, UUID.randomUUID()))
            .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    void удаление_владельцем_убирает_файл_и_метаданные() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});

        service.delete(owner, uploaded.id());

        assertThat(repository.assets).isEmpty();
        assertThat(storage.files).isEmpty();
        assertThat(storage.deletedKeys).hasSize(1);
    }

    @Test
    void чужой_пользователь_не_удаляет_файл() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1});

        assertThatThrownBy(() -> service.delete(other, uploaded.id()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void админ_удаляет_файл() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1});

        service.delete(admin, uploaded.id());

        assertThat(repository.assets).isEmpty();
    }
}
```

- [ ] **Step 2: Убедиться, что тест красный**

```bash
./mvnw test -Dtest=MediaAssetServiceTest
```

Ожидание: COMPILATION ERROR — классы `media.application` не существуют.

- [ ] **Step 3: Реализовать application-слой и фейки**

`src/main/java/com/plantarena/media/application/port/in/UploadMediaUseCase.java`:

```java
package com.plantarena.media.application.port.in;

import com.plantarena.media.application.MediaAssetResult;
import com.plantarena.shared.security.CurrentActor;

/**
 * Загрузка файла (раздел 6): идентифицированный пользователь, multipart-байты →
 * валидация фактического формата/размеров → хранилище → метаданные.
 */
public interface UploadMediaUseCase {

    MediaAssetResult upload(CurrentActor actor, byte[] content);
}
```

`src/main/java/com/plantarena/media/application/port/in/DownloadMediaUseCase.java`:

```java
package com.plantarena.media.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/**
 * Скачивание изображения (раздел 13): приватные незаявленные файлы —
 * только владельцу (видимость по растениям — итерация 3).
 */
public interface DownloadMediaUseCase {

    DownloadedMedia download(CurrentActor actor, UUID assetId);

    record DownloadedMedia(UUID assetId, String mimeType, byte[] content) {}
}
```

`src/main/java/com/plantarena/media/application/port/in/DeleteMediaUseCase.java`:

```java
package com.plantarena.media.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/**
 * Удаление файла (раздел 13): владелец или админ; только незадействованный
 * файл — проверка задействованности появится с plants (итерация 3).
 */
public interface DeleteMediaUseCase {

    void delete(CurrentActor actor, UUID assetId);
}
```

`src/main/java/com/plantarena/media/application/port/out/MediaAssetRepository.java`:

```java
package com.plantarena.media.application.port.out;

import com.plantarena.media.domain.MediaAsset;
import java.util.Optional;
import java.util.UUID;

/** Выходной порт репозитория агрегата MediaAsset (раздел 5). */
public interface MediaAssetRepository {

    MediaAsset save(MediaAsset asset);

    Optional<MediaAsset> findById(UUID id);

    void delete(UUID id);
}
```

`src/main/java/com/plantarena/media/application/MediaAssetResult.java`:

```java
package com.plantarena.media.application;

import com.plantarena.media.domain.MediaAsset;
import java.time.Instant;
import java.util.UUID;

/**
 * Публичный результат загрузки (раздел 6): без storageKey и хэшей —
 * внутренние пути хранилища наружу не публикуются.
 */
public record MediaAssetResult(UUID id, UUID ownerId, String mimeType, long byteSize,
                               int width, int height, Instant createdAt) {

    public static MediaAssetResult from(MediaAsset asset) {
        return new MediaAssetResult(asset.id(), asset.ownerId(), asset.format().mimeType(),
            asset.byteSize(), asset.width(), asset.height(), asset.createdAt());
    }
}
```

`src/main/java/com/plantarena/media/application/FileTooLargeException.java`:

```java
package com.plantarena.media.application;

/**
 * Файл превышает 10 MiB (HTTP 413, разделы 6 и 13).
 * Доменное исключение единого языка, не знает об HTTP.
 */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/media/application/MediaAssetNotFoundException.java`:

```java
package com.plantarena.media.application;

/**
 * Файл не найден или скрыт политикой приватности (HTTP 404, раздел 13).
 */
public class MediaAssetNotFoundException extends RuntimeException {

    public MediaAssetNotFoundException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/media/application/MediaAccessPolicy.java`:

```java
package com.plantarena.media.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Права доступа media (раздел 13): загрузка — любой идентифицированный
 * пользователь; скачивание — только владелец (чужие файлы скрыты, 404);
 * удаление — владелец или админ (чужим — 403).
 */
@Component
public class MediaAccessPolicy {

    public void requireUploader(CurrentActor actor) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Загрузка файлов доступна только идентифицированным пользователям");
        }
    }

    public void requireViewer(CurrentActor actor, UUID ownerId) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Скачивание файлов доступно только идентифицированным пользователям");
        }
        if (!actor.userId().equals(ownerId)) {
            throw new MediaAssetNotFoundException("Файл не найден"); // скрыт приватностью
        }
    }

    public void requireDeleter(CurrentActor actor, UUID ownerId) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Удаление файлов доступно только идентифицированным пользователям");
        }
        if (!actor.userId().equals(ownerId) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Удалить файл может владелец или администратор");
        }
    }
}
```

`src/main/java/com/plantarena/media/application/MediaAssetService.java`:

```java
package com.plantarena.media.application;

import com.plantarena.media.application.port.in.DeleteMediaUseCase;
import com.plantarena.media.application.port.in.DownloadMediaUseCase;
import com.plantarena.media.application.port.in.UploadMediaUseCase;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.FileStorage;
import com.plantarena.media.domain.ImageAnalyzer;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFingerprinter;
import com.plantarena.media.domain.MediaAsset;
import com.plantarena.shared.security.CurrentActor;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Use cases media (раздел 6). Транзакции по разделу 12: файл сохраняется
 * в хранилище ДО короткой транзакции регистрации метаданных (в адаптере);
 * сбой регистрации компенсируется удалением сиротского файла.
 */
@Service
public class MediaAssetService implements UploadMediaUseCase, DownloadMediaUseCase, DeleteMediaUseCase {

    static final long MAX_BYTES = 10 * 1024 * 1024; // 10 MiB (раздел 6)

    private final MediaAssetRepository repository;
    private final ImageAnalyzer imageAnalyzer;
    private final FileStorage fileStorage;
    private final MediaAccessPolicy accessPolicy;
    private final Clock clock;
    private final ImageFingerprinter fingerprinter = new ImageFingerprinter();

    public MediaAssetService(MediaAssetRepository repository, ImageAnalyzer imageAnalyzer,
                             FileStorage fileStorage, MediaAccessPolicy accessPolicy, Clock clock) {
        this.repository = repository;
        this.imageAnalyzer = imageAnalyzer;
        this.fileStorage = fileStorage;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    public MediaAssetResult upload(CurrentActor actor, byte[] content) {
        accessPolicy.requireUploader(actor);
        if (content.length > MAX_BYTES) {
            throw new FileTooLargeException("Файл превышает 10 MiB: " + content.length + " байт");
        }
        AnalyzedImage image = imageAnalyzer.analyze(content); // 415 / 413 (пиксели)
        ImageFingerprint fingerprint = fingerprinter.fingerprint(image);
        String rawSha256 = fingerprinter.rawSha256(content);
        String storageKey = fileStorage.save(content, image.format());
        try {
            MediaAsset asset = MediaAsset.uploaded(actor.userId(), storageKey, image.format(),
                content.length, image.width(), image.height(), rawSha256, fingerprint,
                clock.instant());
            repository.save(asset); // короткая транзакция (раздел 12)
            return MediaAssetResult.from(asset);
        } catch (RuntimeException e) {
            fileStorage.delete(storageKey); // компенсация сиротского файла
            throw e;
        }
    }

    @Override
    public DownloadedMedia download(CurrentActor actor, UUID assetId) {
        MediaAsset asset = find(assetId);
        accessPolicy.requireViewer(actor, asset.ownerId());
        return new DownloadedMedia(asset.id(), asset.format().mimeType(),
            fileStorage.read(asset.storageKey()));
    }

    @Override
    public void delete(CurrentActor actor, UUID assetId) {
        MediaAsset asset = find(assetId);
        accessPolicy.requireDeleter(actor, asset.ownerId());
        repository.delete(asset.id());           // короткая транзакция
        fileStorage.delete(asset.storageKey());  // метаданные уже удалены — «висячих» ссылок нет
    }

    private MediaAsset find(UUID assetId) {
        return repository.findById(assetId)
            .orElseThrow(() -> new MediaAssetNotFoundException("Файл не найден: " + assetId));
    }
}
```

`src/test/java/com/plantarena/media/application/support/InMemoryMediaAssetRepository.java`:

```java
package com.plantarena.media.application.support;

import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.MediaAsset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory репозиторий для application-тестов (раздел 14.2). */
public class InMemoryMediaAssetRepository implements MediaAssetRepository {

    public final Map<UUID, MediaAsset> assets = new ConcurrentHashMap<>();
    public RuntimeException failureOnSave;

    @Override
    public MediaAsset save(MediaAsset asset) {
        if (failureOnSave != null) {
            throw failureOnSave;
        }
        assets.put(asset.id(), asset);
        return asset;
    }

    @Override
    public Optional<MediaAsset> findById(UUID id) {
        return Optional.ofNullable(assets.get(id));
    }

    @Override
    public void delete(UUID id) {
        assets.remove(id);
    }
}
```

`src/test/java/com/plantarena/media/application/support/FakeImageAnalyzer.java`:

```java
package com.plantarena.media.application.support;

import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.ImageAnalyzer;

/** Детерминированный анализатор для application-тестов: заданный результат. */
public class FakeImageAnalyzer implements ImageAnalyzer {

    public AnalyzedImage result;
    public RuntimeException failure;

    public FakeImageAnalyzer(AnalyzedImage result) {
        this.result = result;
    }

    @Override
    public AnalyzedImage analyze(byte[] content) {
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
```

`src/test/java/com/plantarena/media/application/support/InMemoryFileStorage.java`:

```java
package com.plantarena.media.application.support;

import com.plantarena.media.domain.FileStorage;
import com.plantarena.media.domain.ImageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory хранилище для application-тестов: помнит сохранённое и удалённое. */
public class InMemoryFileStorage implements FileStorage {

    public final Map<String, byte[]> files = new ConcurrentHashMap<>();
    public final List<String> deletedKeys = new ArrayList<>();

    @Override
    public String save(byte[] content, ImageFormat format) {
        String storageKey = UUID.randomUUID() + "." + format.extension();
        files.put(storageKey, content);
        return storageKey;
    }

    @Override
    public byte[] read(String storageKey) {
        byte[] content = files.get(storageKey);
        if (content == null) {
            throw new IllegalStateException("Файл не найден в хранилище: " + storageKey);
        }
        return content;
    }

    @Override
    public void delete(String storageKey) {
        files.remove(storageKey);
        deletedKeys.add(storageKey);
    }
}
```

- [ ] **Step 4: Убедиться, что тесты зелёные**

```bash
./mvnw test
```

Ожидание: BUILD SUCCESS — `MediaAssetServiceTest` (10) зелёный, домен и существующие тесты зелёные. `MediaAssetService` — `@Service`, но бины `ImageAnalyzer`/`FileStorage`/`MediaAssetRepository` появятся в Tasks 4–5; полный контекст пока не собирается — это нормально, `@SpringBootTest`-IT остаются красными до Task 6.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/plantarena/media/application src/test/java/com/plantarena/media/application
git commit -m "feat(media): application — use cases загрузки/скачивания/удаления, AccessPolicy"
```

---

### Task 4: Миграция media V2, JPA-адаптер, контрактные тесты репозитория

**Files:**
- Create: `src/main/resources/db/migration/media/V2__media_assets.sql`
- Create: `src/main/java/com/plantarena/media/adapter/out/persistence/MediaAssetJpaEntity.java`
- Create: `src/main/java/com/plantarena/media/adapter/out/persistence/MediaAssetJpaRepository.java`
- Create: `src/main/java/com/plantarena/media/adapter/out/persistence/MediaAssetMapper.java`
- Create: `src/main/java/com/plantarena/media/adapter/out/persistence/JpaMediaAssetRepository.java`
- Test: `src/test/java/com/plantarena/media/MediaAssetRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/media/application/support/InMemoryMediaAssetRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/media/adapter/out/persistence/JpaMediaAssetRepositoryContractIT.java`

**Interfaces:**
- Consumes: домен Task 2 (`MediaAsset.restore`, `ImageFormat.fromMimeType`), порт `MediaAssetRepository` Task 3, `SchemaMigrationConfig` (контекст `media` уже в `CONTEXT_SCHEMAS`, каталог `db/migration/media` уже существует с V1), паттерн контрактных тестов `UserRepositoryContractTest`/`JpaUserRepositoryContractIT` из итерации 1.
- Produces: таблица `media.media_asset` (колонки раздела 11: id, owner_id, storage_key UNIQUE, mime_type, byte_size, width, height, raw_sha256, image_fingerprint, fingerprint_version, created_at); Spring-бин `JpaMediaAssetRepository` (`@Repository`) — закрывает зависимость `MediaAssetService` по `MediaAssetRepository`.

- [ ] **Step 1: Написать миграцию**

`src/main/resources/db/migration/media/V2__media_assets.sql`:

```sql
-- Контекст media: неизменяемые загруженные файлы (разделы 6 и 11 требований).
-- Enum → VARCHAR + CHECK; агрегат неизменяем — version (optimistic locking) не нужен.
-- Индексы: по отпечатку (запреты повторного использования, итерация 3)
-- и по владельцу со временем загрузки.
CREATE TABLE media_asset (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    storage_key         VARCHAR(255) NOT NULL UNIQUE,
    mime_type           VARCHAR(20)  NOT NULL CHECK (mime_type IN ('image/jpeg', 'image/png')),
    byte_size           BIGINT       NOT NULL CHECK (byte_size BETWEEN 1 AND 10485760),
    width               INTEGER      NOT NULL CHECK (width > 0),
    height              INTEGER      NOT NULL CHECK (height > 0),
    raw_sha256          CHAR(64)     NOT NULL,
    image_fingerprint   CHAR(64)     NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX media_asset_owner_idx ON media_asset (owner_id, created_at);
CREATE INDEX media_asset_fingerprint_idx ON media_asset (image_fingerprint);
```

- [ ] **Step 2: Написать контрактный тест (красный)**

`src/test/java/com/plantarena/media/MediaAssetRepositoryContractTest.java`:

```java
package com.plantarena.media;

import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.MediaAsset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт MediaAssetRepository (раздел 14.2): одинаковые гарантии у in-memory
 * фейка (application-тесты) и JPA + PostgreSQL (adapter-тесты) — честность фейка.
 */
@DisplayName("Контракт MediaAssetRepository")
public abstract class MediaAssetRepositoryContractTest {

    protected abstract MediaAssetRepository repository();

    protected MediaAsset sampleAsset(String storageKey) {
        return MediaAsset.uploaded(UUID.randomUUID(), storageKey, ImageFormat.PNG, 123,
            8, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1),
            Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    @DisplayName("сохранение и чтение по id")
    void сохранение_и_чтение_по_id() {
        MediaAsset asset = sampleAsset("key-1.png");

        repository().save(asset);
        MediaAsset loaded = repository().findById(asset.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(asset.id());
        assertThat(loaded.ownerId()).isEqualTo(asset.ownerId());
        assertThat(loaded.storageKey()).isEqualTo("key-1.png");
        assertThat(loaded.format()).isEqualTo(ImageFormat.PNG);
        assertThat(loaded.byteSize()).isEqualTo(123);
        assertThat(loaded.width()).isEqualTo(8);
        assertThat(loaded.height()).isEqualTo(8);
        assertThat(loaded.rawSha256()).isEqualTo("a".repeat(64));
        assertThat(loaded.fingerprint()).isEqualTo(new ImageFingerprint("b".repeat(64), 1));
        assertThat(loaded.createdAt()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    @DisplayName("несуществующий id — пустой результат")
    void несуществующий_id_пустой_результат() {
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("удаление убирает asset")
    void удаление_убирает_asset() {
        MediaAsset asset = sampleAsset("key-2.png");
        repository().save(asset);

        repository().delete(asset.id());

        assertThat(repository().findById(asset.id())).isEmpty();
    }
}
```

`src/test/java/com/plantarena/media/application/support/InMemoryMediaAssetRepositoryContractTest.java`:

```java
package com.plantarena.media.application.support;

import com.plantarena.media.MediaAssetRepositoryContractTest;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт MediaAssetRepository: in-memory фейк")
class InMemoryMediaAssetRepositoryContractTest extends MediaAssetRepositoryContractTest {

    private final InMemoryMediaAssetRepository repository = new InMemoryMediaAssetRepository();

    @Override
    protected MediaAssetRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/media/adapter/out/persistence/JpaMediaAssetRepositoryContractIT.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.media.MediaAssetRepositoryContractTest;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт MediaAssetRepository на JPA + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 * Контейнер singleton на JVM: предыдущие @SpringBootTest-контексты коммитят
 * asset'ы в общую БД, поэтому перед каждым тестом таблица чистится
 * (внутри откатываемой транзакции @DataJpaTest — данные восстанавливаются).
 */
@DisplayName("Контракт MediaAssetRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaMediaAssetRepository.class})
class JpaMediaAssetRepositoryContractIT extends MediaAssetRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaMediaAssetRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_файлы_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from media.media_asset");
    }

    @Override
    protected MediaAssetRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("дубликат storage_key отклоняется ограничением БД (UNIQUE)")
    void дубликат_storage_key_отклоняется_ограничением_бд() {
        repository().save(sampleAsset("same-key.png"));
        MediaAsset duplicate = sampleAsset("same-key.png");

        assertThatThrownBy(() -> repository().save(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 3: Убедиться, что контрактный тест красный**

```bash
./mvnw test -Dtest='MediaAssetRepositoryContractTest,InMemoryMediaAssetRepositoryContractTest'
```

Ожидание: COMPILATION ERROR — `JpaMediaAssetRepository` не существует (наследник JPA не компилируется).

- [ ] **Step 4: Реализовать JPA-адаптер**

`src/main/java/com/plantarena/media/adapter/out/persistence/MediaAssetJpaEntity.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель media_asset (раздел 11); маппинг на домен — явный (MediaAssetMapper).
 * Агрегат неизменяем — entity только создаётся и читается.
 */
@Entity
@Table(name = "media_asset", schema = "media")
public class MediaAssetJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "raw_sha256", nullable = false)
    private String rawSha256;

    @Column(name = "image_fingerprint", nullable = false)
    private String imageFingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MediaAssetJpaEntity() {
    }

    MediaAssetJpaEntity(UUID id, UUID ownerId, String storageKey, String mimeType, long byteSize,
                        int width, int height, String rawSha256, String imageFingerprint,
                        int fingerprintVersion, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.rawSha256 = rawSha256;
        this.imageFingerprint = imageFingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getRawSha256() {
        return rawSha256;
    }

    public String getImageFingerprint() {
        return imageFingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`src/main/java/com/plantarena/media/adapter/out/persistence/MediaAssetJpaRepository.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data репозиторий JPA-модели media_asset (только внутри адаптера, правило 10.2.6). */
public interface MediaAssetJpaRepository extends JpaRepository<MediaAssetJpaEntity, UUID> {
}
```

`src/main/java/com/plantarena/media/adapter/out/persistence/MediaAssetMapper.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.MediaAsset;

/** Явный маппинг домен ↔ JPA (раздел 5). */
public final class MediaAssetMapper {

    private MediaAssetMapper() {
    }

    public static MediaAssetJpaEntity toEntity(MediaAsset asset) {
        return new MediaAssetJpaEntity(asset.id(), asset.ownerId(), asset.storageKey(),
            asset.format().mimeType(), asset.byteSize(), asset.width(), asset.height(),
            asset.rawSha256(), asset.fingerprint().value(), asset.fingerprint().version(),
            asset.createdAt());
    }

    public static MediaAsset toDomain(MediaAssetJpaEntity entity) {
        return MediaAsset.restore(entity.getId(), entity.getOwnerId(), entity.getStorageKey(),
            ImageFormat.fromMimeType(entity.getMimeType()), entity.getByteSize(),
            entity.getWidth(), entity.getHeight(), entity.getRawSha256(),
            new ImageFingerprint(entity.getImageFingerprint(), entity.getFingerprintVersion()),
            entity.getCreatedAt());
    }
}
```

`src/main/java/com/plantarena/media/adapter/out/persistence/JpaMediaAssetRepository.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.MediaAsset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта MediaAssetRepository на JPA + PostgreSQL (раздел 14.2).
 * save — короткая транзакция регистрации метаданных (раздел 12).
 */
@Repository
@Transactional
public class JpaMediaAssetRepository implements MediaAssetRepository {

    private final MediaAssetJpaRepository jpaRepository;

    public JpaMediaAssetRepository(MediaAssetJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MediaAsset save(MediaAsset asset) {
        return MediaAssetMapper.toDomain(
            jpaRepository.saveAndFlush(MediaAssetMapper.toEntity(asset)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MediaAsset> findById(UUID id) {
        return jpaRepository.findById(id).map(MediaAssetMapper::toDomain);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }
}
```

- [ ] **Step 5: Прогнать контрактные тесты (unit + JPA IT)**

```bash
./mvnw test
./mvnw verify -Dit.test=JpaMediaAssetRepositoryContractIT
```

Ожидание: оба BUILD SUCCESS — контрактные тесты фейка (3) и JPA (3 + дубликат storage_key) зелёные; миграция `media/V2` накатывается SchemaMigrationConfig; `MediaApiIT` по-прежнему красный (не запускается: фильтр `-Dit.test` оставляет только контрактный IT).

- [ ] **Step 6: Коммит**

```bash
git add src/main/resources/db/migration/media src/main/java/com/plantarena/media/adapter \
  src/test/java/com/plantarena/media
git commit -m "feat(media): миграция V2 media_asset, JPA-адаптер, контрактные тесты репозитория"
```

---

### Task 5: Адаптеры out — анализ javax.imageio и локальное файловое хранилище

**Files:**
- Create: `src/main/java/com/plantarena/media/adapter/out/image/JavaxImageAnalyzer.java`
- Create: `src/main/java/com/plantarena/media/adapter/out/storage/LocalFileStorage.java`
- Test: `src/test/java/com/plantarena/media/adapter/out/image/JavaxImageAnalyzerTest.java`
- Test: `src/test/java/com/plantarena/media/adapter/out/storage/LocalFileStorageTest.java`

**Interfaces:**
- Consumes: порты `ImageAnalyzer`/`FileStorage` из Task 2; `ImageFormat`/`AnalyzedImage`; доменные исключения `UnsupportedImageFormatException`/`ImageResolutionTooHighException`.
- Produces: Spring-бины `JavaxImageAnalyzer` (`@Component`, реализует `ImageAnalyzer`, `MAX_PIXELS = 20_000_000`) и `LocalFileStorage` (`@Component`, реализует `FileStorage`, корень из свойства `plantarena.media.storage.root`) — закрывают оставшиеся зависимости `MediaAssetService` из Task 3.

- [ ] **Step 1: Написать красные тесты адаптеров**

`src/test/java/com/plantarena/media/adapter/out/image/JavaxImageAnalyzerTest.java`:

```java
package com.plantarena.media.adapter.out.image;

import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Анализ фактического содержимого изображения (javax.imageio)")
class JavaxImageAnalyzerTest {

    private final JavaxImageAnalyzer analyzer = new JavaxImageAnalyzer();

    @Test
    void png_принимается_с_фактическими_размерами() {
        byte[] png = image("png", 3, 2);

        var analyzed = analyzer.analyze(png);

        assertThat(analyzed.format()).isEqualTo(ImageFormat.PNG);
        assertThat(analyzed.width()).isEqualTo(3);
        assertThat(analyzed.height()).isEqualTo(2);
        assertThat(analyzed.argb()).hasSize(6);
    }

    @Test
    void jpeg_принимается() {
        byte[] jpeg = image("jpg", 2, 2);

        var analyzed = analyzer.analyze(jpeg);

        assertThat(analyzed.format()).isEqualTo(ImageFormat.JPEG);
        assertThat(analyzed.width()).isEqualTo(2);
    }

    @Test
    void gif_отклоняется_несмотря_на_поддержку_декодирования() {
        byte[] gif = image("gif", 2, 2);

        assertThatThrownBy(() -> analyzer.analyze(gif))
            .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void произвольные_байты_отклоняются() {
        assertThatThrownBy(() -> analyzer.analyze("точно не изображение".getBytes()))
            .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void больше_20Mpx_отклоняется_по_заголовку() {
        byte[] huge = image("png", 5000, 4200); // 21M пикселей, но крошечный файл

        assertThatThrownBy(() -> analyzer.analyze(huge))
            .isInstanceOf(ImageResolutionTooHighException.class);
    }

    private byte[] image(String format, int width, int height) {
        try {
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = buffered.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, width, height);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(buffered, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`src/test/java/com/plantarena/media/adapter/out/storage/LocalFileStorageTest.java`:

```java
package com.plantarena.media.adapter.out.storage;

import com.plantarena.media.domain.ImageFormat;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Локальное файловое хранилище (порт FileStorage)")
class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage;

    @BeforeEach
    void создать_хранилище() {
        storage = new LocalFileStorage(root.toString());
    }

    @Test
    void сохранение_возвращает_новый_ключ_и_читается_обратно() {
        String key = storage.save(new byte[] {1, 2, 3}, ImageFormat.PNG);

        assertThat(key).endsWith(".png");
        assertThat(storage.read(key)).containsExactly(1, 2, 3);
    }

    @Test
    void повторное_сохранение_не_перезаписывает_файл() {
        String first = storage.save(new byte[] {1}, ImageFormat.PNG);
        String second = storage.save(new byte[] {2}, ImageFormat.PNG);

        assertThat(first).isNotEqualTo(second);
        assertThat(storage.read(first)).containsExactly(1);
        assertThat(storage.read(second)).containsExactly(2);
    }

    @Test
    void удаление_убирает_файл() {
        String key = storage.save(new byte[] {1}, ImageFormat.JPEG);

        storage.delete(key);

        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void чтение_несуществующего_файла_отклоняется() {
        assertThatThrownBy(() -> storage.read("missing.png"))
            .isInstanceOf(UncheckedIOException.class);
    }
}
```

- [ ] **Step 2: Убедиться, что тесты красные**

```bash
./mvnw test -Dtest='JavaxImageAnalyzerTest,LocalFileStorageTest'
```

Ожидание: COMPILATION ERROR — адаптеры не существуют.

- [ ] **Step 3: Реализовать адаптеры**

`src/main/java/com/plantarena/media/adapter/out/image/JavaxImageAnalyzer.java`:

```java
package com.plantarena.media.adapter.out.image;

import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.ImageAnalyzer;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * Анализ фактического содержимого на javax.imageio (JDK): формат определяется
 * зарегистрированным reader'ом — не расширением и не MIME клиента (раздел 6).
 * Размеры читаются по заголовку ДО декодирования растра — большие буферы
 * под превышающие лимит изображения не выделяются.
 */
@Component
public class JavaxImageAnalyzer implements ImageAnalyzer {

    static final long MAX_PIXELS = 20_000_000; // 20 миллионов пикселей (раздел 6)

    static {
        ImageIO.setUseCache(false); // не писать временные файлы при декодировании
    }

    @Override
    public AnalyzedImage analyze(byte[] content) {
        try (ImageInputStream input =
                 ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new UnsupportedImageFormatException(
                    "Файл не является изображением: разрешены JPEG и PNG");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UnsupportedImageFormatException(
                    "Формат файла не поддерживается: разрешены JPEG и PNG");
            }
            ImageReader reader = readers.next();
            try {
                ImageFormat format = formatOf(reader);
                reader.setInput(input);
                int width = reader.getWidth(0);   // по заголовку, без декодирования
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new ImageResolutionTooHighException(
                        "Изображение превышает 20 миллионов пикселей: " + width + "x" + height);
                }
                int[] argb = reader.read(0).getRGB(0, 0, width, height, null, 0, width);
                return new AnalyzedImage(format, width, height, argb);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                "Файл не является изображением: разрешены JPEG и PNG");
        }
    }

    private ImageFormat formatOf(ImageReader reader) throws IOException {
        String formatName = reader.getFormatName().toUpperCase(Locale.ROOT);
        return switch (formatName) {
            case "JPEG", "JPG" -> ImageFormat.JPEG;
            case "PNG" -> ImageFormat.PNG;
            default -> throw new UnsupportedImageFormatException(
                "Формат файла не поддерживается (" + formatName + "): разрешены JPEG и PNG");
        };
    }
}
```

`src/main/java/com/plantarena/media/adapter/out/storage/LocalFileStorage.java`:

```java
package com.plantarena.media.adapter.out.storage;

import com.plantarena.media.domain.FileStorage;
import com.plantarena.media.domain.ImageFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Локальное файловое хранилище за портом FileStorage (раздел 6): Docker volume
 * в лабе №1; в лабе №4 заменяется на file-service с S3-совместимым хранилищем —
 * меняется только этот адаптер. Ключи генерирует хранилище (UUID + расширение),
 * перезапись невозможна (CREATE_NEW). Ключи системные — наружу не публикуются.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${plantarena.media.storage.root}") String root) {
        this.root = Path.of(root);
    }

    @Override
    public String save(byte[] content, ImageFormat format) {
        String storageKey = UUID.randomUUID() + "." + format.extension();
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(storageKey), content,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return storageKey;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить файл в хранилище", e);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(root.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Файл не найден в хранилище: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(root.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось удалить файл из хранилища", e);
        }
    }
}
```

- [ ] **Step 4: Убедиться, что тесты зелёные**

```bash
./mvnw test
```

Ожидание: BUILD SUCCESS — `JavaxImageAnalyzerTest` (5), `LocalFileStorageTest` (4) зелёные; все предыдущие зелёные.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/plantarena/media/adapter src/test/java/com/plantarena/media/adapter
git commit -m "feat(media): адаптеры javax.imageio и локального файлового хранилища"
```

---

### Task 6: REST /files — контроллер, обработчики ошибок, конфигурация; приёмочный IT зелёный

**Files:**
- Create: `src/main/java/com/plantarena/media/adapter/in/web/MediaAssetResponse.java`
- Create: `src/main/java/com/plantarena/media/adapter/in/web/MediaController.java`
- Create: `src/main/java/com/plantarena/media/adapter/in/web/MediaExceptionHandler.java`
- Modify: `src/main/java/com/plantarena/shared/web/ApiExceptionHandler.java` (добавить 2 обработчика)
- Modify: `src/main/resources/application.yml` (multipart + свойство хранилища)
- Test: `src/test/java/com/plantarena/media/adapter/in/web/MediaExceptionHandlerTest.java`
- Test: `src/test/java/com/plantarena/shared/web/ApiExceptionHandlerTest.java` (добавить 2 теста)

**Interfaces:**
- Consumes: use cases Task 3 (`UploadMediaUseCase`, `DownloadMediaUseCase`, `DeleteMediaUseCase`, `MediaAssetResult`, `DownloadedMedia`), исключения media, `CurrentActorProvider` из shared (реализация — identity, ADR-005), `ApiError`/`TraceIdFilter` из shared.web, паттерн `IdentityExceptionHandler`/`UserController` из итерации 1.
- Produces: REST `/api/v1/files` (POST multipart → 201 + Location + `MediaAssetResponse`; GET → байты с фактическим Content-Type; DELETE → 204); стабильные коды ошибок `UNSUPPORTED_IMAGE_FORMAT` (415), `FILE_TOO_LARGE` (413), `IMAGE_TOO_LARGE` (413), `MEDIA_ASSET_NOT_FOUND` (404), `MISSING_PART` (400), `UNSUPPORTED_MEDIA_TYPE` (415); свойство `plantarena.media.storage.root` (ENV `MEDIA_STORAGE_ROOT`); `MediaApiIT` зелёный.

- [ ] **Step 1: Написать красные тесты обработчиков**

`src/test/java/com/plantarena/media/adapter/in/web/MediaExceptionHandlerTest.java`:

```java
package com.plantarena.media.adapter.in.web;

import com.plantarena.media.application.FileTooLargeException;
import com.plantarena.media.application.MediaAssetNotFoundException;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import com.plantarena.shared.web.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ошибки media: 415 формат, 413 размер, 404 не найден")
class MediaExceptionHandlerTest {

    private final MediaExceptionHandler handler = new MediaExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/files");

    @Test
    void неподдерживаемый_формат_даёт_415_unsupported_image_format() {
        ResponseEntity<ApiError> response = handler.unsupportedFormat(
            new UnsupportedImageFormatException("Разрешены JPEG и PNG"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_IMAGE_FORMAT");
    }

    @Test
    void превышение_байт_даёт_413_file_too_large() {
        ResponseEntity<ApiError> response = handler.tooLarge(
            new FileTooLargeException("Файл превышает 10 MiB"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void превышение_пикселей_даёт_413_image_too_large() {
        ResponseEntity<ApiError> response = handler.tooManyPixels(
            new ImageResolutionTooHighException("Больше 20 миллионов пикселей"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IMAGE_TOO_LARGE");
    }

    @Test
    void отсутствующий_файл_даёт_404_media_asset_not_found() {
        ResponseEntity<ApiError> response = handler.notFound(
            new MediaAssetNotFoundException("Файл не найден"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MEDIA_ASSET_NOT_FOUND");
    }
}
```

Добавить в конец `src/test/java/com/plantarena/shared/web/ApiExceptionHandlerTest.java` (перед закрывающей скобкой класса; импорты `org.springframework.web.multipart.support.MissingServletRequestPartException` и `org.springframework.http.MediaType`, `org.springframework.web.HttpMediaTypeNotSupportedException` добавить к существующим):

```java
    @Test
    void отсутствующая_часть_multipart_даёт_400_missing_part() {
        MissingServletRequestPartException missing =
            new MissingServletRequestPartException("file");

        ResponseEntity<ApiError> response = handler.missingPart(missing, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MISSING_PART");
        assertThat(response.getBody().detail()).contains("file");
    }

    @Test
    void неподдерживаемый_тип_запроса_даёт_415_unsupported_media_type() {
        HttpMediaTypeNotSupportedException unsupported =
            new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiError> response = handler.unsupportedMediaType(unsupported, request);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }
```

- [ ] **Step 2: Убедиться, что тесты красные**

```bash
./mvnw test -Dtest='MediaExceptionHandlerTest,ApiExceptionHandlerTest'
```

Ожидание: COMPILATION ERROR — `MediaExceptionHandler` и новые методы `ApiExceptionHandler` не существуют.

- [ ] **Step 3: Реализовать web-слой**

`src/main/java/com/plantarena/media/adapter/in/web/MediaAssetResponse.java`:

```java
package com.plantarena.media.adapter.in.web;

import com.plantarena.media.application.MediaAssetResult;
import java.time.Instant;
import java.util.UUID;

/** Ответ POST /files: метаданные без storageKey и хэшей (раздел 6). */
public record MediaAssetResponse(UUID id, UUID ownerId, String mimeType, long byteSize,
                                 int width, int height, Instant createdAt) {

    public static MediaAssetResponse from(MediaAssetResult result) {
        return new MediaAssetResponse(result.id(), result.ownerId(), result.mimeType(),
            result.byteSize(), result.width(), result.height(), result.createdAt());
    }
}
```

`src/main/java/com/plantarena/media/adapter/in/web/MediaController.java`:

```java
package com.plantarena.media.adapter.in.web;

import com.plantarena.media.application.MediaAssetResult;
import com.plantarena.media.application.port.in.DeleteMediaUseCase;
import com.plantarena.media.application.port.in.DownloadMediaUseCase;
import com.plantarena.media.application.port.in.UploadMediaUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ручки файлов (раздел 13). Контроллер обращается только к входным портам
 * application (правило 10.2.7); формат и размеры проверяет use case по
 * фактическому содержимому, а не по имени/MIME части запроса.
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "media")
public class MediaController {

    private final UploadMediaUseCase uploadMedia;
    private final DownloadMediaUseCase downloadMedia;
    private final DeleteMediaUseCase deleteMedia;
    private final CurrentActorProvider currentActorProvider;

    public MediaController(UploadMediaUseCase uploadMedia, DownloadMediaUseCase downloadMedia,
                           DeleteMediaUseCase deleteMedia, CurrentActorProvider currentActorProvider) {
        this.uploadMedia = uploadMedia;
        this.downloadMedia = downloadMedia;
        this.deleteMedia = deleteMedia;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "media-upload-file",
        summary = "Загрузить изображение (JPEG/PNG, до 10 MiB и 20 млн пикселей; USER и выше)")
    public ResponseEntity<MediaAssetResponse> upload(@RequestParam("file") MultipartFile file)
        throws IOException {
        CurrentActor actor = currentActorProvider.currentActor();
        MediaAssetResult result = uploadMedia.upload(actor, file.getBytes());
        return ResponseEntity
            .created(URI.create("/api/v1/files/" + result.id()))
            .body(MediaAssetResponse.from(result));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "media-download-file",
        summary = "Получить изображение (владелец; байты с фактическим Content-Type)")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        DownloadMediaUseCase.DownloadedMedia media = downloadMedia.download(actor, id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(media.mimeType()))
            .contentLength(media.content().length)
            .body(media.content());
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "media-delete-file",
        summary = "Удалить незадействованный файл (владелец/админ)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        deleteMedia.delete(actor, id);
        return ResponseEntity.noContent().build();
    }
}
```

`src/main/java/com/plantarena/media/adapter/in/web/MediaExceptionHandler.java`:

```java
package com.plantarena.media.adapter.in.web;

import com.plantarena.media.application.FileTooLargeException;
import com.plantarena.media.application.MediaAssetNotFoundException;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Перевод исключений media в ProblemDetail-подобное тело (раздел 13:
 * 415 формат, 413 размер файла/пикселей, 404 не найден/скрыт).
 * Живёт в adapter.in.web: shared не зависит от контекстов (правило 10.2.8).
 */
@RestControllerAdvice
public class MediaExceptionHandler {

    @ExceptionHandler(UnsupportedImageFormatException.class)
    public ResponseEntity<ApiError> unsupportedFormat(UnsupportedImageFormatException e,
                                                      HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE_FORMAT",
            e.getMessage(), request);
    }

    /** Общий предел 10 MiB: проверка use case и предел Spring multipart — один код. */
    @ExceptionHandler({FileTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> tooLarge(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
            "Файл превышает ограничение загрузки 10 MiB", request);
    }

    @ExceptionHandler(ImageResolutionTooHighException.class)
    public ResponseEntity<ApiError> tooManyPixels(ImageResolutionTooHighException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", e.getMessage(), request);
    }

    @ExceptionHandler(MediaAssetNotFoundException.class)
    public ResponseEntity<ApiError> notFound(MediaAssetNotFoundException e,
                                             HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "MEDIA_ASSET_NOT_FOUND", e.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                              HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            URI.create(request.getRequestURI()), code, List.of(), traceId));
    }
}
```

Изменения `src/main/java/com/plantarena/shared/web/ApiExceptionHandler.java` — добавить два обработчика перед `unexpected(...)` (и импорты `org.springframework.web.HttpMediaTypeNotSupportedException`, `org.springframework.web.multipart.support.MissingServletRequestPartException`):

```java
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException e,
                                                HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MISSING_PART",
            "Отсутствует часть запроса: " + e.getRequestPartName(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException e,
                                                         HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
            "Тип содержимого запроса не поддерживается", request);
    }
```

Полное новое содержимое `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: plant-arena
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/plantarena}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: false # миграции по схемам контекстов выполняет config.SchemaMigrationConfig (ADR-003)
  servlet:
    multipart:
      max-file-size: 10MB   # предел раздела 6; нарушение → 413 FILE_TOO_LARGE (MediaExceptionHandler)
      max-request-size: 10MB

server:
  port: ${SERVER_PORT:8080}

management:
  endpoints:
    web:
      exposure:
        include: health

plantarena:
  bootstrap-admin:
    email: ${BOOTSTRAP_ADMIN_EMAIL:}
    password: ${BOOTSTRAP_ADMIN_PASSWORD:}
    display-name: ${BOOTSTRAP_ADMIN_DISPLAY_NAME:Admin}
  media:
    storage:
      root: ${MEDIA_STORAGE_ROOT:./storage/media} # Docker volume; file-service/S3 — лаба №4
```

- [ ] **Step 4: Прогнать полный verify — приёмочный IT зелёный**

```bash
./mvnw verify
```

Ожидание: BUILD SUCCESS — `MediaApiIT` (14 тестов) зелёный: загрузка 201 + метаданные без storageKey, гость 401, текстовые байты 415, >10MiB 413 FILE_TOO_LARGE, >20Mpx 413 IMAGE_TOO_LARGE, отпечаток игнорирует метаданные (равен) при разных rawSha256, повторная загрузка — новый asset, скачивание владельцем 200 image/png, чужой 404/гость 401, удаление владельцем/админом 204, чужой 403, без части file 400 MISSING_PART, не multipart 415 UNSUPPORTED_MEDIA_TYPE. Все прежние IT (identity и т. д.) зелёные, ArchUnit зелёный, JaCoCo gate зелёный.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/plantarena/media/adapter/in/web \
  src/main/java/com/plantarena/shared/web/ApiExceptionHandler.java \
  src/main/resources/application.yml \
  src/test/java/com/plantarena/media/adapter/in/web \
  src/test/java/com/plantarena/shared/web/ApiExceptionHandlerTest.java
git commit -m "feat(media): REST /files — multipart-загрузка, 413/415, скачивание, удаление"
```

---

### Task 7: Документация и развёртывание — ADR-007, глоссарий, агрегаты, README, Compose

**Files:**
- Create: `docs/domain/adr/ADR-007-image-fingerprint-v1.md`
- Modify: `docs/domain/glossary.md` (добавить строку `FileStorage`)
- Modify: `docs/domain/aggregates.md` (заполнить защищающие тесты media)
- Modify: `README.md` (раздел «Файлы (media)»)
- Modify: `.env.example` (добавить `MEDIA_STORAGE_ROOT`)
- Modify: `docker-compose.yml` (ENV `MEDIA_STORAGE_ROOT`)

**Interfaces:**
- Consumes: реализация итерации 2 (Tasks 1–6); правила docs-first (раздел 0: новые понятия сначала в глоссарий; изменения — сначала в docs).
- Produces: зафиксированный алгоритм отпечатка v1 (ADR-007); реестр агрегатов с защищающими тестами media; ENV хранилища в Compose.

- [ ] **Step 1: ADR-007 — алгоритм отпечатка v1 и локальное хранилище**

`docs/domain/adr/ADR-007-image-fingerprint-v1.md`:

```markdown
# ADR-007: Алгоритм отпечатка изображения v1 и локальное хранилище

Статус: принято (итерация 2). Область: контекст media.

## Контекст

Раздел 6 требований: `imageFingerprint` вычисляется по нормализованному декодированному
представлению с версией алгоритма; отпечаток должен игнорировать метаданные при одинаковых
нормализованных пикселях; алгоритм и эталонные примеры фиксируются; `rawSha256` выявляет
одинаковые байты; perceptual matching не требуется и не обещается.

## Решение

### Отпечаток v1

`ImageFingerprinter.fingerprint(AnalyzedImage)` — SHA-256 по раскладке:

1. префикс `plantarena-image-fingerprint-v1` (ASCII);
2. ширина и высота — int64 big-endian;
3. для каждого пикселя построчно (row-major) три байта R, G, B.

Свойства v1:

- альфа-канал игнорируется (прозрачность не влияет на отпечаток);
- порядок каналов зафиксирован R→G→B;
- EXIF-ориентация НЕ применяется: ориентация — метаданные, повёрнутая повторная
  загрузка даёт другой отпечаток (допустимо: perceptual matching не требуется);
- ресайз, обрезка и перекодирование меняют пиксели и отпечаток — совпадение
  не гарантируется (осознанно; криптографический хэш не есть распознавание растения);
- метаданные (PNG tEXt, EXIF) в хэш не входят: файлы с одинаковыми пикселями и разными
  метаданными дают одинаковый отпечаток; `rawSha256` при этом различается.

Любое изменение раскладки требует новой версии (`fingerprint_version`); старые отпечатки
остаются валидными для поиска запретов по паре (ownerId, fingerprint) в plants (итерация 3).

### Эталонные примеры

`src/test/resources/media/reference/`: `red-8x8.png` и `red-8x8-with-comment.png` —
одинаковые пиксели (байт-в-байт одинаковый IDAT), разные метаданные (чанк tEXt Comment).
Приёмочный тест `отпечаток_игнорирует_метаданные_файла` (MediaApiIT) фиксирует равенство
отпечатков и различие rawSha256. Сам алгоритм закреплён независимым вычислением ожидаемого
хэша в `ImageFingerprintTest.алгоритм_v1_зафиксирован_независимым_вычислением`.

### Локальное хранилище

Порт `FileStorage`, адаптер `LocalFileStorage`: корень — `plantarena.media.storage.root`
(ENV `MEDIA_STORAGE_ROOT`, Docker volume в Compose). Ключи генерирует хранилище
(UUID + расширение), перезапись невозможна (CREATE_NEW), ключи наружу не публикуются.
В лабе №4 адаптер заменяется на file-service с S3-совместимым хранилищем — домен и use
cases не меняются. Транзакции (раздел 12): файл сохраняется до короткой транзакции
регистрации метаданных; сбой регистрации компенсируется удалением сиротского файла.

## Последствия

- Запреты повторного использования (plants, итерация 3) ищутся по отпечатку с учётом версии.
- Одинаковые байты у разных владельцев выявляются `rawSha256`; запреты — только по паре
  (ownerId, fingerprint) (допущение 3 раздела 3).
```

- [ ] **Step 2: Глоссарий и реестр агрегатов**

В `docs/domain/glossary.md` добавить строку в таблицу (после строки `Отпечаток изображения`):

```markdown
| Хранилище файлов | `FileStorage` | media | Порт хранения байтов: локальный volume в лабе №1, file-service/S3 в лабе №4 |
```

В `docs/domain/aggregates.md` заменить строку media:

```markdown
| media | `MediaAsset` | метаданные файла | Неизменяем после создания | загрузить файл | — | `MediaAssetTest` (неизменяемость/фабрики), `ImageFingerprintTest` (алгоритм v1: альфа/каналы/размеры), `MediaAssetServiceTest` (компенсация сиротского файла, права), `MediaApiIT` (через HTTP: форматы/размеры/отпечаток игнорирует метаданные) |
```

- [ ] **Step 3: README, .env.example, docker-compose**

В `README.md` добавить раздел после раздела о демо-идентификации:

```markdown
## Файлы (media)

Загрузка изображения — `POST /api/v1/files` (multipart, часть `file`): JPEG/PNG,
до 10 MiB и 20 млн пикселей, формат проверяется по фактическому содержимому.
Ответ: `assetId` и метаданные (без внутренних путей хранилища). Повторная загрузка
тех же байтов создаёт новый asset. Отпечаток изображения (алгоритм v1) игнорирует
метаданные и альфа-канал — ADR-007; эталонные примеры: `src/test/resources/media/reference`.

- Скачивание — `GET /api/v1/files/{id}`: приватные незаявленные файлы доступны только
  владельцу (видимость по растениям появится в итерации 3).
- Удаление — `DELETE /api/v1/files/{id}`: владелец или админ; проверка
  «незадействованности» появится вместе с plants (итерация 3).

Локальное хранилище: каталог `MEDIA_STORAGE_ROOT` (по умолчанию `./storage/media`,
в Docker Compose — volume `storage`). Проверки: `./mvnw verify`.
```

В `.env.example` добавить строку:

```bash
MEDIA_STORAGE_ROOT=/app/storage/media
```

В `docker-compose.yml` добавить в `environment` сервиса `app` (после `BOOTSTRAP_ADMIN_DISPLAY_NAME`):

```yaml
      MEDIA_STORAGE_ROOT: ${MEDIA_STORAGE_ROOT:-/app/storage/media}
```

- [ ] **Step 4: Полный verify и коммит**

```bash
./mvnw verify
git add docs/domain/adr/ADR-007-image-fingerprint-v1.md docs/domain/glossary.md \
  docs/domain/aggregates.md README.md .env.example docker-compose.yml
git commit -m "docs: ADR-007 отпечаток v1, глоссарий/агрегаты media, README, compose ENV хранилища"
```

Ожидание: BUILD SUCCESS (документация не влияет на тесты; проверка — что ничего не сломано).

---

### Task 8: Финальная проверка и merge

**Files:**
- Modify: ничего (только проверки и merge).

**Interfaces:**
- Consumes: всё итерации 2 (Tasks 1–7).
- Produces: ветка `feat/iteration-2-media` слита в `main` (локально, без push); `./mvnw verify` зелёный на `main`.

- [ ] **Step 1: Полный verify на ветке**

```bash
./mvnw verify
```

Ожидание: BUILD SUCCESS — все unit/application/контрактные/приёмочные IT зелёные, ArchUnit зелёный, JaCoCo LINE ≥ 70% (gate «All coverage checks have been met»).

- [ ] **Step 2: Отчёт о покрытии**

```bash
python3 -c "
import xml.etree.ElementTree as t
r = t.parse('target/site/jacoco-merged/jacoco.xml').getroot()
for c in r.findall('counter'):
    if c.get('type') == 'LINE':
        m, co = int(c.get('missed')), int(c.get('covered'))
        print(f'LINE: {co}/{m+co} = {100*co/(m+co):.1f}%')
"
```

Ожидание: LINE ≥ 70% (по итогам итерации 1 было 97.5%; новые классы media покрыты тестами всех уровней).

- [ ] **Step 3: Merge в main (локально, без push)**

```bash
git checkout main
git merge --no-ff feat/iteration-2-media -m "merge: итерация 2 — media"
./mvnw verify
git log --oneline -3
```

Ожидание: merge без конфликтов; verify на `main` BUILD SUCCESS; push НЕ выполняется (только по отдельной команде пользователя).

- [ ] **Step 4: Чекпоинт-отчёт**

Краткий отчёт пользователю: что готово (агрегат, отпечаток v1 + эталонные файлы, порты и адаптеры, REST /files, миграция, docs), результаты verify и покрытия, отклонения от плана (если были), что отложено (409 задействованности и видимость по растениям — итерация 3; media.api — итерация 3).
