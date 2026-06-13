package com.tpv.licenseissuer;

import com.tpv.licenseissuer.ui.LicenseIssuerFrame;
import javax.swing.SwingUtilities;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class LicenseIssuerApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(LicenseIssuerApplication.class)
                .headless(false)
                .web(WebApplicationType.NONE)
                .run(args);
        SwingUtilities.invokeLater(() -> new LicenseIssuerFrame().setVisible(true));
    }
}
