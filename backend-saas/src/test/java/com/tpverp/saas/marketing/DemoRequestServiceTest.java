package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DemoRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:15:30Z");

    @Test
    void normalizesAndPersistsARealRequest() {
        DemoRequestRepository repository = mock(DemoRequestRepository.class);
        DemoRequestService service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        DemoRequestReceipt receipt = service.submit(new CreateDemoRequest(
                DemoProduct.APP_SAAS,
                null,
                "  Laura Garcia  ",
                "  Mercado Centro  ",
                "  LAURA@EXAMPLE.COM  ",
                "  600 000 000  ",
                "  Tres tiendas y un almacen  ",
                "es",
                true,
                "",
                "  /producto/contacto?sector=retail  ",
                "  https://search.example/results  ",
                "  newsletter  ",
                "  email  ",
                "  lanzamiento-octubre  "));

        ArgumentCaptor<DemoRequestView> saved = ArgumentCaptor.forClass(DemoRequestView.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).satisfies(request -> {
            assertThat(request.id()).isEqualTo(receipt.id());
            assertThat(request.product()).isEqualTo(DemoProduct.APP_SAAS);
            assertThat(request.products()).containsExactly(DemoProduct.APP_SAAS);
            assertThat(request.name()).isEqualTo("Laura Garcia");
            assertThat(request.company()).isEqualTo("Mercado Centro");
            assertThat(request.email()).isEqualTo("laura@example.com");
            assertThat(request.phone()).isEqualTo("600 000 000");
            assertThat(request.message()).isEqualTo("Tres tiendas y un almacen");
            assertThat(request.landingPath()).isEqualTo("/producto/contacto?sector=retail");
            assertThat(request.referrer()).isEqualTo("https://search.example/results");
            assertThat(request.utmSource()).isEqualTo("newsletter");
            assertThat(request.utmMedium()).isEqualTo("email");
            assertThat(request.utmCampaign()).isEqualTo("lanzamiento-octubre");
            assertThat(request.status()).isEqualTo("NEW");
            assertThat(request.privacyAcceptedAt()).isEqualTo(NOW);
            assertThat(request.createdAt()).isEqualTo(NOW);
        });
        assertThat(receipt.receivedAt()).isEqualTo(NOW);
    }

    @Test
    void silentlyDiscardsARequestThatFillsTheHoneypot() {
        DemoRequestRepository repository = mock(DemoRequestRepository.class);
        DemoRequestService service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        DemoRequestReceipt receipt = service.submit(new CreateDemoRequest(
                DemoProduct.APP_VENTA, null, "Bot", "Bot Company", "bot@example.com",
                null, null, "en", true, "https://spam.example",
                null, null, null, null, null));

        assertThat(receipt.receivedAt()).isEqualTo(NOW);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistsTheEntireSelectionInCanonicalOrderWithoutLosingAnyProduct() {
        DemoRequestRepository repository = mock(DemoRequestRepository.class);
        DemoRequestService service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        service.submit(new CreateDemoRequest(
                DemoProduct.APP_VENTA,
                List.of(DemoProduct.APP_SAAS, DemoProduct.APP_PDA, DemoProduct.APP_VENTA, DemoProduct.APP_GESTION),
                "Laura", "Mercado Centro", "laura@example.com", null, null, "es", true, "",
                null, null, null, null, null));

        ArgumentCaptor<DemoRequestView> saved = ArgumentCaptor.forClass(DemoRequestView.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().product()).isEqualTo(DemoProduct.APP_VENTA);
        assertThat(saved.getValue().products()).containsExactly(
                DemoProduct.APP_VENTA, DemoProduct.APP_GESTION, DemoProduct.APP_PDA, DemoProduct.APP_SAAS);
    }

    @Test
    void derivesTheCompatibleSummaryWhenOnlyProductsAreProvided() {
        DemoRequestRepository repository = mock(DemoRequestRepository.class);
        DemoRequestService service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        service.submit(new CreateDemoRequest(null, List.of(DemoProduct.APP_SAAS, DemoProduct.APP_GESTION),
                "Laura", "Mercado Centro", "laura@example.com", null, null, "es", true, "",
                null, null, null, null, null));

        ArgumentCaptor<DemoRequestView> saved = ArgumentCaptor.forClass(DemoRequestView.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().product()).isEqualTo(DemoProduct.APP_GESTION);
        assertThat(saved.getValue().products()).containsExactly(DemoProduct.APP_GESTION, DemoProduct.APP_SAAS);
    }

    @Test
    void readsOnlyTheBoundedAdministrativeInbox() {
        DemoRequestRepository repository = mock(DemoRequestRepository.class);
        DemoRequestService service = new DemoRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findLatest(DemoRequestService.ADMIN_INBOX_LIMIT)).thenReturn(List.of());

        assertThat(service.latest()).isEmpty();
        verify(repository).findLatest(100);
    }
}
