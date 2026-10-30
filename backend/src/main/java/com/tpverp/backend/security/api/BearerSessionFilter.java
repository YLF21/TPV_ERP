package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.terminal.TerminalRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerSessionFilter extends OncePerRequestFilter {

	private final UserSessionRepository sesionRepository;
	private final AuthenticationService authenticationService;
	private final Clock clock;
	private final Duration maximumLifetime;
	private final TerminalRepository terminalRepository;

	public BearerSessionFilter(
			UserSessionRepository sesionRepository,
			AuthenticationService authenticationService) {
		this(sesionRepository, authenticationService, Clock.systemUTC(), Duration.ofHours(12), null);
	}

	public BearerSessionFilter(
			UserSessionRepository sesionRepository,
			AuthenticationService authenticationService,
			Clock clock,
			Duration maximumLifetime) {
		this(sesionRepository, authenticationService, clock, maximumLifetime, null);
	}

	public BearerSessionFilter(
			UserSessionRepository sesionRepository,
			AuthenticationService authenticationService,
			Clock clock,
			Duration maximumLifetime,
			TerminalRepository terminalRepository) {
		this.sesionRepository = sesionRepository;
		this.authenticationService = authenticationService;
		this.clock = clock;
		this.maximumLifetime = maximumLifetime;
		this.terminalRepository = terminalRepository;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		var header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith("Bearer ")) {
			var token = header.substring(7);
			var now = Instant.now(clock);
			sesionRepository.findByTokenHashAndRevocadaEnIsNull(authenticationService.hash(token))
					.filter(session -> !session.isExpired(now, maximumLifetime))
					.filter(session -> session.getTerminal() == null
							? session.getUsuario().isProtegido() && session.getUsuario().getTienda() == null
							: session.getTerminal() != null
							&& session.getTerminal().isActiva()
							&& session.getTerminal().isAprobada())
					.filter(session -> session.getUsuario().isActivo())
					.filter(com.tpverp.backend.security.domain.UserSession::hasCurrentTerminalBinding)
					.ifPresent(session -> {
						sesionRepository.touchActivity(session.getId(), now, now.minus(Duration.ofMinutes(5)));
						if (terminalRepository != null && session.getTerminal() != null)
							terminalRepository.touchLastSeen(session.getTerminal().getId(), now,
									now.minus(Duration.ofMinutes(5)));
						var user = session.getUsuario();
						var authorities = new ArrayList<SimpleGrantedAuthority>();
						authorities.add(new SimpleGrantedAuthority(user.getRol().authority()));
						user.getRol().getPermisos().stream()
								.map(value -> value.getPermiso().getCodigo())
								.map(SimpleGrantedAuthority::new)
								.forEach(authorities::add);
						var securityContext = SecurityContextHolder.createEmptyContext();
						var authentication = new UsernamePasswordAuthenticationToken(
								user, token, authorities);
						authentication.setDetails(new OperationalSessionContext(
								session.getId(),
								session.getTerminal() == null ? null : session.getTerminal().getId(),
								session.getTerminal() == null ? null : session.getTerminal().getTienda().getId()));
						securityContext.setAuthentication(authentication);
						SecurityContextHolder.setContext(securityContext);
					});
		}
		filterChain.doFilter(request, response);
	}
}
