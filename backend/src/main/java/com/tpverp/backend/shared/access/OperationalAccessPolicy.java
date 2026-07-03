package com.tpverp.backend.shared.access;

import java.util.EnumSet;

public final class OperationalAccessPolicy {

	private static final EnumSet<OperationCategory> RESTRICTED_OPERATIONS = EnumSet.of(
			OperationCategory.READ,
			OperationCategory.EXPORT_OR_PRINT,
			OperationCategory.LICENSE_MANAGEMENT,
			OperationCategory.BACKUP_OR_RESTORE);

	public boolean isAllowed(OperationalMode mode, OperationCategory category) {
		return switch (mode) {
			case LICENSED, OFFLINE -> true;
			case UNLINKED, RESTRICTED -> RESTRICTED_OPERATIONS.contains(category);
		};
	}
}
