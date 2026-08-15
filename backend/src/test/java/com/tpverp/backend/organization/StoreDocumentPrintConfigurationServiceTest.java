package com.tpverp.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateResolver;
import com.tpverp.backend.document.template.DocumentTemplateType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class StoreDocumentPrintConfigurationServiceTest {

    @Test
    void storesIndependentObservationsAndVersionedLogoForCurrentStore() throws Exception {
        var organization = mock(CurrentOrganization.class);
        var settings = mock(StoreDocumentPrintSettingsRepository.class);
        var logos = mock(StoreDocumentLogoRepository.class);
        var templates = mock(DocumentTemplateResolver.class);
        var audit = mock(AuditService.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(settings.findById(storeId)).thenReturn(Optional.empty());
        when(logos.findByStoreIdAndSha256(any(), any())).thenReturn(Optional.empty());
        var savedLogo = new java.util.concurrent.atomic.AtomicReference<StoreDocumentLogo>();
        when(logos.save(any())).thenAnswer(invocation -> {
            StoreDocumentLogo value = invocation.getArgument(0);
            savedLogo.set(value);
            return value;
        });
        when(logos.findByIdAndStoreId(any(), any())).thenAnswer(invocation ->
                Optional.ofNullable(savedLogo.get()));
        when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new StoreDocumentPrintConfigurationService(
                organization, settings, logos, templates, audit,
                Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC));

        var observations = service.updateObservations(
                "Gracias", "Factura legal", "Mercancia entregada", "Vale al portador");
        var style = service.updateTicketStyle(TicketPrintStyle.MINIMALISTA);
        var origin = service.updateTicketPresentation(
                TicketTemplateOrigin.INTEGRATED, TicketPrintStyle.COMPACTA);
        var logo = service.uploadLogo(png());

        assertThat(observations.ticketObservations()).isEqualTo("Gracias");
        assertThat(observations.invoiceObservations()).isEqualTo("Factura legal");
        assertThat(observations.deliveryNoteObservations()).isEqualTo("Mercancia entregada");
        assertThat(observations.voucherObservations()).isEqualTo("Vale al portador");
        assertThat(style.ticketStyle()).isEqualTo(TicketPrintStyle.MINIMALISTA);
        assertThat(origin.ticketTemplateOrigin()).isEqualTo(TicketTemplateOrigin.INTEGRATED);
        assertThat(origin.ticketStyle()).isEqualTo(TicketPrintStyle.COMPACTA);
        assertThat(logo.storeId()).isEqualTo(storeId);
        verify(audit, org.mockito.Mockito.times(4)).record(any(), any(), any());
    }

    @Test
    void selectsObservationByDocumentType() {
        var organization = mock(CurrentOrganization.class);
        var settings = mock(StoreDocumentPrintSettingsRepository.class);
        var logos = mock(StoreDocumentLogoRepository.class);
        var templates = mock(DocumentTemplateResolver.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        var value = new StoreDocumentPrintSettings(storeId);
        value.updateObservations("Ticket", "Factura", "Albaran", "Vale");
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(settings.findById(storeId)).thenReturn(Optional.of(value));
        var service = new StoreDocumentPrintConfigurationService(
                organization, settings, logos, templates,
                mock(AuditService.class), Clock.systemUTC());

        assertThat(service.presentation(DocumentTemplateType.TICKET).observations())
                .isEqualTo("Ticket");
        assertThat(service.presentation(DocumentTemplateType.FACTURA_VENTA).observations())
                .isEqualTo("Factura");
        assertThat(service.presentation(DocumentTemplateType.ALBARAN_VENTA).observations())
                .isEqualTo("Albaran");
        assertThat(service.presentation(DocumentTemplateType.VALE).observations())
                .isEqualTo("Vale");
    }

    @Test
    void requiresAnEffectiveTemplateBeforeSelectingImportedTickets() {
        var organization = mock(CurrentOrganization.class);
        var settings = mock(StoreDocumentPrintSettingsRepository.class);
        var templates = mock(DocumentTemplateResolver.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(templates.findEffective(
                store, DocumentTemplateType.TICKET,
                DocumentTemplateFormat.TICKET_80)).thenReturn(Optional.empty());
        var service = new StoreDocumentPrintConfigurationService(
                organization, settings, mock(StoreDocumentLogoRepository.class),
                templates, mock(AuditService.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.updateTicketPresentation(
                TicketTemplateOrigin.IMPORTED, TicketPrintStyle.PRINCIPAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ticket_imported_template_required");
    }

    @Test
    void rejectsNonImageAndOversizedContent() {
        assertThatThrownBy(() -> StoreDocumentPrintConfigurationService.validate(
                "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreDocumentPrintConfigurationService.validate(
                new byte[StoreDocumentPrintConfigurationService.MAX_LOGO_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_print_logo_size_invalid");
    }

    private static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }
}
