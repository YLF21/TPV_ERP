package com.tpv.licenseissuer.ui;

import com.tpv.licenseissuer.model.LicenseDetails;
import com.tpv.licenseissuer.model.TaxRegime;
import com.tpv.licenseissuer.model.TaxpayerType;
import com.tpv.licenseissuer.service.LicenseIssuanceService;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class LicenseIssuerFrame extends JFrame {
    private final JTextField requestPath = new JTextField(32);
    private final JTextField keyStorePath = new JTextField(32);
    private final JTextField outputPath = new JTextField(32);
    private final JPasswordField password = new JPasswordField(32);
    private final JTextField taxId = new JTextField(16);
    private final JComboBox<TaxpayerType> taxpayerType = new JComboBox<>(TaxpayerType.values());
    private final JTextField company = new JTextField(32);
    private final JTextField store = new JTextField(32);
    private final JTextField validFrom = new JTextField(LocalDate.now().toString(), 12);
    private final JTextField validUntil = new JTextField(LocalDate.now().plusYears(1).toString(), 12);
    private final JSpinner maxWindows = new JSpinner(new SpinnerNumberModel(1, 1, 100000, 1));
    private final JSpinner maxPda = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 1));
    private final JComboBox<TaxRegime> impuestos = new JComboBox<>(TaxRegime.values());
    private final LicenseIssuanceService service = new LicenseIssuanceService();

    public LicenseIssuerFrame() {
        super("TPV ERP License Issuer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        add(buildForm(), BorderLayout.CENTER);
        JButton issueButton = new JButton("Emitir licencia");
        issueButton.addActionListener(event -> issueLicense());
        JPanel actions = new JPanel();
        actions.add(issueButton);
        add(actions, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildForm() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        int row = 0;
        addFileRow(panel, constraints, row++, "Solicitud JSON", requestPath, false, "json");
        addFileRow(panel, constraints, row++, "Identidad PKCS#12", keyStorePath, true, "p12");
        addRow(panel, constraints, row++, "Contraseña", password);
        addRow(panel, constraints, row++, "NIF", taxId);
        addRow(panel, constraints, row++, "Tipo de contribuyente", taxpayerType);
        addRow(panel, constraints, row++, "Empresa", company);
        addRow(panel, constraints, row++, "Tienda", store);
        addRow(panel, constraints, row++, "Válida desde (AAAA-MM-DD)", validFrom);
        addRow(panel, constraints, row++, "Válida hasta (AAAA-MM-DD)", validUntil);
        addRow(panel, constraints, row++, "Máx. equipos Windows", maxWindows);
        addRow(panel, constraints, row++, "Máx. PDA", maxPda);
        addRow(panel, constraints, row++, "Régimen de impuestos", impuestos);
        addFileRow(panel, constraints, row, "Archivo de licencia", outputPath, true, "json");
        return panel;
    }

    private void addFileRow(
            JPanel panel,
            GridBagConstraints constraints,
            int row,
            String label,
            JTextField field,
            boolean save,
            String extension) {
        addRow(panel, constraints, row, label, field);
        JButton browse = new JButton("Examinar");
        browse.addActionListener(event -> chooseFile(field, save, extension));
        constraints.gridx = 2;
        constraints.gridy = row;
        constraints.weightx = 0;
        panel.add(browse, constraints);
    }

    private void addRow(
            JPanel panel,
            GridBagConstraints constraints,
            int row,
            String label,
            java.awt.Component component) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(component, constraints);
    }

    private void chooseFile(JTextField field, boolean save, String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("*." + extension, extension));
        int result = save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void issueLicense() {
        char[] secret = password.getPassword();
        try {
            LicenseDetails details = new LicenseDetails(
                    taxId.getText(),
                    (TaxpayerType) taxpayerType.getSelectedItem(),
                    company.getText(),
                    store.getText(),
                    LocalDate.parse(validFrom.getText().trim()),
                    LocalDate.parse(validUntil.getText().trim()),
                    (Integer) maxWindows.getValue(),
                    (Integer) maxPda.getValue(),
                    (TaxRegime) impuestos.getSelectedItem());
            service.issue(
                    Path.of(requestPath.getText().trim()),
                    details,
                    Path.of(keyStorePath.getText().trim()),
                    secret,
                    Path.of(outputPath.getText().trim()));
            JOptionPane.showMessageDialog(this, "Licencia exportada correctamente.");
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(
                    this,
                    exception.getMessage(),
                    "No se pudo emitir la licencia",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            Arrays.fill(secret, '\0');
            password.setText("");
        }
    }
}
