package com.tpverp.backend.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationIdentityStoreTest {

	@TempDir
	Path directory;

	@Test
	void createsAndReloadsTheSameProtectedIdentity() throws Exception {
		var protector = new ReversibleTestProtector();
		var store = new InstallationIdentityStore(directory, protector);

		var created = store.loadOrCreate();
		var reloaded = store.loadOrCreate();

		assertThat(reloaded.publicKey().getEncoded()).isEqualTo(created.publicKey().getEncoded());
		assertThat(reloaded.privateKey().getEncoded()).isEqualTo(created.privateKey().getEncoded());
		assertThat(created.keyId()).hasSize(64);

		var signer = Signature.getInstance("RSASSA-PSS");
		signer.setParameter(CryptoParameters.pssSha256());
		signer.initSign(created.privateKey());
		signer.update("identity-proof".getBytes());
		var signature = signer.sign();

		var verifier = Signature.getInstance("RSASSA-PSS");
		verifier.setParameter(CryptoParameters.pssSha256());
		verifier.initVerify(reloaded.publicKey());
		verifier.update("identity-proof".getBytes());
		assertThat(verifier.verify(signature)).isTrue();
	}

	private static final class ReversibleTestProtector implements SecretProtector {

		@Override
		public byte[] protect(byte[] plaintext) {
			return xor(plaintext);
		}

		@Override
		public byte[] unprotect(byte[] protectedValue) {
			return xor(protectedValue);
		}

		private byte[] xor(byte[] value) {
			var result = value.clone();
			for (var index = 0; index < result.length; index++) {
				result[index] ^= 0x5a;
			}
			return result;
		}
	}
}
