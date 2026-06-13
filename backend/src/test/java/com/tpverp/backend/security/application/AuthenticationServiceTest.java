package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Sesion;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TipoTerminal;
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
	@Mock UsuarioRepository usuarioRepository;
	@Mock SesionRepository sesionRepository;
	@Mock PasswordEncoder passwordEncoder;

	@Test
	void createsOpaqueSessionForValidCredentials() {
		var store = store();
		var terminal = new Terminal(store, "SERVIDOR", TipoTerminal.SERVIDOR, "credential");
		var role = new Rol(store, "ADMIN");
		var user = new Usuario(store, "ADMIN", "password-hash", role);
		when(terminalRepository.findById(terminal.getId())).thenReturn(Optional.of(terminal));
		when(usuarioRepository.findByTiendaIdAndNombre(store.getId(), "ADMIN")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("0000", "password-hash")).thenReturn(true);

		var service = service();
		var result = service.login(terminal.getId(), "admin", "0000");

		assertThat(result.accessToken()).isNotBlank();
		assertThat(result.userName()).isEqualTo("ADMIN");
		var session = ArgumentCaptor.forClass(Sesion.class);
		verify(sesionRepository).save(session.capture());
		assertThat(session.getValue().getTokenHash()).doesNotContain(result.accessToken());
	}

	@Test
	void rejectsInvalidPassword() {
		var store = store();
		var terminal = new Terminal(store, "SERVIDOR", TipoTerminal.SERVIDOR, "credential");
		var role = new Rol(store, "ADMIN");
		var user = new Usuario(store, "ADMIN", "password-hash", role);
		when(terminalRepository.findById(terminal.getId())).thenReturn(Optional.of(terminal));
		when(usuarioRepository.findByTiendaIdAndNombre(store.getId(), "ADMIN")).thenReturn(Optional.of(user));
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

	private Tienda store() {
		var address = Map.of(
				"linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
				"provincia", "Las Palmas", "pais", "ES");
		var company = new Empresa("DEMO", "DEMO", address);
		return new Tienda(company, "DEMO", address, UUID.randomUUID().toString(),
				"Atlantic/Canary", "EUR", "es-ES");
	}
}
