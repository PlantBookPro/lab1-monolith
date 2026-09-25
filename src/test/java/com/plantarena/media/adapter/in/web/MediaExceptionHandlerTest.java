package com.plantarena.media.adapter.in.web;

import com.plantarena.media.application.FileTooLargeException;
import com.plantarena.media.application.MediaAssetNotFoundException;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import com.plantarena.shared.web.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ошибки media: 415 формат, 413 размер, 404 не найден")
class MediaExceptionHandlerTest {

    private final MediaExceptionHandler handler = new MediaExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/files");

    @Test
    void неподдерживаемый_формат_даёт_415_unsupported_image_format() {
        ResponseEntity<ApiError> response = handler.unsupportedFormat(
            new UnsupportedImageFormatException("Разрешены JPEG и PNG"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_IMAGE_FORMAT");
    }

    @Test
    void превышение_байт_даёт_413_file_too_large() {
        ResponseEntity<ApiError> response = handler.tooLarge(
            new FileTooLargeException("Файл превышает 10 MiB"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void превышение_пикселей_даёт_413_image_too_large() {
        ResponseEntity<ApiError> response = handler.tooManyPixels(
            new ImageResolutionTooHighException("Больше 20 миллионов пикселей"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IMAGE_TOO_LARGE");
    }

    @Test
    void отсутствующий_файл_даёт_404_media_asset_not_found() {
        ResponseEntity<ApiError> response = handler.notFound(
            new MediaAssetNotFoundException("Файл не найден"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MEDIA_ASSET_NOT_FOUND");
    }
}
