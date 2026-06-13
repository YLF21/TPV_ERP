package com.tpverp.backend.installation;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.EmpresaRepository;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.RolRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TipoTerminal;
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

	private final InstalacionRepository instalacionRepository;
	private final EmpresaRepository empresaRepository;
	private final TiendaRepository tiendaRepository;
	private final RolRepository rolRepository;
	private final UsuarioRepository usuarioRepository;
	private final TerminalRepository terminalRepository;
	private final InstallationIdentityStore identityStore;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public InstallationBootstrapService(
			InstalacionRepository instalacionRepository,
			EmpresaRepository empresaRepository,
			TiendaRepository tiendaRepository,
			RolRepository rolRepository,
			UsuarioRepository usuarioRepository,
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
		var installation = new Instalacion(
				reference(identity.keyId()),
				Base64.getEncoder().encodeToString(identity.publicKey().getEncoded()),
				now);
		instalacionRepository.save(installation);

		var company = empresaRepository.save(
				new Empresa("DEMO-00000000", "EMPRESA DE DEMOSTRACION", DEMO_ADDRESS));
		var store = tiendaRepository.save(new Tienda(
				company,
				"TIENDA DEMO",
				DEMO_ADDRESS,
				addressHash(),
				"Atlantic/Canary",
				"EUR",
				"es-ES"));

		var adminRole = rolRepository.save(new Rol(store, "ADMIN"));
		var server = new Terminal(
				store,
				"SERVIDOR",
				TipoTerminal.SERVIDOR,
				passwordEncoder.encode(UUID.randomUUID().toString()));
		terminalRepository.save(server);
		usuarioRepository.save(new Usuario(store, "ADMIN", passwordEncoder.encode("0000"), adminRole));
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
