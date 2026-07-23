package com.tpverp.backend.shared.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OperationalAccessPolicyTest {

	private final OperationalAccessPolicy policy = new OperationalAccessPolicy();

	@ParameterizedTest
	@EnumSource(OperationCategory.class)
	void offlineModeAllowsEveryOperation(OperationCategory category) {
		assertThat(policy.isAllowed(OperationalMode.OFFLINE, category)).isTrue();
	}

	@ParameterizedTest
	@EnumSource(OperationCategory.class)
	void licensedModeAllowsEveryOperation(OperationCategory category) {
		assertThat(policy.isAllowed(OperationalMode.LICENSED, category)).isTrue();
	}

	@ParameterizedTest
	@EnumSource(OperationCategory.class)
	void developmentModeAllowsEveryOperation(OperationCategory category) {
		assertThat(policy.isAllowed(OperationalMode.DEVELOPMENT, category)).isTrue();
	}

	@ParameterizedTest
	@EnumSource(
			value = OperationCategory.class,
			names = {"READ", "EXPORT_OR_PRINT", "LICENSE_MANAGEMENT", "BACKUP_OR_RESTORE"})
	void restrictedModeAllowsRecoveryAndReadOperations(OperationCategory category) {
		assertThat(policy.isAllowed(OperationalMode.RESTRICTED, category)).isTrue();
	}

	@ParameterizedTest
	@EnumSource(
			value = OperationCategory.class,
			names = {"BUSINESS_WRITE", "SECURITY_WRITE", "TERMINAL_WRITE"})
	void restrictedModeBlocksNormalWrites(OperationCategory category) {
		assertThat(policy.isAllowed(OperationalMode.RESTRICTED, category)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(
			value = OperationCategory.class,
			names = {"BUSINESS_WRITE", "SECURITY_WRITE", "TERMINAL_WRITE"})
	void unlinkedModeBlocksNormalWrites(OperationCategory category) {
		assertThat(policy.isAllowed(OperationalMode.UNLINKED, category)).isFalse();
	}
}
