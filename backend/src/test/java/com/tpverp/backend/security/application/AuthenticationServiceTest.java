package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserSession;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

	@Mock TerminalRepository terminalRepository;
	@Mock UserAccountRepository usuarioRepository;
	@Mock UserSessionRepository sesionRepository;
	@Mock PasswordEncoder passwordEncoder;

	@Test
	void createsOpaqueSessionForValidCredentials() {
		var store = store();
		var terminal = new Terminal(store, "SERVIDOR", TerminalType.SERVIDOR, "credential");
		var role = new Role(store, "ADMIN");
		var user = new UserAccount(store, "ADMIN", "password-hash", role);
		when(terminalRepository.findById(terminal.getId())).thenReturn(Optional.of(terminal));
		when(usuarioRepository.findByEmpresaIdAndNombre(store.getEmpresa().getId(), "ADMIN"))
				.thenReturn(Optional.of(user));
		when(passwordEncoder.matches("0000", "password-hash")).thenReturn(true);

		var service = service();
		var result = service.login(terminal.getId(), "admin", "0000");

		assertThat(result.accessToken()).isNotBlank();
		assertThat(result.userName()).isEqualTo("ADMIN");
		var session = ArgumentCaptor.forClass(UserSession.class);
		verify(sesionRepository).save(session.capture());
		assertThat(session.getValue().getTokenHash()).doesNotContain(result.accessToken());
	}

	@Test
	void rejectsInvalidPassword() {
		var store = store();
		var terminal = new Terminal(store, "SERVIDOR", TerminalType.SERVIDOR, "credential");
		var role = new Role(store, "ADMIN");
		var user = new UserAccount(store, "ADMIN", "password-hash", role);
		when(terminalRepository.findById(terminal.getId())).thenReturn(Optional.of(terminal));
		when(usuarioRepository.findByEmpresaIdAndNombre(store.getEmpresa().getId(), "ADMIN"))
				.thenReturn(Optional.of(user));
		when(passwordEncoder.matches("bad", "password-hash")).thenReturn(false);

		assertThatThrownBy(() -> service().login(terminal.getId(), "ADMIN", "bad"))
				.isInstanceOf(AuthenticationFailedException.class);
	}

	private AuthenticationService service() {
		return new AuthenticationService(
				terminalRepository,
				usuarioRepository,
				sesionRepository,
				passwordEncoder,
				Clock.fixed(Instant.parse("2026-06-08T10:00:00Z"), ZoneOffset.UTC));
	}

	private Store store() {
		var address = Map.of(
				"linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
				"provincia", "Las Palmas", "pais", "ES");
		var company = new Company("DEMO", "DEMO", address);
		return new Store(company, "DEMO", address, UUID.randomUUID().toString(),
				"Atlantic/Canary", "EUR", "es-ES");
	}
}
