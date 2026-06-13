package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.SesionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerSessionFilter extends OncePerRequestFilter {

	private final SesionRepository sesionRepository;
	private final AuthenticationService authenticationService;

	public BearerSessionFilter(
			SesionRepository sesionRepository,
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
					.map(session -> session.getUsuario())
					.filter(user -> user.isActivo())
					.ifPresent(user -> {
						var authorities = new ArrayList<SimpleGrantedAuthority>();
						authorities.add(new SimpleGrantedAuthority(user.getRol().authority()));
						user.getRol().getPermisos().stream()
								.map(value -> value.getPermiso().getCodigo())
								.map(SimpleGrantedAuthority::new)
								.forEach(authorities::add);
						SecurityContextHolder.getContext().setAuthentication(
								new UsernamePasswordAuthenticationToken(
										user.getNombre(), token, authorities));
					});
		}
		filterChain.doFilter(request, response);
	}
}
