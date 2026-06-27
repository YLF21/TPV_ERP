package com.tpverp.backend.security.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "rol_permiso")
@IdClass(RolePermissionId.class)
public class RolePermission {

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "rol_id", nullable = false)
	private Role rol;

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "permiso_id", nullable = false)
	private Permission permiso;

	protected RolePermission() {
	}

	public RolePermission(Role rol, Permission permiso) {
		this.rol = Objects.requireNonNull(rol, "rol");
		this.permiso = Objects.requireNonNull(permiso, "permiso");
	}

	public Permission getPermiso() {
		return permiso;
	}
}
