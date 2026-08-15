package com.tpverp.backend.document.template;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

@Component
public class SafeJrxmlCompiler {

    static final int DATA_SCHEMA_VERSION = 1;
    static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ELEMENTS = 5_000;
    private static final int MAX_EXPRESSION_CHARS = 100_000;

    private static final Set<String> FORBIDDEN_ELEMENTS = Set.of(
            "import", "scriptlet", "scriptletExpression", "subreport",
            "subreportExpression", "connectionExpression", "returnValue",
            "anchorNameExpression", "hyperlinkReferenceExpression",
            "hyperlinkAnchorExpression", "hyperlinkPageExpression");
    private static final Set<String> ALLOWED_CLASSES = Set.of(
            "java.lang.Boolean",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Double",
            "java.lang.String",
            "java.lang.Object",
            "java.math.BigDecimal",
            "java.time.LocalDate",
            "java.time.Instant",
            "java.util.Date",
            "java.sql.Timestamp",
            "byte[]",
            "java.text.DecimalFormat",
            "java.io.InputStream",
            "java.io.ByteArrayInputStream",
            "java.util.Base64",
            "java.util.stream.StreamSupport",
            "java.util.stream.Collectors",
            "com.fasterxml.jackson.databind.JsonNode");
    private static final Set<String> ALLOWED_COMPONENTS = Set.of(
            "list", "barcode4j:QRCode", "barcode4j:Code128");
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "ByteArrayInputStream", "DecimalFormat",
            "equals", "equalsIgnoreCase", "trim", "isEmpty",
            "length", "substring", "indexOf", "getDecoder", "decode", "format",
            "stream", "spliterator", "map", "asText", "collect", "joining");
    private static final Pattern QUALIFIED_CLASS = Pattern.compile(
            "(?<![A-Za-z0-9_$])(?:[a-z_][A-Za-z0-9_$]*\\.){2,}[A-Z][A-Za-z0-9_$]*");
    private static final Pattern FORBIDDEN_EXPRESSION = Pattern.compile(
            "(?i)(?:\\bRuntime\\b|\\bProcessBuilder\\b|\\bClassLoader\\b|"
                    + "\\bClass\\s*\\.|\\bSystem\\s*\\.|\\bThread\\s*\\.|"
                    + "\\bFiles\\s*\\.|\\bPaths?\\s*\\.|\\bSocket\\b|"
                    + "\\bURL\\b|\\bURI\\b|\\bScriptEngine\\b|\\bUnsafe\\b|"
                    + "\\bObjectInputStream\\b|\\bFileInputStream\\b|"
                    + "\\bFileOutputStream\\b|\\bgetClass\\s*\\(|"
                    + "\\bforName\\s*\\(|\\bnewInstance\\s*\\(|\\bexec\\s*\\(|"
                    + "\\bloadLibrary\\s*\\(|java\\.nio\\.|java\\.net\\.|"
                    + "java\\.sql\\.|java\\.lang\\.reflect\\.|javax\\.|jakarta\\.|"
                    + "org\\.springframework\\.|(?:/\\*|//)|\\\\u[0-9a-f]{4}|;|::)");
    private static final Pattern ALLOWED_NEW = Pattern.compile(
            "new\\s+(java\\.io\\.ByteArrayInputStream|java\\.text\\.DecimalFormat)\\s*\\(");
    private static final Pattern METHOD_CALL = Pattern.compile(
            "\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

    public CompiledTemplate compile(byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("document_template_jrxml_required");
        }
        if (sourceBytes.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("document_template_jrxml_too_large");
        }
        String source = decodeUtf8(sourceBytes);
        validateXml(source);
        try (var input = new ByteArrayInputStream(sourceBytes);
                var output = new ByteArrayOutputStream()) {
            JasperCompileManager.getInstance(secureContext()).compileToStream(input, output);
            return new CompiledTemplate(
                    sourceBytes.clone(), output.toByteArray(), sha256(sourceBytes));
        } catch (JRException | IOException exception) {
            throw new IllegalArgumentException(
                    "document_template_jrxml_compile_failed", exception);
        }
    }

    private static String decodeUtf8(byte[] sourceBytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String source = decoder.decode(ByteBuffer.wrap(sourceBytes)).toString();
            if (!source.isEmpty() && source.charAt(0) == '\ufeff') {
                source = source.substring(1);
            }
            return source;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "document_template_jrxml_utf8_required", exception);
        }
    }

    private static void validateXml(String source) {
        if (source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("document_template_jrxml_invalid");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            var document = builder.parse(new ByteArrayInputStream(
                    source.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            if (root == null || !"jasperReport".equals(root.getTagName())) {
                throw new IllegalArgumentException("document_template_jrxml_root_invalid");
            }
            if (!"java".equals(root.getAttribute("language"))) {
                throw new IllegalArgumentException("document_template_jrxml_language_invalid");
            }
            if (hasValue(root, "resourceBundle") || hasValue(root, "scriptletClass")) {
                throw new IllegalArgumentException("document_template_jrxml_external_resource_forbidden");
            }
            validateElements(document.getElementsByTagName("*"));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new IllegalArgumentException("document_template_jrxml_invalid", exception);
        }
    }

    private static void validateElements(org.w3c.dom.NodeList nodes) {
        if (nodes.getLength() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("document_template_jrxml_too_complex");
        }
        int expressionChars = 0;
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String tag = element.getTagName();
            if (FORBIDDEN_ELEMENTS.contains(tag)) {
                throw new IllegalArgumentException("document_template_jrxml_element_forbidden");
            }
            if ("query".equals(tag)) {
                String language = element.getAttribute("language");
                if (!"jsonql".equals(language) && !"json".equals(language)) {
                    throw new IllegalArgumentException("document_template_jrxml_query_forbidden");
                }
            }
            if ("property".equals(tag)) {
                validateProperty(element.getAttribute("name"));
            }
            if (element.hasAttribute("class")) {
                validateClass(element.getAttribute("class"));
            }
            if ("component".equals(tag) && element.hasAttribute("kind")
                    && !ALLOWED_COMPONENTS.contains(element.getAttribute("kind"))) {
                throw new IllegalArgumentException("document_template_jrxml_component_forbidden");
            }
            if (isExpression(tag)) {
                String expression = element.getTextContent();
                expressionChars += expression.length();
                validateExpression(expression);
            }
            if ("element".equals(tag) && "image".equals(element.getAttribute("kind"))) {
                validateInlineImage(element);
            }
        }
        if (expressionChars > MAX_EXPRESSION_CHARS) {
            throw new IllegalArgumentException("document_template_jrxml_too_complex");
        }
    }

    private static void validateProperty(String name) {
        boolean allowed = name.startsWith("com.jaspersoft.studio.")
                || "net.sf.jasperreports.json.field.expression".equals(name)
                || "net.sf.jasperreports.jsonql.field.expression".equals(name);
        if (!allowed) {
            throw new IllegalArgumentException("document_template_jrxml_property_forbidden");
        }
    }

    private static void validateClass(String className) {
        if (!ALLOWED_CLASSES.contains(className)) {
            throw new IllegalArgumentException("document_template_jrxml_class_forbidden");
        }
    }

    private static boolean isExpression(String tag) {
        return tag.endsWith("Expression") || "expression".equals(tag);
    }

    private static void validateExpression(String expression) {
        if (FORBIDDEN_EXPRESSION.matcher(expression).find()) {
            throw new IllegalArgumentException("document_template_jrxml_expression_forbidden");
        }
        var classes = QUALIFIED_CLASS.matcher(expression);
        while (classes.find()) {
            validateClass(classes.group());
        }
        var methods = METHOD_CALL.matcher(expression);
        while (methods.find()) {
            if (!ALLOWED_METHODS.contains(methods.group(1))) {
                throw new IllegalArgumentException(
                        "document_template_jrxml_expression_forbidden");
            }
        }
        var newExpressions = Pattern.compile("\\bnew\\s+[A-Za-z_$]").matcher(expression);
        int allowedNew = 0;
        var allowed = ALLOWED_NEW.matcher(expression);
        while (allowed.find()) {
            allowedNew++;
        }
        int allNew = 0;
        while (newExpressions.find()) {
            allNew++;
        }
        if (allNew != allowedNew) {
            throw new IllegalArgumentException("document_template_jrxml_expression_forbidden");
        }
    }

    private static void validateInlineImage(Element image) {
        var expressions = image.getElementsByTagName("expression");
        if (expressions.getLength() != 1) {
            throw new IllegalArgumentException("document_template_jrxml_external_resource_forbidden");
        }
        String expression = expressions.item(0).getTextContent();
        if (("$P{" + InvoiceJasperRenderer.ISSUER_LOGO_STREAM_PARAMETER + "}")
                .equals(expression.trim())) {
            return;
        }
        if (!expression.contains("new java.io.ByteArrayInputStream")
                || !expression.contains("java.util.Base64.getDecoder().decode")) {
            throw new IllegalArgumentException("document_template_jrxml_external_resource_forbidden");
        }
    }

    private static boolean hasValue(Element element, String attribute) {
        return element.hasAttribute(attribute) && !element.getAttribute(attribute).isBlank();
    }

    static SimpleJasperReportsContext secureContext() {
        var context = new SimpleJasperReportsContext(
                DefaultJasperReportsContext.getInstance());
        context.setProperty("net.sf.jasperreports.report.class.filter.enabled", "true");
        context.setProperty("net.sf.jasperreports.report.class.whitelist.tpv",
                String.join(",", ALLOWED_CLASSES));
        return context;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    public record CompiledTemplate(byte[] source, byte[] compiled, String sha256) {

        public CompiledTemplate {
            source = source.clone();
            compiled = compiled.clone();
        }

        @Override
        public byte[] source() {
            return source.clone();
        }

        @Override
        public byte[] compiled() {
            return compiled.clone();
        }
    }
}
