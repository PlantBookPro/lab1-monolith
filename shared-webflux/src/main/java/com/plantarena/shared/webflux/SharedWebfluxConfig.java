package com.plantarena.shared.webflux;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SharedWebfluxConfig {

    @Bean
    public TraceIdWebFilter traceIdWebFilter() {
        return new TraceIdWebFilter();
    }

    @Bean
    public ReactiveApiExceptionHandler reactiveApiExceptionHandler() {
        return new ReactiveApiExceptionHandler();
    }
}
