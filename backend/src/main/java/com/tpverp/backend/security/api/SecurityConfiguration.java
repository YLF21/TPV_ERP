package com.tpverp.backend.security.api;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.catalog.ProductEditAuthorizationService;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.WebTerminalPolicy;
import com.tpverp.backend.shared.access.OperationalAccessFilter;
import com.tpverp.backend.shared.access.OperationalAccessPolicy;
import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
class SecurityConfiguration {

	@Bean
	WebTerminalPolicy webTerminalPolicy(Environment environment) {
		return new WebTerminalPolicy(environment.getProperty("tpv.web-terminal.enabled", Boolean.class, false),
				environment.getProperty("tpv.web-terminal.allowed-origin", ""),
				environment.getProperty("tpv.web-terminal.allow-local-http", Boolean.class, false));
	}

	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			UserSessionRepository sesionRepository,
			TerminalRepository terminalRepository,
			AuthenticationService authenticationService,
			ProductEditAuthorizationService productEditAuthorizations,
			InstallationStatusService installationStatusService,
			Clock clock,
			WebTerminalPolicy webTerminalPolicy,
			Environment environment) throws Exception {

		var bearerFilter = new BearerSessionFilter(sesionRepository, authenticationService, clock,
				Duration.ofHours(12), terminalRepository);
		var publicPdaRateLimitFilter = new PublicPdaRateLimitFilter(clock);
		var temporaryPasswordFilter = new TemporaryPasswordFilter();
		var productEditAuthorizationFilter = new ProductEditAuthorizationFilter(productEditAuthorizations);
		var operationalFilter = new OperationalAccessFilter(
				installationStatusService, new OperationalAccessPolicy());
		var publicPaths = publicPaths(environment);

		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/fiscal/export-jobs/download").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/licenses/link-saas/bootstrap-initial").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/mail-oauth/callback",
								"/api/public/communications/unsubscribe").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/public/communications/unsubscribe").permitAll()
						.requestMatchers(
								"/actuator/metrics",
								"/actuator/metrics/**",
								"/actuator/prometheus").hasRole("ADMIN")
						.requestMatchers(publicPaths.toArray(String[]::new)).permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(publicPdaRateLimitFilter, BearerSessionFilter.class)
				.addFilterBefore(new WebTerminalRequestFilter(webTerminalPolicy), PublicPdaRateLimitFilter.class)
				.addFilterAfter(productEditAuthorizationFilter, BearerSessionFilter.class)
				.addFilterAfter(temporaryPasswordFilter, ProductEditAuthorizationFilter.class)
				.addFilterAfter(operationalFilter, TemporaryPasswordFilter.class)
				.build();
	}

	private List<String> publicPaths(Environment environment) {
		var paths = new ArrayList<>(List.of(
				"/api/v1/connectivity",
				"/api/v1/auth/login",
				"/api/v1/auth/installation-login",
				"/api/v1/installation/status",
				"/api/v1/installation/license-request",
				"/api/v1/terminal-linking/bootstrap",
				"/api/v1/terminal-linking/requests",
				"/api/v1/terminal-linking/requests/status",
				"/api/v1/terminal-linking/requests/cancel",
				"/api/v1/terminal-linking/server/adopt",
				"/api/v1/terminal-linking/legacy/adopt",
				"/api/v1/web-terminal/status",
				"/api/v1/web-terminal/request",
				"/api/v1/web-terminal/cancel",
				"/api/v1/web-terminal/login",
				"/api/v1/license/validate",
				"/api/v1/terminals/request",
				"/api/v1/terminals/pda/request",
				"/api/v1/terminals/pda/link",
				"/",
				"/actuator/health",
				"/actuator/health/**"));

		if (environment.acceptsProfiles(Profiles.of("dev"))) {
			paths.add("/swagger-ui.html");
			paths.add("/swagger-ui/**");
			paths.add("/v3/api-docs/**");
		}

		return paths;
	}
}
