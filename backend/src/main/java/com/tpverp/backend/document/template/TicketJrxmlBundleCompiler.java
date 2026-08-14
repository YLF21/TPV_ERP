package com.tpverp.backend.document.template;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import net.sf.jasperreports.engine.JasperCompileManager;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

/** Compiles the fixed, database-backed ticket report family as one trusted bundle. */
@Component
public class TicketJrxmlBundleCompiler {

    public static final String MASTER_FILENAME = "ticket.jrxml";
    public static final String SUBREPORT_DIRECTORY_PARAMETER = "TPV_SUBREPORT_DIR";
    private static final Set<String> SECTIONS = Set.of(
            "ticket_cabecera", "ticket_cliente", "ticket_contenido",
            "ticket_impuesto", "ticket_pago", "ticket_pie");
    private static final Set<String> VARIANTS = Set.of("", "_compacta", "_minimalista");
    public static final Set<String> REQUIRED_FILENAMES = requiredFilenames();
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "cliente", "configuracion_documento_impreso_tienda", "cupon_promocional",
            "documento", "documento_linea", "documento_pago", "empresa", "licencia",
            "logo_documento_tienda", "metodo_pago", "payment_terminal_operation",
            "payment_terminal_receipt", "promocion", "tienda");
    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern SQL_MUTATION = Pattern.compile(
            "(?i)\\b(?:insert|update|delete|merge|alter|drop|truncate|grant|revoke|copy|call|do)\\b");
    private static final Pattern DANGEROUS_EXPRESSION = Pattern.compile(
            "(?i)(?:\\bRuntime\\b|\\bProcessBuilder\\b|\\bClassLoader\\b|"
                    + "\\bSystem\\s*\\.|\\bThread\\s*\\.|\\bFiles\\s*\\.|"
                    + "\\bPaths?\\s*\\.|\\bSocket\\b|\\bURL\\b|\\bURI\\b|"
                    + "\\bScriptEngine\\b|\\bUnsafe\\b|\\bObjectInputStream\\b|"
                    + "\\bFileInputStream\\b|\\bFileOutputStream\\b|"
                    + "\\bgetClass\\s*\\(|\\bforName\\s*\\(|\\bnewInstance\\s*\\(|"
                    + "\\bexec\\s*\\(|\\bloadLibrary\\s*\\(|java\\.nio\\.|"
                    + "java\\.net\\.|java\\.sql\\.|java\\.lang\\.reflect\\.|"
                    + "org\\.springframework\\.)");
    private static final Pattern SUBREPORT_EXPRESSION = Pattern.compile(
            "(?s)(<element\\s+kind=\"subreport\"[^>]*>.*?<expression><!\\[CDATA\\[)"
                    + "(.*?)(]]></expression>)");

    public CompiledBundle compile(Map<String, byte[]> sources) {
        if (sources == null || !sources.keySet().equals(REQUIRED_FILENAMES)) {
            throw new IllegalArgumentException("document_template_ticket_bundle_incomplete");
        }
        var compiled = new LinkedHashMap<String, CompiledReport>();
        for (var entry : new TreeMap<>(sources).entrySet()) {
            byte[] source = entry.getValue();
            validate(entry.getKey(), source);
            byte[] compilable = MASTER_FILENAME.equals(entry.getKey())
                    ? masterWithPortableSubreportDirectory(source) : source;
            try (var input = new ByteArrayInputStream(compilable);
                    var output = new ByteArrayOutputStream()) {
                JasperCompileManager.getInstance(SafeJrxmlCompiler.secureContext())
                        .compileToStream(input, output);
                compiled.put(entry.getKey(),
                        new CompiledReport(source, output.toByteArray()));
            } catch (Exception exception) {
                throw new IllegalArgumentException(
                        "document_template_jrxml_compile_failed", exception);
            }
        }
        return new CompiledBundle(Map.copyOf(compiled), bundleSha256(sources));
    }

    public static String bundleSha256(Map<String, byte[]> sources) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var entry : new TreeMap<>(sources).entrySet()) {
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(4).putInt(entry.getValue().length).array());
                digest.update(entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static void validate(String filename, byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("document_template_jrxml_required");
        }
        if (sourceBytes.length > SafeJrxmlCompiler.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("document_template_jrxml_too_large");
        }
        String source = decodeUtf8(sourceBytes);
        if (source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) {
            throw new IllegalArgumentException("document_template_jrxml_invalid");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(sourceBytes));
            Element root = document.getDocumentElement();
            if (root == null || !"jasperReport".equals(root.getTagName())
                    || !"java".equals(root.getAttribute("language"))) {
                throw new IllegalArgumentException("document_template_jrxml_root_invalid");
            }
            if (root.hasAttribute("resourceBundle") || root.hasAttribute("scriptletClass")) {
                throw new IllegalArgumentException(
                        "document_template_jrxml_external_resource_forbidden");
            }
            var nodes = document.getElementsByTagName("*");
            for (int index = 0; index < nodes.getLength(); index++) {
                Element element = (Element) nodes.item(index);
                String tag = element.getTagName();
                if (Set.of("import", "scriptlet", "scriptletExpression").contains(tag)) {
                    throw new IllegalArgumentException(
                            "document_template_jrxml_element_forbidden");
                }
                if ("query".equals(tag)) {
                    validateSql(element.getAttribute("language"), element.getTextContent());
                }
                if ((tag.endsWith("Expression") || "expression".equals(tag))
                        && DANGEROUS_EXPRESSION.matcher(element.getTextContent()).find()) {
                    throw new IllegalArgumentException(
                            "document_template_jrxml_expression_forbidden");
                }
                if ("subreport".equals(element.getAttribute("kind"))
                        && !MASTER_FILENAME.equals(filename)) {
                    throw new IllegalArgumentException(
                            "document_template_ticket_subreport_nested");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("document_template_jrxml_invalid", exception);
        }
    }

    private static void validateSql(String language, String query) {
        String normalized = query == null ? "" : query.trim();
        if (!"sql".equalsIgnoreCase(language)
                || !(normalized.regionMatches(true, 0, "select", 0, 6)
                        || normalized.regionMatches(true, 0, "with", 0, 4))
                || normalized.contains(";") || normalized.contains("--")
                || normalized.contains("/*") || SQL_MUTATION.matcher(normalized).find()
                || (!normalized.contains("$P{DOCUMENTO_ID}")
                        && !normalized.contains("$P{TIENDA_ID}"))) {
            throw new IllegalArgumentException("document_template_ticket_query_forbidden");
        }
        var tables = SQL_TABLE.matcher(normalized);
        while (tables.find()) {
            String table = tables.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!"lateral".equals(table) && !"lineas_fiscales".equals(table)
                    && !ALLOWED_TABLES.contains(table)) {
                throw new IllegalArgumentException(
                        "document_template_ticket_query_forbidden");
            }
        }
    }

    private static byte[] masterWithPortableSubreportDirectory(byte[] source) {
        String report = decodeUtf8(source);
        String parameterDeclaration = "<parameter name=\""
                + SUBREPORT_DIRECTORY_PARAMETER + "\"";
        if (!report.contains(parameterDeclaration)) {
            String parameter = "\t<parameter name=\"" + SUBREPORT_DIRECTORY_PARAMETER
                    + "\" class=\"java.lang.String\"/>\n";
            int query = report.indexOf("<query ");
            if (query < 0) {
                throw new IllegalArgumentException("document_template_jrxml_invalid");
            }
            report = report.substring(0, query) + parameter + report.substring(query);
        }
        var matcher = SUBREPORT_EXPRESSION.matcher(report);
        var transformed = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            count++;
            String expression = matcher.group(2);
            String portableExpression = expression.contains(
                    "$P{" + SUBREPORT_DIRECTORY_PARAMETER + "}")
                    ? expression
                    : "$P{" + SUBREPORT_DIRECTORY_PARAMETER + "} + ("
                            + expression + ")";
            matcher.appendReplacement(transformed, Matcher.quoteReplacement(
                    matcher.group(1) + portableExpression + matcher.group(3)));
        }
        matcher.appendTail(transformed);
        if (count != 6) {
            throw new IllegalArgumentException(
                    "document_template_ticket_subreport_expression_invalid");
        }
        return transformed.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8(byte[] sourceBytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String source = decoder.decode(ByteBuffer.wrap(sourceBytes)).toString();
            return !source.isEmpty() && source.charAt(0) == '\ufeff'
                    ? source.substring(1) : source;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "document_template_jrxml_utf8_required", exception);
        }
    }

    private static Set<String> requiredFilenames() {
        var names = new java.util.LinkedHashSet<String>();
        names.add(MASTER_FILENAME);
        for (String section : SECTIONS) {
            for (String variant : VARIANTS) {
                names.add(section + variant + ".jrxml");
            }
        }
        return Set.copyOf(names);
    }

    public record CompiledReport(byte[] source, byte[] compiled) {
        public CompiledReport {
            source = source.clone();
            compiled = compiled.clone();
        }
        @Override public byte[] source() { return source.clone(); }
        @Override public byte[] compiled() { return compiled.clone(); }
    }

    public record CompiledBundle(Map<String, CompiledReport> reports, String sha256) {
        public CompiledBundle {
            reports = Map.copyOf(reports);
        }
    }
}
