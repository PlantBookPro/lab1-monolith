package com.plantarena.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единая OpenAPI-спецификация монолита (раздел 13 требований).
 * Теги по контекстам добавляются контроллерами итераций 1–9.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI plantArenaOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Plant Arena API")
                .version("1")
                .description("Турниры растений: пользователи, файлы и растения, модерация, "
                    + "закрытые и глобальный турниры, голосование, лента"));
    }
}
