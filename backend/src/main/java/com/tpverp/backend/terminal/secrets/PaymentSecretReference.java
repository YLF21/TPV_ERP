package com.tpverp.backend.terminal.secrets;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payment_terminal_secret_reference")
public class PaymentSecretReference {
    @Id private UUID id;
    @Column(name="company_id",nullable=false) private UUID companyId;
    @Column(name="store_id",nullable=false) private UUID storeId;
    @Column(name="terminal_id",nullable=false) private UUID terminalId;
    @Column(name="opaque_reference", nullable=false, length=96) private String opaqueReference;
    @Column(nullable=false, length=32) private String provider;
    @Column(nullable=false) private int version;
    @Column(name="protected_value", nullable=false) private byte[] protectedValue;
    @Column(nullable=false) private boolean active;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="retired_at") private Instant retiredAt;

    protected PaymentSecretReference() {}

    static PaymentSecretReference createVersion(UUID id, PaymentSecretOwnerScope owner, String reference, String provider, int version, byte[] value, Instant now) {
        var result = new PaymentSecretReference();
        result.companyId=owner.companyId();result.storeId=owner.storeId();result.terminalId=owner.terminalId();
        result.id=Objects.requireNonNull(id); result.opaqueReference=required(reference); result.provider=required(provider);
        if(version < 1) throw new IllegalArgumentException("version");
        result.version=version; result.protectedValue=required(value); result.active=true; result.createdAt=Objects.requireNonNull(now);
        return result;
    }
    public void retire(Instant now) { active=false; protectedValue=new byte[]{0}; retiredAt=Objects.requireNonNull(now); }
    public String getOpaqueReference(){return opaqueReference;} public String getProvider(){return provider;}
    public UUID getCompanyId(){return companyId;} public UUID getStoreId(){return storeId;} public UUID getTerminalId(){return terminalId;}
    public int getVersion(){return version;} public byte[] getProtectedValue(){return protectedValue.clone();}
    public boolean isActive(){return active;}
    private static String required(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("required");return value.trim();}
    private static byte[] required(byte[] value){if(value==null||value.length==0)throw new IllegalArgumentException("required");return value.clone();}
}
