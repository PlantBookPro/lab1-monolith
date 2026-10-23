package com.plantarena.media.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Отпечаток изображения: алгоритм v1 (ADR-007)")
class ImageFingerprintTest {

    private final ImageFingerprinter fingerprinter = new ImageFingerprinter();

    @Test
    void алгоритм_v1_зафиксирован_независимым_вычислением() throws Exception {
        // 1x1, пиксель RGB (0x12, 0x34, 0x56), альфа 0xFF — игнорируется
        AnalyzedImage image = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF123456});

        String fingerprint = fingerprinter.fingerprint(image).value();

        // Ожидание вычислено независимо от реализации: SHA-256 по документированной
        // раскладке ADR-007 — префикс версии, ширина/высота int64 big-endian, байты R,G,B.
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update("plantarena-image-fingerprint-v1".getBytes(StandardCharsets.US_ASCII));
        expected.update(ByteBuffer.allocate(16).putLong(1L).putLong(1L).array());
        expected.update(new byte[] {0x12, 0x34, 0x56});
        assertThat(fingerprint).isEqualTo(HexFormat.of().formatHex(expected.digest()));
    }

    @Test
    void альфа_канал_игнорируется() {
        AnalyzedImage opaque = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF102030});
        AnalyzedImage transparent = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0x00102030});

        assertThat(fingerprinter.fingerprint(opaque)).isEqualTo(fingerprinter.fingerprint(transparent));
    }

    @Test
    void порядок_каналов_зафиксирован_rgb() {
        AnalyzedImage image = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF123456});
        AnalyzedImage swapped = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF654321});

        assertThat(fingerprinter.fingerprint(image)).isNotEqualTo(fingerprinter.fingerprint(swapped));
    }

    @Test
    void размеры_входят_в_отпечаток() {
        // те же пиксели в другой геометрии (2x1 вместо 1x2) — другой отпечаток
        AnalyzedImage wide = new AnalyzedImage(ImageFormat.PNG, 2, 1,
            new int[] {0xFF000000, 0xFF112233});
        AnalyzedImage tall = new AnalyzedImage(ImageFormat.PNG, 1, 2,
            new int[] {0xFF000000, 0xFF112233});

        assertThat(fingerprinter.fingerprint(wide)).isNotEqualTo(fingerprinter.fingerprint(tall));
    }

    @Test
    void версия_алгоритма_равна_1() {
        AnalyzedImage image = new AnalyzedImage(ImageFormat.PNG, 1, 1, new int[] {0xFF123456});

        assertThat(fingerprinter.fingerprint(image).version()).isEqualTo(1);
    }

    @Test
    void rawSha256_вычисляется_по_исходным_байтам() {
        byte[] content = "plantarena".getBytes(StandardCharsets.UTF_8);

        assertThat(fingerprinter.rawSha256(content)).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void некорректный_отпечаток_невозможно_создать() {
        assertThatThrownBy(() -> new ImageFingerprint("not-hex", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("a".repeat(64), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
