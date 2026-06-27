package com.tpverp.backend.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.RoleRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentity;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class InstallationBootstrapServiceTest {

	@Mock
	private InstallationRepository instalacionRepository;
	@Mock
	private CompanyRepository empresaRepository;
	@Mock
	private StoreRepository tiendaRepository;
	@Mock
	private RoleRepository rolRepository;
	@Mock
	private UserAccountRepository usuarioRepository;
	@Mock
	private TerminalRepository terminalRepository;
	@Mock
	private InstallationIdentityStore identityStore;
	@Mock
	private PasswordEncoder passwordEncoder;

	private InstallationBootstrapService service;

	@BeforeEach
	void setUp() {
		service = new InstallationBootstrapService(
				instalacionRepository,
				empresaRepository,
				tiendaRepository,
				rolRepository,
				usuarioRepository,
				terminalRepository,
				identityStore,
				passwordEncoder,
				Clock.fixed(Instant.parse("2026-06-08T10:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void createsDemoStoreServerAndProtectedAdminOnFirstStart() throws Exception {
		when(instalacionRepository.count()).thenReturn(0L);
		when(identityStore.loadOrCreate()).thenReturn(identity());
		when(passwordEncoder.encode(any())).thenAnswer(invocation ->
				"0000".equals(invocation.getArgument(0)) ? "admin-hash" : "credential-hash");
		when(empresaRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(tiendaRepository.save(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(rolRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.initialize();

		var installation = ArgumentCaptor.forClass(Installation.class);
		var store = ArgumentCaptor.forClass(Store.class);
		var role = ArgumentCaptor.forClass(Role.class);
		var user = ArgumentCaptor.forClass(UserAccount.class);
		var terminal = ArgumentCaptor.forClass(Terminal.class);
		verify(instalacionRepository).save(installation.capture());
		verify(tiendaRepository).save(store.capture());
		verify(rolRepository).save(role.capture());
		verify(usuarioRepository).save(user.capture());
		verify(terminalRepository).save(terminal.capture());

		assertThat(installation.getValue().getDemoHasta())
				.isEqualTo(Instant.parse("2026-07-08T10:00:00Z"));
		assertThat(store.getValue().getCodigoTienda()).isEqualTo("001");
		assertThat(store.getValue().getTimezone()).isEqualTo("Atlantic/Canary");
		assertThat(role.getValue().getNombre()).isEqualTo("ADMIN");
		assertThat(role.getValue().isProtegido()).isTrue();
		assertThat(user.getValue().getNombre()).isEqualTo("ADMIN");
		assertThat(user.getValue().isProtegido()).isTrue();
		assertThat(terminal.getValue().getNombre()).isEqualTo("SERVIDOR");
	}

	@Test
	void doesNothingWhenInstallationAlreadyExists() {
		when(instalacionRepository.count()).thenReturn(1L);

		service.initialize();

		verify(identityStore, never()).loadOrCreate();
		verify(empresaRepository, never()).save(any());
		verify(usuarioRepository, never()).save(any());
	}

	private InstallationIdentity identity() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		var keyPair = generator.generateKeyPair();
		return new InstallationIdentity("key-id", keyPair.getPublic(), keyPair.getPrivate());
	}
}
