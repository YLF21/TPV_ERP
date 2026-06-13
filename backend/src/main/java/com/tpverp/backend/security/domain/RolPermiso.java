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
@IdClass(RolPermisoId.class)
public class RolPermiso {

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "rol_id", nullable = false)
	private Rol rol;

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "permiso_id", nullable = false)
	private Permiso permiso;

	protected RolPermiso() {
	}

	public RolPermiso(Rol rol, Permiso permiso) {
		this.rol = Objects.requireNonNull(rol, "rol");
		this.permiso = Objects.requireNonNull(permiso, "permiso");
	}

	public Permiso getPermiso() {
		return permiso;
	}
}
