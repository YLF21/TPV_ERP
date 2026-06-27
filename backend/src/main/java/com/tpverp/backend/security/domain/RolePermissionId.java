package com.tpverp.backend.security.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class RolePermissionId implements Serializable {

	private UUID rol;
	private UUID permiso;

	protected RolePermissionId() {
	}

	public RolePermissionId(UUID rol, UUID permiso) {
		this.rol = rol;
		this.permiso = permiso;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof RolePermissionId that)) {
			return false;
		}
		return Objects.equals(rol, that.rol) && Objects.equals(permiso, that.permiso);
	}

	@Override
	public int hashCode() {
		return Objects.hash(rol, permiso);
	}
}
