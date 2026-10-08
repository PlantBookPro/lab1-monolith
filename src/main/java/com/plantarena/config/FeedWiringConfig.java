package com.plantarena.config;

import com.plantarena.feed.application.FeedCursorCodec;
import com.plantarena.feed.application.FeedSettings;
import com.plantarena.feed.domain.FeedOrdering;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableConfigurationProperties(FeedSettings.class)
public class FeedWiringConfig {

    @Bean
    public FeedCursorCodec feedCursorCodec(FeedSettings settings) {
        return new FeedCursorCodec(settings.cursorSecret(), settings.cursorTtl());
    }

    @Bean
    public FeedOrdering feedOrdering() {
        return new FeedOrdering();
    }
}
