package com.plantarena.media.adapter.out.image;

import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.ImageResolutionTooHighException;
import com.plantarena.media.domain.UnsupportedImageFormatException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Анализ фактического содержимого изображения (javax.imageio)")
class JavaxImageAnalyzerTest {

    private final JavaxImageAnalyzer analyzer = new JavaxImageAnalyzer();

    @Test
    void png_принимается_с_фактическими_размерами() {
        byte[] png = image("png", 3, 2);

        var analyzed = analyzer.analyze(png);

        assertThat(analyzed.format()).isEqualTo(ImageFormat.PNG);
        assertThat(analyzed.width()).isEqualTo(3);
        assertThat(analyzed.height()).isEqualTo(2);
        assertThat(analyzed.argb()).hasSize(6);
    }

    @Test
    void jpeg_принимается() {
        byte[] jpeg = image("jpg", 2, 2);

        var analyzed = analyzer.analyze(jpeg);

        assertThat(analyzed.format()).isEqualTo(ImageFormat.JPEG);
        assertThat(analyzed.width()).isEqualTo(2);
    }

    @Test
    void gif_отклоняется_несмотря_на_поддержку_декодирования() {
        byte[] gif = image("gif", 2, 2);

        assertThatThrownBy(() -> analyzer.analyze(gif))
            .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void произвольные_байты_отклоняются() {
        assertThatThrownBy(() -> analyzer.analyze("точно не изображение".getBytes()))
            .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void больше_20Mpx_отклоняется_по_заголовку() {
        byte[] huge = image("png", 5000, 4200); // 21M пикселей, но крошечный файл

        assertThatThrownBy(() -> analyzer.analyze(huge))
            .isInstanceOf(ImageResolutionTooHighException.class);
    }

    private byte[] image(String format, int width, int height) {
        try {
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = buffered.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, width, height);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(buffered, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
