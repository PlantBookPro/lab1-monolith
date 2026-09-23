package com.plantarena.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot автоматически регистрирует бины типа Filter в servlet-контейнере.
 */
@Configuration
public class SharedWebConfig {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }
}
