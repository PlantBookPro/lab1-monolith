package com.plantarena.media.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Доменный сервис отпечатков (ADR-007, алгоритм v1): SHA-256 по нормализованным
 * пикселям — префикс версии, ширина/высота int64 big-endian, затем R,G,B каждого
 * пикселя построчно (row-major). Альфа-канал и метаданные игнорируются;
 * EXIF-ориентация не применяется (зафиксировано в ADR-007). Любое изменение
 * раскладки требует новой версии алгоритма.
 */
public final class ImageFingerprinter {

    public static final int ALGORITHM_VERSION = 1;
    private static final byte[] PREFIX =
        "plantarena-image-fingerprint-v1".getBytes(StandardCharsets.US_ASCII);

    public ImageFingerprint fingerprint(AnalyzedImage image) {
        MessageDigest digest = sha256();
        digest.update(PREFIX);
        digest.update(longBytes(image.width()));
        digest.update(longBytes(image.height()));
        for (int pixel : image.argb()) {
            digest.update((byte) (pixel >> 16)); // R
            digest.update((byte) (pixel >> 8));  // G
            digest.update((byte) pixel);         // B (альфа игнорируется)
        }
        return new ImageFingerprint(HexFormat.of().formatHex(digest.digest()), ALGORITHM_VERSION);
    }

    /** rawSha256 (раздел 6): хэш исходных байтов — выявляет одинаковые файлы. */
    public String rawSha256(byte[] content) {
        return HexFormat.of().formatHex(sha256().digest(content));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static byte[] longBytes(long value) {
        return new byte[] {
            (byte) (value >> 56), (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32),
            (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }
}
