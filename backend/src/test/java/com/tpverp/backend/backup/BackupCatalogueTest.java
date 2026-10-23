package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.application.*;
import com.tpverp.backend.installation.*;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.verifactu.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;

class BackupCatalogueTest {
    @TempDir Path root;
    private final BackupSettingsRepository settings = mock(BackupSettingsRepository.class);
    private final BackupExecutionRepository executions = mock(BackupExecutionRepository.class);
    private final InstallationRepository installations = mock(InstallationRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final Installation installation = new Installation("test", "public", Instant.parse("2026-10-01T00:00:00Z"));
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T13:00:00Z"), ZoneOffset.UTC);

    private BackupService service() {
        return service(clock);
    }

    private BackupService service(Clock effectiveClock) {
        when(installations.findAll()).thenReturn(List.of(installation));
        return new BackupService(settings, executions, installations, stores,
                mock(UserAccountRepository.class), mock(PasswordEncoder.class), mock(BackupKeyStore.class),
                mock(BackupFileCrypto.class), mock(BackupArchiveService.class), mock(PostgreSqlBackupCommands.class),
                mock(AuditService.class), mock(VerifactuConfigurationRepository.class), mock(FiscalEventService.class),
                effectiveClock, root, root.resolve("images"), root.resolve("templates"));
    }

    @Test void automaticScheduleUsesStoreMidnightAndHourEvenWhenServiceClockIsUtc() {
        var store = mock(com.tpverp.backend.organization.Store.class);
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(stores.findAll()).thenReturn(List.of(store));
        var config = new BackupSettings(installation, LocalTime.of(0, 15), 30, 72, Map.of("path", root.toString()));
        when(settings.findByInstalacionId(installation.getId())).thenReturn(Optional.of(config));
        var localMidnight = Instant.parse("2026-06-30T22:00:00Z");
        var effectiveClock = Clock.fixed(Instant.parse("2026-06-30T22:30:00Z"), ZoneOffset.UTC);
        var service = service(effectiveClock);
        assertThat(service.isDue()).isTrue();
        verify(executions).existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
                config.getId(), BackupResult.EXITO, localMidnight);
        when(executions.findByConfiguracionIdOrderBySequenceNumberDesc(config.getId(), PageRequest.of(0, 50)))
                .thenReturn(Page.empty(PageRequest.of(0, 50)));
        when(executions.existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
                config.getId(), BackupResult.EXITO, localMidnight)).thenReturn(true);
        assertThat(service.history(0, 50).nextScheduledAt()).isEqualTo(Instant.parse("2026-07-01T22:15:00Z"));
    }

    @Test void nonexistentDstHourUsesTheSameResolvedInstantForDueAndPreview() {
        var store = mock(com.tpverp.backend.organization.Store.class);
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(stores.findAll()).thenReturn(List.of(store));
        var config = new BackupSettings(installation, LocalTime.of(2, 30), 30, 72, Map.of("path", root.toString()));
        when(settings.findByInstalacionId(installation.getId())).thenReturn(Optional.of(config));
        when(executions.findByConfiguracionIdOrderBySequenceNumberDesc(config.getId(), PageRequest.of(0, 50)))
                .thenReturn(Page.empty(PageRequest.of(0, 50)));
        var before = service(Clock.fixed(Instant.parse("2026-03-29T01:00:00Z"), ZoneOffset.UTC));
        assertThat(before.isDue()).isFalse();
        assertThat(before.history(0, 50).nextScheduledAt()).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
        var due = service(Clock.fixed(Instant.parse("2026-03-29T01:30:00Z"), ZoneOffset.UTC));
        assertThat(due.isDue()).isTrue();
    }

    @Test void initialConfigurationCanBeShownWithoutProvisioningSecrets() {
        var service = service();
        var configuration = service.configuration();
        assertThat(configuration.configured()).isFalse();
        assertThat(configuration.active()).isFalse();
        assertThat(configuration.dailyRetention()).isEqualTo(30);
        assertThat(configuration.monthlyRetention()).isEqualTo(72);
        assertThat(service.history(0, 50).items()).isEmpty();
        verifyNoInteractions(executions);
    }

    @Test void historyIsPagedAndWarnsOnlyWhenAutomaticBackupsAreEnabled() {
        var service = service();
        var config = new BackupSettings(installation, LocalTime.NOON, 30, 72, Map.of("path", root.toString()));
        when(settings.findByInstalacionId(installation.getId())).thenReturn(Optional.of(config));
        when(executions.findByConfiguracionIdOrderBySequenceNumberDesc(config.getId(), PageRequest.of(2, 50)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 100));
        var page = service.history(2, 50);
        assertThat(page.total()).isEqualTo(100);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.overdue()).isTrue();
        assertThat(page.nextScheduledAt()).isAfter(clock.instant());
        verify(executions, never()).findByConfiguracionIdOrderByIniciadaEnDesc(any());
    }

    @Test void encryptedSizeAndMonthlyAvailabilitySurviveDailyRetention() throws Exception {
        var config = new BackupSettings(installation, LocalTime.NOON, 30, 72, Map.of("path", root.toString()));
        Path monthly = Files.write(root.resolve("000123.tpvb"), new byte[375]);
        var execution = new BackupExecution(config, clock.instant());
        execution.completar(BackupResult.EXITO, clock.instant(), Map.of("path", root.resolve("gone.tpvb").toString(),
                "monthlyPath", monthly.toString(), "sizeBytes", 375, "plaintextBytes", 100), null);
        var item = BackupService.ExecutionItem.from(execution);
        assertThat(item.available()).isTrue();
        assertThat(item.sizeBytes()).isEqualTo(375);
        Files.delete(monthly);
        item = BackupService.ExecutionItem.from(execution);
        assertThat(item.available()).isFalse();
        assertThat(item.sizeBytes()).isEqualTo(375);
    }

    @Test void detailCannotExposeAnotherConfiguration() {
        var service = service();
        var config = new BackupSettings(installation, LocalTime.NOON, 30, 72, Map.of());
        var other = new BackupSettings(installation, LocalTime.NOON, 30, 72, Map.of());
        var execution = new BackupExecution(other, clock.instant());
        when(settings.findByInstalacionId(installation.getId())).thenReturn(Optional.of(config));
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        assertThatThrownBy(() -> service.detail(execution.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void arbitraryRestoreIsDisabledEvenForDirectRuntimeCalls() {
        assertThatThrownBy(() -> service().restore(root, root, "a long recovery key"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("traslado controlado");
        assertThat(Arrays.stream(BackupController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value())))
                .doesNotContain("/restore");
    }

    @Test void numericOrLegacyCollisionNeverOverwritesOrDeletesAnExistingFile() throws Exception {
        var service = service();
        var config = new BackupSettings(installation, LocalTime.NOON, 30, 72, Map.of("path", root.toString()));
        when(settings.findByInstalacionId(installation.getId())).thenReturn(Optional.of(config));
        when(executions.save(any(BackupExecution.class))).thenAnswer(call -> call.getArgument(0));
        var prepared = service.prepare("MANUAL");
        org.springframework.test.util.ReflectionTestUtils.setField(prepared.execution(), "sequenceNumber", 123L);
        Path daily = Files.createDirectories(root.resolve("daily"));
        Path existing = Files.writeString(daily.resolve("000123.tpvb"), "original encrypted backup");
        assertThat(service.executePrepared(prepared).result()).isEqualTo(BackupResult.FALLO);
        assertThat(Files.readString(existing)).isEqualTo("original encrypted backup");
    }
}
