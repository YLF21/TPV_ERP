package com.tpverp.backend.terminal;

import com.tpverp.backend.document.template.OperationalReceiptJasperRenderer;
import com.tpverp.backend.document.template.RenderedDocumentView.RenderedArtifact;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.Base64;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hardware/printer-test")
public class PrinterTestController {

    private final CurrentOrganization organization;
    private final OperationalReceiptJasperRenderer renderer;

    public PrinterTestController(
            CurrentOrganization organization, OperationalReceiptJasperRenderer renderer) {
        this.organization = organization;
        this.renderer = renderer;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CONFIGURACION_TERMINAL')")
    @Transactional(readOnly = true)
    public PrinterTestView render() {
        var receipt = renderer.renderPrinterTest(organization.currentStore().getId());
        return new PrinterTestView(
                new RenderedArtifact("application/pdf",
                        Base64.getEncoder().encodeToString(receipt.pdf())),
                new RenderedArtifact("image/png",
                        Base64.getEncoder().encodeToString(receipt.png())));
    }

    public record PrinterTestView(
            RenderedArtifact renderedPdf, RenderedArtifact ticketRenderedImage) {
    }
}
