package com.plantarena.media.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


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
            digest.update((byte) (pixel >> 16)); 
            digest.update((byte) (pixel >> 8));  
            digest.update((byte) pixel);         
        }
        return new ImageFingerprint(HexFormat.of().formatHex(digest.digest()), ALGORITHM_VERSION);
    }

    
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
