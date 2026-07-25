package com.tpverp.backend.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "configuracion_interfaz_terminal")
public class TerminalInterfaceConfiguration {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terminal_id", nullable = false, unique = true)
    private Terminal terminal;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_mode", nullable = false, length = 16)
    private SaleInterfaceMode saleMode = SaleInterfaceMode.KEYBOARD;

    @Version
    private long version;

    protected TerminalInterfaceConfiguration() {
    }

    private TerminalInterfaceConfiguration(Terminal terminal) {
        this.id = UUID.randomUUID();
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    public static TerminalInterfaceConfiguration keyboard(Terminal terminal) {
        return new TerminalInterfaceConfiguration(terminal);
    }

    public void select(SaleInterfaceMode saleMode) {
        this.saleMode = Objects.requireNonNull(saleMode, "saleMode");
    }

    public UUID getId() {
        return id;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public SaleInterfaceMode getSaleMode() {
        return saleMode;
    }
}
