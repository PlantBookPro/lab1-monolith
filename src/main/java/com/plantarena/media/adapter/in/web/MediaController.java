package com.plantarena.media.adapter.in.web;

import com.plantarena.media.application.MediaAssetResult;
import com.plantarena.media.application.port.in.DeleteMediaUseCase;
import com.plantarena.media.application.port.in.DownloadMediaUseCase;
import com.plantarena.media.application.port.in.UploadMediaUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ручки файлов (раздел 13). Контроллер обращается только к входным портам
 * application (правило 10.2.7); формат и размеры проверяет use case по
 * фактическому содержимому, а не по имени/MIME части запроса.
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "media")
public class MediaController {

    private final UploadMediaUseCase uploadMedia;
    private final DownloadMediaUseCase downloadMedia;
    private final DeleteMediaUseCase deleteMedia;
    private final CurrentActorProvider currentActorProvider;

    public MediaController(UploadMediaUseCase uploadMedia, DownloadMediaUseCase downloadMedia,
                           DeleteMediaUseCase deleteMedia, CurrentActorProvider currentActorProvider) {
        this.uploadMedia = uploadMedia;
        this.downloadMedia = downloadMedia;
        this.deleteMedia = deleteMedia;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "media-upload-file",
        summary = "Загрузить изображение (JPEG/PNG, до 10 MiB и 20 млн пикселей; USER и выше)")
    public ResponseEntity<MediaAssetResponse> upload(@RequestParam("file") MultipartFile file)
        throws IOException {
        CurrentActor actor = currentActorProvider.currentActor();
        MediaAssetResult result = uploadMedia.upload(actor, file.getBytes());
        return ResponseEntity
            .created(URI.create("/api/v1/files/" + result.id()))
            .body(MediaAssetResponse.from(result));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "media-download-file",
        summary = "Получить изображение (владелец; байты с фактическим Content-Type)")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        DownloadMediaUseCase.DownloadedMedia media = downloadMedia.download(actor, id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(media.mimeType()))
            .contentLength(media.content().length)
            .body(media.content());
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "media-delete-file",
        summary = "Удалить незадействованный файл (владелец/админ)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        deleteMedia.delete(actor, id);
        return ResponseEntity.noContent().build();
    }
}
