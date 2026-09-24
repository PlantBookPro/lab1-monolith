package com.plantarena.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единая OpenAPI-спецификация монолита (раздел 13 требований).
 * Демо-идентификация документируется в Swagger (ADR-005).
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

    /**
     * Добавляет описание демо-заголовка ко всем операциям: отсутствие заголовка
     * означает гостя (ADR-005). Это механизм демонстрации, не аутентификация.
     */
    @Bean
    public OperationCustomizer demoUserIdHeaderCustomizer() {
        return (Operation operation, org.springframework.web.method.HandlerMethod handlerMethod) -> {
            operation.addParametersItem(new Parameter()
                .in("header")
                .name(DemoHeaderCurrentActorProvider.DEMO_USER_ID_HEADER)
                .description("Демо-идентификация (только профили dev/test, ADR-005): UUID "
                    + "существующего активного пользователя. Отсутствие заголовка = гость. "
                    + "Роли всегда берутся из БД. Не аутентификация.")
                .required(false)
                .schema(new StringSchema()));
            return operation;
        };
    }
}
