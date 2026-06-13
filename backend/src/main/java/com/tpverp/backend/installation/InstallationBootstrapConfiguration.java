package com.tpverp.backend.installation;

import com.tpverp.backend.organization.EmpresaRepository;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.security.domain.RolRepository;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.UsuarioRepository;
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
			InstalacionRepository instalacionRepository,
			EmpresaRepository empresaRepository,
			TiendaRepository tiendaRepository,
			RolRepository rolRepository,
			UsuarioRepository usuarioRepository,
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
			UsuarioRepository usuarioRepository,
			SesionRepository sesionRepository,
			PasswordEncoder passwordEncoder,
			Clock clock) {
		return new AuthenticationService(
				terminalRepository, usuarioRepository, sesionRepository, passwordEncoder, clock);
	}

	@Bean
	InstallationStatusService installationStatusService(
			InstalacionRepository instalacionRepository,
			LicenciaRepository licenciaRepository,
			Clock clock) {
		return new InstallationStatusService(instalacionRepository, licenciaRepository, clock);
	}
}
