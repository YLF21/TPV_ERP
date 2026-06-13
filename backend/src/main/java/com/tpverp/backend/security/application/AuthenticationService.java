package com.tpverp.backend.security.application;

import com.tpverp.backend.security.domain.Sesion;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.UsuarioRepository;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TipoTerminal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class AuthenticationService {

	private final TerminalRepository terminalRepository;
	private final UsuarioRepository usuarioRepository;
	private final SesionRepository sesionRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public AuthenticationService(
			TerminalRepository terminalRepository,
			UsuarioRepository usuarioRepository,
			SesionRepository sesionRepository,
			PasswordEncoder passwordEncoder,
			Clock clock) {
		this.terminalRepository = terminalRepository;
		this.usuarioRepository = usuarioRepository;
		this.sesionRepository = sesionRepository;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Transactional
	public LoginResult login(UUID terminalId, String userName, String password) {
		return login(terminalId, null, userName, password);
	}

	@Transactional
	public LoginResult login(
			UUID terminalId,
			String terminalCredential,
			String userName,
			String password) {
		var terminal = terminalRepository.findById(terminalId)
				.filter(value -> value.isAprobada() && value.isActiva())
				.orElseThrow(AuthenticationFailedException::new);
		if (terminal.getTipo() != TipoTerminal.SERVIDOR
				&& !passwordEncoder.matches(
						terminalCredential == null ? "" : terminalCredential,
						terminal.getCredentialHash())) {
			throw new AuthenticationFailedException();
		}
		var normalizedName = userName == null ? "" : userName.trim().toUpperCase(Locale.ROOT);
		var user = usuarioRepository.findByTiendaIdAndNombre(terminal.getTienda().getId(), normalizedName)
				.filter(value -> value.isActivo())
				.orElseThrow(AuthenticationFailedException::new);
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new AuthenticationFailedException();
		}
		var token = newToken();
		sesionRepository.save(new Sesion(user, terminal, hash(token), Instant.now(clock)));
		return new LoginResult(token, user.getNombre(), user.getRol().getNombre());
	}

	@Transactional
	public void logout(String accessToken) {
		sesionRepository.findByTokenHash(hash(accessToken))
				.filter(Sesion::isActiva)
				.ifPresent(session -> session.revocar(session.getUsuario(), "LOGOUT", Instant.now(clock)));
	}

	@Transactional
	public LoginResult renew(String accessToken) {
		var current = sesionRepository.findByTokenHash(hash(accessToken))
				.filter(Sesion::isActiva)
				.filter(session -> session.getUsuario().isActivo())
				.filter(session -> session.getTerminal() != null
						&& session.getTerminal().isActiva()
						&& session.getTerminal().isAprobada())
				.orElseThrow(AuthenticationFailedException::new);
		current.revocar(current.getUsuario(), "TOKEN_RENEWED", Instant.now(clock));
		var token = newToken();
		sesionRepository.save(new Sesion(
				current.getUsuario(),
				current.getTerminal(),
				hash(token),
				Instant.now(clock)));
		return new LoginResult(
				token,
				current.getUsuario().getNombre(),
				current.getUsuario().getRol().getNombre());
	}

	public String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("No se pudo procesar la sesión", exception);
		}
	}

	private String newToken() {
		var bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
