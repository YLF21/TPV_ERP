package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.shared.access.OperationalAccessFilter;
import com.tpverp.backend.shared.access.OperationalAccessPolicy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			UserSessionRepository sesionRepository,
			AuthenticationService authenticationService,
			InstallationStatusService installationStatusService,
			Environment environment) throws Exception {
		var bearerFilter = new BearerSessionFilter(sesionRepository, authenticationService);
		var temporaryPasswordFilter = new TemporaryPasswordFilter();
		var operationalFilter = new OperationalAccessFilter(
				installationStatusService, new OperationalAccessPolicy());
		var publicPaths = publicPaths(environment);
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(publicPaths.toArray(String[]::new)).permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(temporaryPasswordFilter, BearerSessionFilter.class)
				.addFilterAfter(operationalFilter, TemporaryPasswordFilter.class)
				.build();
	}

	private List<String> publicPaths(Environment environment) {
		var paths = new ArrayList<>(List.of(
				"/api/v1/auth/login",
				"/api/v1/auth/installation-login",
				"/api/v1/installation/status",
				"/api/v1/installation/license-request",
				"/api/v1/license/validate",
				"/api/v1/terminals/request",
				"/actuator/health"));
		if (environment.acceptsProfiles(Profiles.of("dev"))) {
			paths.add("/swagger-ui.html");
			paths.add("/swagger-ui/**");
			paths.add("/v3/api-docs/**");
		}
		return paths;
	}
}
