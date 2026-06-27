package com.tpverp.backend.catalog.image;

import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPWriteParam;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

@Component
public class ProductImageProcessor {

    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_DIMENSION = 1600;
    private static final int THUMBNAIL_SIZE = 300;
    private static final float WEBP_QUALITY = 0.85f;

    public ProcessedImage process(byte[] content) {
        validateSize(content);
        BufferedImage source = decode(content);
        BufferedImage image = scaleToFit(source, MAX_DIMENSION, MAX_DIMENSION, false);
        BufferedImage thumbnail = createThumbnail(source);
        return new ProcessedImage(
                encodeWebp(image),
                encodeWebp(thumbnail),
                image.getWidth(),
                image.getHeight(),
                sha256(content));
    }
    // Valida, normaliza y genera las dos variantes WebP de una imagen de producto.

    public byte[] export(byte[] storedWebp, ExportFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("El formato de exportacion es obligatorio");
        }
        if (format == ExportFormat.WEBP) {
            return storedWebp.clone();
        }
        BufferedImage image = decode(storedWebp);
        if (format == ExportFormat.JPG) {
            image = paintOnWhite(image);
        }
        return encode(image, format == ExportFormat.JPG ? "jpg" : "png");
    }
    // Exporta el WebP interno al formato solicitado sin exponer archivos locales.

    private void validateSize(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("La imagen debe ocupar entre 1 byte y 5 MB");
        }
    }

    private BufferedImage decode(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                throw new IllegalArgumentException("El archivo no contiene una imagen compatible");
            }
            return image;
        } catch (Exception exception) {
            throw new IllegalArgumentException("No se pudo leer la imagen", exception);
        }
    }

    private BufferedImage createThumbnail(BufferedImage source) {
        BufferedImage thumbnail = new BufferedImage(
                THUMBNAIL_SIZE, THUMBNAIL_SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage scaled = scaleToFit(source, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true);
        Graphics2D graphics = thumbnail.createGraphics();
        applyQuality(graphics);
        graphics.drawImage(
                scaled,
                (THUMBNAIL_SIZE - scaled.getWidth()) / 2,
                (THUMBNAIL_SIZE - scaled.getHeight()) / 2,
                null);
        graphics.dispose();
        return thumbnail;
    }

    private BufferedImage scaleToFit(BufferedImage source, int maxWidth, int maxHeight, boolean allowEnlarge) {
        double scale = Math.min((double) maxWidth / source.getWidth(), (double) maxHeight / source.getHeight());
        if (!allowEnlarge) {
            scale = Math.min(scale, 1);
        }
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        if (width == source.getWidth() && height == source.getHeight()) {
            return source;
        }
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D graphics = target.createGraphics();
        applyQuality(graphics);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private void applyQuality(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private byte[] encodeWebp(BufferedImage image) {
        image = normalizeForWebp(image);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
        try (var output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            WebPWriteParam parameters = (WebPWriteParam) writer.getDefaultWriteParam();
            boolean lossless = image.getColorModel().hasAlpha();
            parameters.setCompressionType(lossless ? CompressionType.Lossless : CompressionType.Lossy);
            parameters.setAlphaCompressionAlgorithm(1);
            parameters.setUseSharpYUV(true);
            if (!lossless) {
                parameters.setCompressionQuality(WEBP_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo convertir la imagen a WebP", exception);
        } finally {
            writer.dispose();
        }
    }

    private byte[] encode(BufferedImage image, String format) {
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("No existe codificador para " + format);
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo exportar la imagen", exception);
        }
    }

    private BufferedImage paintOnWhite(BufferedImage source) {
        BufferedImage target =
                new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return target;
    }

    private BufferedImage normalizeForWebp(BufferedImage source) {
        if (!source.getColorModel().hasAlpha() || source.getType() == BufferedImage.TYPE_4BYTE_ABGR) {
            return source;
        }
        BufferedImage normalized =
                new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D graphics = normalized.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return normalized;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("message.crypto.sha256_not_available", exception);
        }
    }

    public record ProcessedImage(byte[] image, byte[] thumbnail, int width, int height, String sha256) {}

    public enum ExportFormat {
        JPG,
        PNG,
        WEBP
    }
}
