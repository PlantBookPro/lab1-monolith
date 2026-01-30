package com.plantarena.media.application;

import com.plantarena.media.application.port.in.DeleteMediaUseCase;
import com.plantarena.media.application.port.in.DownloadMediaUseCase;
import com.plantarena.media.application.port.in.UploadMediaUseCase;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.FileStorage;
import com.plantarena.media.domain.ImageAnalyzer;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFingerprinter;
import com.plantarena.media.domain.MediaAsset;
import com.plantarena.shared.security.CurrentActor;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Use cases media (раздел 6). Транзакции по разделу 12: файл сохраняется
 * в хранилище ДО короткой транзакции регистрации метаданных (в адаптере);
 * сбой регистрации компенсируется удалением сиротского файла.
 */
@Service
public class MediaAssetService implements UploadMediaUseCase, DownloadMediaUseCase, DeleteMediaUseCase {

    static final long MAX_BYTES = 10 * 1024 * 1024; // 10 MiB (раздел 6)

    private final MediaAssetRepository repository;
    private final ImageAnalyzer imageAnalyzer;
    private final FileStorage fileStorage;
    private final MediaAccessPolicy accessPolicy;
    private final Clock clock;
    private final ImageFingerprinter fingerprinter = new ImageFingerprinter();

    public MediaAssetService(MediaAssetRepository repository, ImageAnalyzer imageAnalyzer,
                             FileStorage fileStorage, MediaAccessPolicy accessPolicy, Clock clock) {
        this.repository = repository;
        this.imageAnalyzer = imageAnalyzer;
        this.fileStorage = fileStorage;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    public MediaAssetResult upload(CurrentActor actor, byte[] content) {
        accessPolicy.requireUploader(actor);
        if (content.length > MAX_BYTES) {
            throw new FileTooLargeException("Файл превышает 10 MiB: " + content.length + " байт");
        }
        AnalyzedImage image = imageAnalyzer.analyze(content); // 415 / 413 (пиксели)
        ImageFingerprint fingerprint = fingerprinter.fingerprint(image);
        String rawSha256 = fingerprinter.rawSha256(content);
        String storageKey = fileStorage.save(content, image.format());
        try {
            MediaAsset asset = MediaAsset.uploaded(actor.userId(), storageKey, image.format(),
                content.length, image.width(), image.height(), rawSha256, fingerprint,
                clock.instant());
            repository.save(asset); // короткая транзакция (раздел 12)
            return MediaAssetResult.from(asset);
        } catch (RuntimeException e) {
            fileStorage.delete(storageKey); // компенсация сиротского файла
            throw e;
        }
    }

    @Override
    public DownloadedMedia download(CurrentActor actor, UUID assetId) {
        MediaAsset asset = find(assetId);
        accessPolicy.requireViewer(actor, asset.ownerId());
        return new DownloadedMedia(asset.id(), asset.format().mimeType(),
            fileStorage.read(asset.storageKey()));
    }

    @Override
    public void delete(CurrentActor actor, UUID assetId) {
        MediaAsset asset = find(assetId);
        accessPolicy.requireDeleter(actor, asset.ownerId());
        repository.delete(asset.id());           // короткая транзакция
        fileStorage.delete(asset.storageKey());  // метаданные уже удалены — «висячих» ссылок нет
    }

    private MediaAsset find(UUID assetId) {
        return repository.findById(assetId)
            .orElseThrow(() -> new MediaAssetNotFoundException("Файл не найден: " + assetId));
    }
}
