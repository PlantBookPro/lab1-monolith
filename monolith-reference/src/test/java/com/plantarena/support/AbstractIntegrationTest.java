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
