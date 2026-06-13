package com.tpverp.backend.shared.crypto;

import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

public final class CryptoParameters {

	private CryptoParameters() {
	}

	public static PSSParameterSpec pssSha256() {
		return new PSSParameterSpec(
				"SHA-256",
				"MGF1",
				MGF1ParameterSpec.SHA256,
				32,
				PSSParameterSpec.TRAILER_FIELD_BC);
	}
}
