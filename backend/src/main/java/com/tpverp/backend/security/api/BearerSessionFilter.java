package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerSessionFilter extends OncePerRequestFilter {

	private final UserSessionRepository sesionRepository;
	private final AuthenticationService authenticationService;

	public BearerSessionFilter(
			UserSessionRepository sesionRepository,
			AuthenticationService authenticationService) {
		this.sesionRepository = sesionRepository;
		this.authenticationService = authenticationService;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		var header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith("Bearer ")) {
			var token = header.substring(7);
			sesionRepository.findByTokenHashAndRevocadaEnIsNull(authenticationService.hash(token))
					.filter(session -> session.getTerminal() == null
							? session.getUsuario().isProtegido() && session.getUsuario().getTienda() == null
							: session.getTerminal() != null
							&& session.getTerminal().isActiva()
							&& session.getTerminal().isAprobada())
					.map(session -> session.getUsuario())
					.filter(user -> user.isActivo())
					.ifPresent(user -> {
						var authorities = new ArrayList<SimpleGrantedAuthority>();
						authorities.add(new SimpleGrantedAuthority(user.getRol().authority()));
						user.getRol().getPermisos().stream()
								.map(value -> value.getPermiso().getCodigo())
								.map(SimpleGrantedAuthority::new)
								.forEach(authorities::add);
						var securityContext = SecurityContextHolder.createEmptyContext();
						securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(
								user, token, authorities));
						SecurityContextHolder.setContext(securityContext);
					});
		}
		filterChain.doFilter(request, response);
	}
}
