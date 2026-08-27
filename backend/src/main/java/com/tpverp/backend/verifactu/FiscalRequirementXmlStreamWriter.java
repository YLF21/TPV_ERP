package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Bounded-memory writer for an AEAT requirement batch. It validates one frozen
 * signed record at a time and copies its original XML lexical content into the
 * envelope, avoiding a million-record DOM or List allocation.
 */
final class FiscalRequirementXmlStreamWriter implements AutoCloseable {
    private static final String XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String LR_NS = "https://www2.agenciatributaria.gob.es/static_files/common/"
            + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroLR.xsd";
    private static final String SF_NS = "https://www2.agenciatributaria.gob.es/static_files/common/"
            + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd";
    private final OutputStream output;
    private final Path path;
    private final boolean initialFinished;
    private boolean finished;
    private boolean closed;
    private long records;

    FiscalRequirementXmlStreamWriter(Path path, String issuerName, String issuerTaxId,
            FiscalRequirementContext requirement) {
        try {
            this.path = Objects.requireNonNull(path, "path");
            this.finished = requirement.finished();
            this.initialFinished = this.finished;
            this.output = Files.newOutputStream(Objects.requireNonNull(path, "path"));
            write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            write("<sfLR:RegFactuSistemaFacturacion xmlns:sfLR=\"" + LR_NS
                    + "\" xmlns:sf=\"" + SF_NS + "\">");
            write("<sfLR:Cabecera><sf:ObligadoEmision><sf:NombreRazon>");
            write(escape(issuerName));
            write("</sf:NombreRazon><sf:NIF>");
            write(escape(issuerTaxId));
            write("</sf:NIF></sf:ObligadoEmision><sf:RemisionRequerimiento><sf:RefRequerimiento>");
            write(escape(requirement.reference()));
            write("</sf:RefRequerimiento><sf:FinRequerimiento>");
            write(escape(requirement.finishedValue()));
            write("</sf:FinRequerimiento></sf:RemisionRequerimiento></sfLR:Cabecera>");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    void appendSignedRecord(String signedXml) {
        if (records >= 1_000) {
            throw new IllegalStateException("fiscal_required_submission_batch_limit");
        }
        var body = validateAndRemoveDeclaration(signedXml);
        try {
            write("<sfLR:RegistroFactura>");
            write(body);
            write("</sfLR:RegistroFactura>");
            records++;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    long records() {
        return records;
    }

    /** Marks this envelope as the final submission before it is closed. */
    void markFinished() {
        this.finished = true;
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            write("</sfLR:RegFactuSistemaFacturacion>");
            output.close();
            closed = true;
            if (finished != initialFinished) {
                replaceFinishedMarker(finished ? "N" : "S", finished ? "S" : "N");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void replaceFinishedMarker(String from, String to) throws IOException {
        // The marker is fixed-width and lives in the small header. Patch it
        // after closing the stream so the final envelope can be selected only
        // once the bounded query has revealed that no next batch exists.
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            var bytes = new byte[8192];
            var length = file.read(bytes);
            if (length <= 0) throw new IOException("Cabecera del sobre AEAT vacia");
            var marker = (">" + from + "</sf:FinRequerimiento>")
                    .getBytes(StandardCharsets.UTF_8);
            var offset = indexOf(bytes, length, marker);
            if (offset < 0) throw new IOException("Marcador FinRequerimiento no encontrado");
            file.seek(offset + 1);
            file.write(to.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int indexOf(byte[] value, int length, byte[] target) {
        outer: for (int i = 0; i <= length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (value[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void write(String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String validateAndRemoveDeclaration(String signedXml) {
        if (signedXml == null || signedXml.isBlank()) {
            throw new IllegalStateException("fiscal_required_submission_unsigned_record");
        }
        var input = signedXml.stripLeading();
        if (input.startsWith("\uFEFF")) input = input.substring(1).stripLeading();
        var factory = XMLInputFactory.newFactory();
        try {
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        } catch (IllegalArgumentException ignored) {
            // The JDK implementation supports both properties; a provider may
            // reject one, while the no-DTD parser below still rejects hostile input.
        }
        try {
            var reader = factory.createXMLStreamReader(
                    new java.io.StringReader(input));
            boolean rootSeen = false;
            boolean rootClosed = false;
            boolean signatureSeen = false;
            int depth = 0;
            while (reader.hasNext()) {
                var event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    if (depth == 0) {
                        if (rootSeen) {
                            throw new IllegalArgumentException(
                                    "El XML firmado contiene mas de un elemento raiz");
                        }
                        rootSeen = true;
                        if (!SF_NS.equals(reader.getNamespaceURI())
                                || !("RegistroAlta".equals(reader.getLocalName())
                                || "RegistroAnulacion".equals(reader.getLocalName()))) {
                            throw new IllegalArgumentException(
                                    "El XML firmado no contiene RegistroAlta ni RegistroAnulacion");
                        }
                    }
                    if (depth > 0 && XMLDSIG_NS.equals(reader.getNamespaceURI())
                            && "Signature".equals(reader.getLocalName())) {
                        signatureSeen = true;
                    }
                    depth++;
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    depth--;
                    if (depth < 0) {
                        throw new IllegalArgumentException("El XML firmado no es un documento completo");
                    }
                    if (depth == 0) rootClosed = true;
                } else if (event == XMLStreamReader.CHARACTERS
                        || event == XMLStreamReader.CDATA) {
                    if (depth == 0 && !reader.getText().isBlank()) {
                        throw new IllegalArgumentException(
                                "El XML firmado contiene texto fuera de su raiz");
                    }
                } else if (event == XMLStreamReader.DTD) {
                    throw new IllegalArgumentException("El XML firmado no puede contener DTD");
                }
            }
            reader.close();
            if (!rootSeen || !rootClosed || depth != 0 || !signatureSeen) {
                if (rootSeen && rootClosed && depth == 0 && !signatureSeen) {
                    throw new IllegalArgumentException("El XML fiscal no contiene firma XMLDSig");
                }
                throw new IllegalArgumentException("El XML firmado no es un documento completo");
            }
        } catch (XMLStreamException exception) {
            throw new IllegalArgumentException("El XML firmado no es valido", exception);
        }
        var declaration = input.indexOf("?>");
        if (input.startsWith("<?xml")) {
            if (declaration < 0) {
                throw new IllegalArgumentException("La declaracion XML firmada no es valida");
            }
            input = input.substring(declaration + 2).stripLeading();
        }
        return input;
    }

    private static String escape(String value) {
        return Objects.requireNonNull(value, "value").replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
