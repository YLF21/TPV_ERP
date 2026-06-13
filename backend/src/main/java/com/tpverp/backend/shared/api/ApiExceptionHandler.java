package com.tpverp.backend.shared.api;

import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(AuthenticationFailedException.class)
	ProblemDetail authenticationFailed(AuthenticationFailedException exception) {
		return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail validationFailed(MethodArgumentNotValidException exception) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "La solicitud contiene datos no válidos");
	}

	@ExceptionHandler(LicenseValidationException.class)
	ProblemDetail invalidLicense(LicenseValidationException exception) {
		return problem(
				HttpStatus.UNPROCESSABLE_CONTENT,
				"INVALID_LICENSE",
				exception.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalidArgument(IllegalArgumentException exception) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage());
	}

	@ExceptionHandler(IllegalStateException.class)
	ProblemDetail stateConflict(IllegalStateException exception) {
		return problem(HttpStatus.CONFLICT, "STATE_CONFLICT", exception.getMessage());
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail integrityConflict(DataIntegrityViolationException exception) {
		return problem(
				HttpStatus.CONFLICT,
				"DATA_INTEGRITY_CONFLICT",
				"La operación entra en conflicto con los datos existentes");
	}

	private ProblemDetail problem(HttpStatus status, String code, String detail) {
		var problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("urn:tpv-erp:error:" + code));
		problem.setProperty("code", code);
		return problem;
	}
}
