package com.tpverp.backend.shared.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

public final class InstallationIdentityStore {

	private static final String PUBLIC_KEY_FILE = "installation-public.der";
	private static final String PRIVATE_KEY_FILE = "installation-private.dpapi";

	private final Path directory;
	private final SecretProtector protector;

	public InstallationIdentityStore(Path directory, SecretProtector protector) {
		this.directory = directory;
		this.protector = protector;
	}

	public synchronized InstallationIdentity loadOrCreate() {
		try {
			Files.createDirectories(directory);
			var publicPath = directory.resolve(PUBLIC_KEY_FILE);
			var privatePath = directory.resolve(PRIVATE_KEY_FILE);
			if (Files.exists(publicPath) && Files.exists(privatePath)) {
				return read(publicPath, privatePath);
			}
			if (Files.exists(publicPath) || Files.exists(privatePath)) {
				throw new IllegalStateException("La identidad de instalación está incompleta");
			}
			return create(publicPath, privatePath);
		} catch (IOException | GeneralSecurityException exception) {
			throw new IllegalStateException("No se pudo cargar la identidad de instalación", exception);
		}
	}

	private InstallationIdentity create(Path publicPath, Path privatePath)
			throws GeneralSecurityException, IOException {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(3072);
		var keyPair = generator.generateKeyPair();
		writeAtomically(publicPath, keyPair.getPublic().getEncoded());
		writeAtomically(privatePath, protector.protect(keyPair.getPrivate().getEncoded()));
		return identity(keyPair.getPublic().getEncoded(), keyPair.getPrivate().getEncoded());
	}

	private InstallationIdentity read(Path publicPath, Path privatePath)
			throws IOException, GeneralSecurityException {
		return identity(
				Files.readAllBytes(publicPath),
				protector.unprotect(Files.readAllBytes(privatePath)));
	}

	private InstallationIdentity identity(byte[] publicBytes, byte[] privateBytes)
			throws GeneralSecurityException {
		var factory = KeyFactory.getInstance("RSA");
		var publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
		var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
		var digest = MessageDigest.getInstance("SHA-256").digest(publicBytes);
		return new InstallationIdentity(HexFormat.of().formatHex(digest), publicKey, privateKey);
	}

	private void writeAtomically(Path target, byte[] contents) throws IOException {
		var temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
		try {
			Files.write(temporary, contents);
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
