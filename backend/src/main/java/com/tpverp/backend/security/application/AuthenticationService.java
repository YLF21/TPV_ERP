package com.tpverp.backend.security.application;

import com.tpverp.backend.security.domain.UserSession;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.TerminalRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class AuthenticationService {

	private static final String NUMERIC_PASSWORD_PATTERN = "\\d{4,12}";

	private final TerminalRepository terminalRepository;
	private final UserAccountRepository usuarioRepository;
	private final UserSessionRepository sesionRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public AuthenticationService(
			TerminalRepository terminalRepository,
			UserAccountRepository usuarioRepository,
			UserSessionRepository sesionRepository,
			PasswordEncoder passwordEncoder,
			Clock clock) {
		this.terminalRepository = terminalRepository;
		this.usuarioRepository = usuarioRepository;
		this.sesionRepository = sesionRepository;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Transactional
	// Permite al ADMIN global entrar antes de que existan tienda y terminal.
	public LoginResult installationLogin(String userName, String password) {
		var user = usuarioRepository.findByNombreAndTiendaIsNull(normalize(userName))
				.filter(value -> value.isProtegido() && value.isActivo())
				.orElseThrow(AuthenticationFailedException::new);
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new AuthenticationFailedException();
		}
		return createSession(user, null);
	}

	@Transactional
	// Sustituye la contrasena temporal inicial y emite una sesion ya desbloqueada.
	public LoginResult changeInstallationPassword(String accessToken, String currentPassword, String newPassword) {
		var session = sesionRepository.findByTokenHashAndRevocadaEnIsNull(hash(accessToken))
				.filter(value -> value.getTerminal() == null)
				.orElseThrow(AuthenticationFailedException::new);
		var user = session.getUsuario();
		if (!user.isProtegido() || user.getTienda() != null || !user.mustChangePassword()) {
			throw new IllegalStateException("message.security.password_change_not_required");
		}
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new AuthenticationFailedException();
		}
		requireNumericPassword(newPassword);
		user.cambiarPassword(passwordEncoder.encode(newPassword));
		session.revocar(user, "INITIAL_PASSWORD_CHANGED", Instant.now(clock));
		return createSession(user, null);
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
		if (!passwordEncoder.matches(
						terminalCredential == null ? "" : terminalCredential,
						terminal.getCredentialHash())) {
			throw new AuthenticationFailedException();
		}
		var normalizedName = userName == null ? "" : userName.trim().toUpperCase(Locale.ROOT);
		var user = usuarioRepository.findByEmpresaIdAndNombre(
						terminal.getTienda().getEmpresa().getId(), normalizedName)
				.or(() -> usuarioRepository.findByNombreAndTiendaIsNull(normalizedName)
						.filter(value -> value.isProtegido()))
				.filter(value -> value.isActivo())
				.filter(value -> value.isProtegido()
						|| usuarioRepository.hasStoreAccess(value.getId(), terminal.getTienda().getId()))
				.orElseThrow(AuthenticationFailedException::new);
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new AuthenticationFailedException();
		}
		return createSession(user, terminal);
	}

	@Transactional
	public void logout(String accessToken) {
		sesionRepository.findByTokenHash(hash(accessToken))
				.filter(UserSession::isActiva)
				.ifPresent(session -> session.revocar(session.getUsuario(), "LOGOUT", Instant.now(clock)));
	}

	@Transactional
	public LoginResult renew(String accessToken) {
		var current = sesionRepository.findByTokenHash(hash(accessToken))
				.filter(UserSession::isActiva)
				.filter(session -> session.getUsuario().isActivo())
				.filter(this::validSessionContext)
				.orElseThrow(AuthenticationFailedException::new);
		current.revocar(current.getUsuario(), "TOKEN_RENEWED", Instant.now(clock));
		var token = newToken();
		sesionRepository.save(new UserSession(
				current.getUsuario(),
				current.getTerminal(),
				hash(token),
				Instant.now(clock)));
		return new LoginResult(
				token,
				current.getUsuario().getId(),
				current.getUsuario().getNombre(),
				current.getUsuario().getRol().getNombre(),
				current.getUsuario().mustChangePassword(),
				permissions(current.getUsuario()));
	}

	public String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("message.security.session_processing_failed", exception);
		}
	}

	private String newToken() {
		var bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private boolean validSessionContext(UserSession session) {
		if (session.getTerminal() == null) {
			return session.getUsuario().isProtegido() && session.getUsuario().getTienda() == null;
		}
		return session.getTerminal().isActiva() && session.getTerminal().isAprobada();
	}

	private LoginResult createSession(
			com.tpverp.backend.security.domain.UserAccount user,
			com.tpverp.backend.terminal.Terminal terminal) {
		var token = newToken();
		sesionRepository.save(new UserSession(user, terminal, hash(token), Instant.now(clock)));
		return new LoginResult(
				token,
				user.getId(),
				user.getNombre(),
				user.getRol().getNombre(),
				user.mustChangePassword(),
				permissions(user));
	}

	private static Set<String> permissions(com.tpverp.backend.security.domain.UserAccount user) {
		var codes = new LinkedHashSet<String>();
		user.getRol().getPermisos().stream()
				.map(value -> value.getPermiso().getCodigo())
				.forEach(codes::add);
		if (user.getRol().isProtegido()) {
			codes.add("ADMIN");
		}
		return Set.copyOf(codes);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}

	private void requireNumericPassword(String value) {
		if (value == null || !value.matches(NUMERIC_PASSWORD_PATTERN)) {
			throw new IllegalArgumentException("La contrasena debe tener entre 4 y 12 cifras numericas");
		}
	}
}
