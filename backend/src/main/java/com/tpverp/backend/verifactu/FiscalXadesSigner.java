package com.tpverp.backend.verifactu;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.MimeType;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.enumerations.SignaturePackaging;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.Policy;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import eu.europa.esig.dss.token.Pkcs12SignatureToken;
import eu.europa.esig.dss.token.SignatureTokenConnection;
import eu.europa.esig.dss.xades.XAdESSignatureParameters;
import eu.europa.esig.dss.xades.signature.XAdESService;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Signs NO VERI*FACTU records with the AEAT XAdES-EPES profile. */
@Service
public class FiscalXadesSigner {

    static final String POLICY_ID = "urn:oid:2.16.724.1.3.1.1.2.1.9";
    static final String POLICY_URL = "https://sede.administracion.gob.es/politica_de_firma_anexo_1.pdf";
    static final byte[] POLICY_SHA1 = Base64.getDecoder().decode("G7roucf600+f03r/o0bAOQ6WAs0=");

    private final ManagedCertificateKeyStoreFactory managedCertificates;
    private final FiscalRuntimeProperties runtime;

    public FiscalXadesSigner(
            ManagedCertificateKeyStoreFactory managedCertificates,
            FiscalRuntimeProperties runtime) {
        this.managedCertificates = managedCertificates;
        this.runtime = runtime;
    }

    public String sign(FiscalRecord record, String unsignedXml) {
        if (record == null || record.getFiscalMode() != FiscalMode.NO_VERIFACTU) {
            throw new IllegalArgumentException("Solo se pueden firmar registros NO_VERIFACTU");
        }
        return sign(record.getCompanyId(), record.getInstallationId(), unsignedXml);
    }

    /** Signs a standalone RegistroEvento with the same fiscal identity as records. */
    public String signEvent(java.util.UUID companyId, java.util.UUID installationId,
            String unsignedXml) {
        return sign(companyId, installationId, unsignedXml);
    }

    private String sign(java.util.UUID companyId, java.util.UUID installationId,
            String unsignedXml) {
        if (unsignedXml == null || unsignedXml.isBlank()) {
            throw new IllegalArgumentException("El XML a firmar es obligatorio");
        }
        try (TokenHandle handle = token(companyId, installationId)) {
            var key = rsaKey(handle.token().getKeys());
            var certificate = key.getCertificate().getCertificate();
            certificate.checkValidity();

            var input = new InMemoryDocument(
                    unsignedXml.getBytes(StandardCharsets.UTF_8), "registro-fiscal.xml",
                    MimeType.fromMimeTypeString("application/xml"));
            var parameters = new XAdESSignatureParameters();
            // DSS 6.4 exposes the signing implementation through the baseline-B
            // profile. The explicit B-level SignaturePolicyId below makes the
            // resulting XAdES structure policy-bearing (EPES).
            parameters.setSignatureLevel(SignatureLevel.XAdES_BASELINE_B);
            parameters.setSignaturePackaging(SignaturePackaging.ENVELOPED);
            parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
            parameters.setReferenceDigestAlgorithm(DigestAlgorithm.SHA256);
            parameters.setSigningCertificateDigestMethod(DigestAlgorithm.SHA256);
            parameters.setSigningCertificate(key.getCertificate());
            parameters.setCertificateChain(key.getCertificateChain());
            var policy = new Policy();
            policy.setId(POLICY_ID);
            policy.setSpuri(POLICY_URL);
            policy.setDigestAlgorithm(DigestAlgorithm.SHA1);
            policy.setDigestValue(POLICY_SHA1.clone());
            parameters.bLevel().setSignaturePolicy(policy);

            var service = new XAdESService(new CommonCertificateVerifier());
            ToBeSigned toBeSigned = service.getDataToSign(input, parameters);
            SignatureValue signature = handle.token().sign(
                    toBeSigned, SignatureAlgorithm.RSA_SHA256, key);
            var signed = service.signDocument(input, parameters, signature);
            var bytes = relocateEventSignature(write(signed));
            verify(bytes, certificate.getPublicKey());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar o verificar la firma XAdES", exception);
        }
    }

