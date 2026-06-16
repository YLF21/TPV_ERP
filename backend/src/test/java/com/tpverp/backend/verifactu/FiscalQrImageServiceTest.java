package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FiscalQrImageServiceTest {

    private final FiscalQrImageService service = new FiscalQrImageService();

    @Test
    void generatesReadablePngQrImage() throws Exception {
        var png = service.png(
                "https://www2.agenciatributaria.gob.es/wlpl/TIKE-CONT/ValidarQR"
                        + "?nif=B12345674&numserie=FV-001-26-000001"
                        + "&fecha=02-06-2026&importe=157.26",
                220);

        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.bytes()).isNotEmpty();
        var image = ImageIO.read(new ByteArrayInputStream(png.bytes()));
        assertThat(image.getWidth()).isEqualTo(220);
        assertThat(image.getHeight()).isEqualTo(220);
    }
}
