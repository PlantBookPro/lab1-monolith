package com.plantarena.media.domain;

/**
 * Форматы первого этапа (раздел 6): JPEG и PNG. MIME и расширение — часть
 * единого языка media; маппинг MIME ↔ формат используется JPA-адаптером.
 */
public enum ImageFormat {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png");

    private final String mimeType;
    private final String extension;

    ImageFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static ImageFormat fromMimeType(String mimeType) {
        for (ImageFormat format : values()) {
            if (format.mimeType.equals(mimeType)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Неизвестный MIME-тип изображения: " + mimeType);
    }
}
