package com.plantarena.media.adapter.out.image;

import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.ImageAnalyzer;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * Анализ фактического содержимого на javax.imageio (JDK): формат определяется
 * зарегистрированным reader'ом — не расширением и не MIME клиента (раздел 6).
 * Размеры читаются по заголовку ДО декодирования растра — большие буферы
 * под превышающие лимит изображения не выделяются.
 */
@Component
public class JavaxImageAnalyzer implements ImageAnalyzer {

    static final long MAX_PIXELS = 20_000_000; // 20 миллионов пикселей (раздел 6)

    static {
        ImageIO.setUseCache(false); // не писать временные файлы при декодировании
    }

    @Override
    public AnalyzedImage analyze(byte[] content) {
        try (ImageInputStream input =
                 ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new UnsupportedImageFormatException(
                    "Файл не является изображением: разрешены JPEG и PNG");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UnsupportedImageFormatException(
                    "Формат файла не поддерживается: разрешены JPEG и PNG");
            }
            ImageReader reader = readers.next();
            try {
                ImageFormat format = formatOf(reader);
                reader.setInput(input);
                int width = reader.getWidth(0);   // по заголовку, без декодирования
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new ImageResolutionTooHighException(
                        "Изображение превышает 20 миллионов пикселей: " + width + "x" + height);
                }
                int[] argb = reader.read(0).getRGB(0, 0, width, height, null, 0, width);
                return new AnalyzedImage(format, width, height, argb);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                "Файл не является изображением: разрешены JPEG и PNG");
        }
    }

    private ImageFormat formatOf(ImageReader reader) throws IOException {
        String formatName = reader.getFormatName().toUpperCase(Locale.ROOT);
        return switch (formatName) {
            case "JPEG", "JPG" -> ImageFormat.JPEG;
            case "PNG" -> ImageFormat.PNG;
            default -> throw new UnsupportedImageFormatException(
                "Формат файла не поддерживается (" + formatName + "): разрешены JPEG и PNG");
        };
    }
}
