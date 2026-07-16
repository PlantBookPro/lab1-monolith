package com.plantarena.shared.web;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.NotIdentifiedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    @Test
    void некорректный_тип_параметра_запроса_даёт_400_invalid_parameter() {
        MethodArgumentTypeMismatchException mismatch =
            new MethodArgumentTypeMismatchException("abc", Integer.class, "page", null,
                new NumberFormatException("abc"));

        ResponseEntity<ApiError> response = handler.invalidParameter(mismatch, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_PARAMETER");
        assertThat(response.getBody().detail()).contains("page");
    }

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
            new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_JSON, List.of());

        ResponseEntity<ApiError> response = handler.unsupportedMediaType(unsupported, request);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }
}
