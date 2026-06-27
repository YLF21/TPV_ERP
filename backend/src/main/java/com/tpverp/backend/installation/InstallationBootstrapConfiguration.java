package com.tpverp.backend.installation;

import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.security.domain.RoleRepository;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.shared.crypto.WindowsDpapiSecretProtector;
import com.tpverp.backend.terminal.TerminalRepository;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class InstallationBootstrapConfiguration {

	@Bean
	Clock systemClock() {
		return Clock.systemUTC();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	InstallationIdentityStore installationIdentityStore(
			@Value("${tpv.installation.key-directory}") Path keyDirectory) {
		return new InstallationIdentityStore(keyDirectory, new WindowsDpapiSecretProtector());
	}

	@Bean
	InstallationBootstrapService installationBootstrapService(
			InstallationRepository instalacionRepository,
			CompanyRepository empresaRepository,
			StoreRepository tiendaRepository,
			RoleRepository rolRepository,
			UserAccountRepository usuarioRepository,
			TerminalRepository terminalRepository,
			InstallationIdentityStore identityStore,
			PasswordEncoder passwordEncoder,
			Clock clock) {
		return new InstallationBootstrapService(
				instalacionRepository,
				empresaRepository,
				tiendaRepository,
				rolRepository,
				usuarioRepository,
				terminalRepository,
				identityStore,
				passwordEncoder,
				clock);
	}

	@Bean
	@Order(0)
	ApplicationRunner installationBootstrapRunner(InstallationBootstrapService service) {
		return arguments -> service.initialize();
	}

	@Bean
	CommercialBootstrapService commercialBootstrapService(JdbcTemplate jdbcTemplate) {
		return new CommercialBootstrapService(jdbcTemplate);
	}

	@Bean
	@Order(5)
	ApplicationRunner commercialBootstrapRunner(CommercialBootstrapService service) {
		return arguments -> service.initialize();
	}

	@Bean
	AuthenticationService authenticationService(
			TerminalRepository terminalRepository,
			UserAccountRepository usuarioRepository,
			UserSessionRepository sesionRepository,
			PasswordEncoder passwordEncoder,
			Clock clock) {
		return new AuthenticationService(
				terminalRepository, usuarioRepository, sesionRepository, passwordEncoder, clock);
	}

	@Bean
	InstallationStatusService installationStatusService(
			InstallationRepository instalacionRepository,
			LicenseRepository licenciaRepository,
			Clock clock) {
		return new InstallationStatusService(instalacionRepository, licenciaRepository, clock);
	}
}
