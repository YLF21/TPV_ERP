package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "cliente")
public class Customer {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Company company;

    @Column(name = "client_id", nullable = false, length = 12, updatable = false)
    private String clientId;

    @Column(name = "client_code_store_id", nullable = false, updatable = false)
    private UUID clientCodeStoreId;

    @Column(name = "nombre_fiscal", nullable = false)
    private String fiscalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento", nullable = false, length = 16)
    private DocumentType documentType;

    @Column(name = "numero_documento", nullable = false, length = 64)
    private String documentNumber;

    @Embedded
    private FiscalAddress fiscalAddress;

    @Column(length = 64)
    private String telefono;

    @Column(length = 320)
    private String email;

    @Column(columnDefinition = "text")
    private String observaciones;

    private LocalDate birthday;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CustomerGender gender;

    @Column(name = "commercial_consent", nullable = false)
    private boolean commercialConsent;

    @Column(name = "preferred_commercial_channel_id")
    private UUID preferredCommercialChannelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CustomerRate tarifa;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal descuento;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "credit_enabled", nullable = false)
    private boolean creditEnabled = true;

    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "payment_term_days", nullable = false)
    private int paymentTermDays = 30;

    @Column(name = "credit_blocked", nullable = false)
    private boolean creditBlocked;

    @Column(name = "block_on_overdue", nullable = false)
    private boolean blockOnOverdue;

    @Version
    private long version;

    protected Customer() {
    }

    public Customer(
            Company company,
            String fiscalName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress fiscalAddress,
            String phone,
            String email,
            String notes,
            CustomerRate rate,
            BigDecimal discount) {
        this.id = UUID.randomUUID();
        this.company = Objects.requireNonNull(company, "empresa");
        update(fiscalName, documentType, documentNumber, fiscalAddress,
                phone, email, notes, rate, discount);
    }

    public void update(
            String fiscalName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress fiscalAddress,
            String phone,
            String email,
            String notes,
            CustomerRate rate,
            BigDecimal discount) {
        CustomerRate newRate = Objects.requireNonNull(rate, "tarifa");
        this.fiscalName = PartyValues.required(fiscalName, "nombreFiscal");
        this.documentType = Objects.requireNonNull(documentType, "tipoDocumento");
        this.documentNumber = PartyValues.document(documentNumber);
        this.fiscalAddress = fiscalAddress;
        this.telefono = PartyValues.optional(phone);
        this.email = PartyValues.optional(email);
        this.observaciones = PartyValues.optional(notes);
        this.tarifa = newRate;
        this.descuento = PartyValues.discount(discount);
    }

    public void updateProfile(
            LocalDate birthday,
            CustomerGender gender,
            boolean commercialConsent,
            UUID preferredCommercialChannelId) {
        if (commercialConsent && preferredCommercialChannelId == null) {
            throw new IllegalArgumentException("Debe elegir canal comercial");
        }
        this.birthday = birthday;
        this.gender = gender;
        this.commercialConsent = commercialConsent;
        this.preferredCommercialChannelId = preferredCommercialChannelId;
    }

    public void configureCredit(
            boolean enabled,
            BigDecimal limit,
            int termDays,
            boolean blocked,
            boolean blockWhenOverdue) {
        if (limit != null && limit.signum() < 0) {
            throw new IllegalArgumentException("creditLimit no puede ser negativo");
        }
        if (termDays < 0 || termDays > 3650) {
            throw new IllegalArgumentException("paymentTermDays debe estar entre 0 y 3650");
        }
        creditEnabled = enabled;
        creditLimit = limit == null ? null : limit.setScale(2, java.math.RoundingMode.HALF_UP);
        paymentTermDays = termDays;
        creditBlocked = blocked;
        blockOnOverdue = blockWhenOverdue;
    }

    public void assignClientCode(UUID storeId, String code) {
        if (clientId != null) {
            throw new IllegalStateException("El codigo de cliente es inmutable");
        }
        clientCodeStoreId = Objects.requireNonNull(storeId, "tienda");
        clientId = PartyValues.required(code, "clientId");
    }

    public boolean hasCompleteFiscalData() {
        return fiscalName != null && documentType != null && documentNumber != null
                && fiscalAddress != null && fiscalAddress.isComplete();
    }

    public void deactivate() {
        activo = false;
    }

    public void activate() {
        activo = true;
    }

    public UUID getId() {
        return id;
    }

    public String getFiscalName() {
        return fiscalName;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public String getClientId() {
        return clientId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public FiscalAddress getFiscalAddress() {
        return fiscalAddress;
    }

    public String getPhone() {
        return telefono;
    }

    public String getEmail() {
        return email;
    }

    public String getNotes() {
        return observaciones;
    }

    public CustomerRate getRate() {
        return tarifa;
    }

    public BigDecimal getDiscount() {
        return descuento;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public CustomerGender getGender() {
        return gender;
    }

    public boolean hasCommercialConsent() {
        return commercialConsent;
    }

    public UUID getPreferredCommercialChannelId() {
        return preferredCommercialChannelId;
    }

    public boolean isActive() {
        return activo;
    }

    public Company getCompany() {
        return company;
    }

    public boolean isCreditEnabled() {
        return creditEnabled;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public int getPaymentTermDays() {
        return paymentTermDays;
    }

    public boolean isCreditBlocked() {
        return creditBlocked;
    }

    public boolean isBlockOnOverdue() {
        return blockOnOverdue;
    }
}
