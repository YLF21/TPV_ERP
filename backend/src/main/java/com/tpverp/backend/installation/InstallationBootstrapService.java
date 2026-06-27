package com.tpverp.backend.installation;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.RoleRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class InstallationBootstrapService {

	private static final Map<String, String> DEMO_ADDRESS = Map.of(
			"linea1", "Calle Demostracion 1",
			"ciudad", "Las Palmas de Gran Canaria",
			"codigoPostal", "35001",
			"provincia", "Las Palmas",
			"pais", "ES");

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

		var company = empresaRepository.save(
				new Company("DEMO-00000000", "EMPRESA DE DEMOSTRACION", DEMO_ADDRESS));
		var store = tiendaRepository.save(new Store(
				company,
				"TIENDA DEMO",
				DEMO_ADDRESS,
				addressHash(),
				"Atlantic/Canary",
				"EUR",
				"es-ES"));

		var adminRole = rolRepository.save(new Role(store, "ADMIN"));
		var server = new Terminal(
				store,
				"SERVIDOR",
				TerminalType.SERVIDOR,
				passwordEncoder.encode(UUID.randomUUID().toString()));
		terminalRepository.save(server);
		usuarioRepository.save(new UserAccount(store, "ADMIN", passwordEncoder.encode("0000"), adminRole));
	}

	private String reference(String keyId) {
		return "INST-" + keyId.substring(0, Math.min(12, keyId.length())).toUpperCase(Locale.ROOT);
	}

	private String addressHash() {
		try {
			var normalized = String.join("|",
					DEMO_ADDRESS.get("linea1"),
					DEMO_ADDRESS.get("ciudad"),
					DEMO_ADDRESS.get("codigoPostal"),
					DEMO_ADDRESS.get("provincia"),
					DEMO_ADDRESS.get("pais")).toUpperCase(Locale.ROOT);
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("No se pudo normalizar la dirección demo", exception);
		}
	}
}
