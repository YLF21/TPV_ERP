package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.shared.access.OperationalAccessFilter;
import com.tpverp.backend.shared.access.OperationalAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
			InstallationStatusService installationStatusService) throws Exception {
		var bearerFilter = new BearerSessionFilter(sesionRepository, authenticationService);
		var operationalFilter = new OperationalAccessFilter(
				installationStatusService, new OperationalAccessPolicy());
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(
								"/api/v1/auth/login",
								"/api/v1/installation/status",
								"/api/v1/installation/license-request",
								"/api/v1/license/validate",
								"/api/v1/terminals/request",
								"/actuator/health")
						.permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(operationalFilter, BearerSessionFilter.class)
				.build();
	}
}
