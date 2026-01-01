package com.plantarena.shared.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;

/**
 * Назначает traceId каждому HTTP-запросу и возвращает его в заголовке X-Trace-Id.
 */
public final class TraceIdFilter implements Filter {

    public static final String TRACE_ID_ATTRIBUTE = "plantarena.traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString();
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpRequest.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        httpResponse.setHeader(TRACE_ID_HEADER, traceId);
        chain.doFilter(request, response);
    }
}
