package com.tpverp.backend.catalog.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ProductImageProcessorTest {

    private final ProductImageProcessor processor = new ProductImageProcessor();

    @Test
    void scalesImageAndCreatesTransparentThumbnail() throws Exception {
        var result = processor.process(image("png", 3200, 1200, true));

        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(600);
        assertThat(result.sha256()).hasSize(64);

        BufferedImage main = read(result.image());
        BufferedImage thumbnail = read(result.thumbnail());
        assertThat(main.getWidth()).isEqualTo(1600);
        assertThat(main.getHeight()).isEqualTo(600);
        assertThat(thumbnail.getWidth()).isEqualTo(300);
        assertThat(thumbnail.getHeight()).isEqualTo(300);
        assertThat(thumbnail.getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void acceptsJpegWithoutEnlargingSmallImages() throws Exception {
        var result = processor.process(image("jpg", 200, 100, false));

        assertThat(result.width()).isEqualTo(200);
        assertThat(result.height()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidOrOversizedContent() {
        assertThatThrownBy(() -> processor.process("not-an-image".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.process(new byte[5 * 1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exportsStoredWebpAsWebpPngOrJpeg() throws Exception {
        byte[] stored = processor.process(image("png", 20, 10, true)).image();

        assertThat(processor.export(stored, ProductImageProcessor.ExportFormat.WEBP)).isEqualTo(stored);
        assertThat(read(processor.export(stored, ProductImageProcessor.ExportFormat.PNG)).getColorModel().hasAlpha())
                .isTrue();
        BufferedImage jpeg = read(processor.export(stored, ProductImageProcessor.ExportFormat.JPG));
        assertThat(jpeg.getColorModel().hasAlpha()).isFalse();
        assertThat(new Color(jpeg.getRGB(19, 9))).isEqualTo(Color.WHITE);
    }

    private byte[] image(String format, int width, int height, boolean alpha) throws Exception {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        var image = new BufferedImage(width, height, type);
        var graphics = image.createGraphics();
        graphics.setColor(alpha ? new Color(20, 80, 160, 120) : Color.BLUE);
        graphics.fillRect(0, 0, alpha ? width / 2 : width, height);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private BufferedImage read(byte[] content) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(content));
    }
}
