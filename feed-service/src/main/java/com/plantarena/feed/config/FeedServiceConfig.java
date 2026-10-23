package com.plantarena.feed.config;

import com.plantarena.feed.application.FeedCursorCodec;
import com.plantarena.feed.application.FeedSettings;
import com.plantarena.feed.domain.FeedOrdering;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeedSettings.class)
public class FeedServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public FeedCursorCodec feedCursorCodec(FeedSettings settings) {
        return new FeedCursorCodec(settings.cursorSecret(), settings.cursorTtl());
    }

    @Bean
    public FeedOrdering feedOrdering() {
        return new FeedOrdering();
    }

    @Bean
    public OpenAPI feedOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Feed Service API").version("1")
            .description("Лента карточек для голосования: проекция окон tournaments, keyset-пагинация, курсор"));
    }
}
