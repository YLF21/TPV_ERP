package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FiscalQrImageServiceTest {

    private final FiscalQrImageService service = new FiscalQrImageService();

    @Test
    void generatesReadablePngQrImage() throws Exception {
        var url =
                "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR"
                        + "?nif=B12345674&numserie=FV-001-26-000001"
                        + "&fecha=02-06-2026&importe=157.26";
        var png = service.png(url, 220);

        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.bytes()).isNotEmpty();
        var image = ImageIO.read(new ByteArrayInputStream(png.bytes()));
        assertThat(image.getWidth()).isEqualTo(220);
        assertThat(image.getHeight()).isEqualTo(220);
        assertThat(decode(png.bytes())).isEqualTo(url);
    }

    @Test
    void decodesNoVerifactuQrWithUtf8EncodedPayload() throws Exception {
        var url =
                "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQRNoVerifactu"
                        + "?nif=B12345674&numserie=R%2F%C3%91-0001"
                        + "&fecha=02-06-2026&importe=12.30";

        assertThat(decode(service.png(url, 220).bytes())).isEqualTo(url);
    }

    private String decode(byte[] bytes) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        var bitmap = new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap, Map.of(
                DecodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }
}
