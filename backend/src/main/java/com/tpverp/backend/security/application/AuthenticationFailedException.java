package com.tpverp.backend.security.application;

public class AuthenticationFailedException extends RuntimeException {

	public AuthenticationFailedException() {
		super("message.security.invalid_credentials");
	}
}
