package com.plantarena.shared.web;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.NotIdentifiedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Общие ошибки API: идентификация, доступ, пагинация")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");

    @Test
    void неидентифицированный_субъект_получает_401_not_identified() {
        ResponseEntity<ApiError> response =
            handler.notIdentified(new NotIdentifiedException("Требуется идентификация"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_IDENTIFIED");
        assertThat(response.getBody().detail()).isEqualTo("Требуется идентификация");
    }

    @Test
    void отказ_в_доступе_даёт_403_access_denied() {
        ResponseEntity<ApiError> response =
            handler.accessDenied(new AccessDeniedException("Только админ"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void некорректная_пагинация_даёт_400_invalid_pagination() {
        ResponseEntity<ApiError> response =
            handler.invalidPagination(new InvalidPaginationException("size 51 вне диапазона"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_PAGINATION");
    }
}