    private TokenHandle token(java.util.UUID companyId, java.util.UUID installationId) throws Exception {
        if (runtime.isSandbox()) {
            if (runtime.devSigningPkcs12().isBlank() || runtime.devSigningPassword().isBlank()) {
                throw new IllegalStateException(
                        "NO_VERIFACTU en SANDBOX requiere un PKCS#12 temporal de pruebas");
            }
            var password = runtime.devSigningPassword().toCharArray();
            return new TokenHandle(
                    new Pkcs12SignatureToken(Path.of(runtime.devSigningPkcs12()).toFile(),
                            new KeyStore.PasswordProtection(password)), password);
        }
        var managed = managedCertificates.activeForCompany(companyId, installationId);
        var password = managed.password();
        try {
            var output = new ByteArrayOutputStream();
            managed.keyStore().store(output, password);
            return new TokenHandle(new Pkcs12SignatureToken(output.toByteArray(),
                    new KeyStore.PasswordProtection(password)), password, managed);
        } catch (Exception exception) {
            managed.close();
            java.util.Arrays.fill(password, '\0');
            throw exception;
        }
    }

    private static DSSPrivateKeyEntry rsaKey(List<DSSPrivateKeyEntry> keys) {
        return keys.stream()
                .filter(key -> key.getCertificate().getPublicKey().getAlgorithm()
                        .equalsIgnoreCase("RSA"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El certificado de firma debe tener una clave RSA"));
    }

    private static byte[] write(eu.europa.esig.dss.model.DSSDocument document) throws Exception {
        var output = new ByteArrayOutputStream();
        document.writeTo(output);
        return output.toByteArray();
    }

    // EventosSIF.xsd places the enveloped Signature inside Evento, while DSS
    // appends it to the document element by default. Move only that case before
    // validation; the signed octets remain the same because the enveloped
    // transform excludes the Signature element itself.
    private static byte[] relocateEventSignature(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(
                new InputSource(new StringReader(new String(xml, StandardCharsets.UTF_8))));
        if (!"RegistroEvento".equals(document.getDocumentElement().getLocalName())) {
            return xml;
        }
        var signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        var events = document.getElementsByTagNameNS(FiscalEventXmlService.EVENT_NS, "Evento");
        if (signatures.getLength() != 1 || events.getLength() != 1
                || signatures.item(0).getParentNode() == events.item(0)) {
            return xml;
        }
        var signature = signatures.item(0);
        events.item(0).appendChild(signature);
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void verify(byte[] xml, PublicKey publicKey) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(
                new InputSource(new StringReader(new String(xml, StandardCharsets.UTF_8))));
        var signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatures.getLength() != 1) {
            throw new IllegalStateException("El XML firmado debe contener una unica Signature");
        }
        var signature = (Element) signatures.item(0);
        var signedProperties = document.getElementsByTagNameNS(
                "http://uri.etsi.org/01903/v1.3.2#", "SignedProperties");
        if (signedProperties.getLength() == 1) {
            var element = (Element) signedProperties.item(0);
            element.setIdAttribute("Id", true);
        }
        var context = new DOMValidateContext(publicKey, signature);
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
        var valid = XMLSignatureFactory.getInstance("DOM")
                .unmarshalXMLSignature(context).validate(context);
        if (!valid) {
            throw new IllegalStateException("La validacion XMLDSig de la firma ha fallado");
        }
    }

    private static final class TokenHandle implements AutoCloseable {
        private final SignatureTokenConnection token;
        private final char[] password;
        private final ManagedCertificateKeyStoreFactory.ManagedKeyStore managed;

        private TokenHandle(SignatureTokenConnection token, char[] password) {
            this(token, password, null);
        }

        private TokenHandle(SignatureTokenConnection token, char[] password,
                ManagedCertificateKeyStoreFactory.ManagedKeyStore managed) {
            this.token = token;
            this.password = password;
            this.managed = managed;
        }

        SignatureTokenConnection token() { return token; }

        @Override
        public void close() {
            token.close();
            java.util.Arrays.fill(password, '\0');
            if (managed != null) {
                managed.close();
            }
        }
    }
}
