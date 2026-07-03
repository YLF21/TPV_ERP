package com.tpverp.backend.installation;

import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.RoleRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class InstallationBootstrapService {

	private final InstallationRepository instalacionRepository;
	private final CompanyRepository empresaRepository;
	private final StoreRepository tiendaRepository;
	private final RoleRepository rolRepository;
	private final UserAccountRepository usuarioRepository;
	private final TerminalRepository terminalRepository;
	private final InstallationIdentityStore identityStore;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public InstallationBootstrapService(
			InstallationRepository instalacionRepository,
			CompanyRepository empresaRepository,
			StoreRepository tiendaRepository,
			RoleRepository rolRepository,
			UserAccountRepository usuarioRepository,
			TerminalRepository terminalRepository,
			InstallationIdentityStore identityStore,
			PasswordEncoder passwordEncoder,
			Clock clock) {
		this.instalacionRepository = instalacionRepository;
		this.empresaRepository = empresaRepository;
		this.tiendaRepository = tiendaRepository;
		this.rolRepository = rolRepository;
		this.usuarioRepository = usuarioRepository;
		this.terminalRepository = terminalRepository;
		this.identityStore = identityStore;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	@Transactional
	public void initialize() {
		if (instalacionRepository.count() > 0) {
			return;
		}

		var identity = identityStore.loadOrCreate();
		var now = Instant.now(clock);
		var installation = new Installation(
				reference(identity.keyId()),
				Base64.getEncoder().encodeToString(identity.publicKey().getEncoded()),
				now);
		instalacionRepository.save(installation);

		var adminRole = rolRepository.save(new Role(null, "ADMIN"));
		usuarioRepository.save(new UserAccount(null, "ADMIN", passwordEncoder.encode("0000"), adminRole));
	}

	private String reference(String keyId) {
		return "INST-" + keyId.substring(0, Math.min(12, keyId.length())).toUpperCase(Locale.ROOT);
	}

}
