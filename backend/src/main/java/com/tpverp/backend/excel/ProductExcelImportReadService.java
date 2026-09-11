package com.tpverp.backend.excel;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;

/** Reads the first sheet without evaluating formulas or retaining the source file. */
@Service
public class ProductExcelImportReadService {

    public static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    public static final int MAX_ROWS = 5_000;
    /** Defensive grid bound; identity/detected-row limit is enforced by preview after mapping. */
    public static final int MAX_GRID_ROWS = 100_000;
    public static final int MAX_COLUMNS = 256;
    public static final int MAX_NON_EMPTY_CELLS = 250_000;
    public static final int MAX_FORMULAS = 20_000;
    /** Prevents a sparse/formatted sheet from materialising a huge rectangular grid. */
    public static final long MAX_MATERIALIZED_CELLS = 250_000L;
    /** Excel's maximum cell text length. */
    public static final int MAX_CELL_CHARACTERS = 32_767;
    /** Defensive aggregate limit for materialised values and formula text. */
    public static final long MAX_TEXT_CHARACTERS = 5_000_000L;
    /** Prevents pathological shared number formats from reaching POI's formatter. */
    public static final int MAX_NUMBER_FORMAT_CHARACTERS = 1_024;
    /** Bounds the OOXML container before POI parses XML or allocates workbook objects. */
    public static final int MAX_ZIP_ENTRIES = 4_096;
    public static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024L * 1024L;
    public static final long MAX_ZIP_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L;

    private static final byte[] OOXML_SIGNATURE = { 0x50, 0x4b, 0x03, 0x04 };
    private static final byte[] OLE2_SIGNATURE = {
            (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
            (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
    };
    private static final Pattern SCIENTIFIC_NUMBER = Pattern.compile(
            "^[+-]?\\d+(?:[.,]\\d+)?[Ee][+-]?\\d+$");
    private static final Pattern NUMERIC_FORMAT_CONDITION = Pattern.compile(
            "^(?:<=|>=|<>|=|<|>)\\s*[+-]?(?:\\d[\\d.,]*|[.,]\\d+)(?:[Ee][+-]?\\d+)?$");
    private static final NumericFormatDescriptor GENERAL_FORMAT =
            new NumericFormatDescriptor(false, 1, 1, 1);

    private final AuditService audit;
    private final CurrentOrganization organization;

    public ProductExcelImportReadService(AuditService audit) {
        this(audit, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductExcelImportReadService(AuditService audit, CurrentOrganization organization) {
        this.audit = audit;
        this.organization = organization;
    }

    public ReadResult read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            recordFailure(file == null ? null : file.getOriginalFilename(), null, "FILE_EMPTY");
            throw error("FILE_EMPTY", "El fichero Excel esta vacio");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            recordFailure(file.getOriginalFilename(), null, "FILE_TOO_LARGE");
            throw error("FILE_TOO_LARGE", "El fichero supera 10 MB");
        }
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            recordFailure(file.getOriginalFilename(), null, "FILE_READ_FAILED");
            throw error("FILE_READ_FAILED", "No se pudo leer el fichero", exception);
        }
        String hash = sha256(bytes);
        // Multipart metadata is not authoritative: a custom MultipartFile can
        // report a small size while carrying an oversized byte array.
        if (bytes.length > MAX_FILE_BYTES) {
            recordFailure(file.getOriginalFilename(), hash, "FILE_TOO_LARGE");
            throw error("FILE_TOO_LARGE", "El fichero supera 10 MB");
        }
        try {
            validateExtension(file.getOriginalFilename());
        } catch (ProductExcelImportException exception) {
            recordFailure(file.getOriginalFilename(), hash, exception.code());
            throw exception;
        }
        try {
            verifyOfficeSignature(bytes, file.getOriginalFilename());
            preflightXlsxZip(bytes, file.getOriginalFilename());
            ReadResult result = readWorkbook(bytes, file.getOriginalFilename(), hash);
            recordAudit(AuditResult.EXITO, auditDetails(result));
            return result;
        } catch (ProductExcelImportException exception) {
            recordAudit(AuditResult.FALLO,
                    Map.of("fileName", safeFileName(file.getOriginalFilename()), "sha256", hash, "code", exception.code()));
            throw exception;
        } catch (EncryptedDocumentException exception) {
            recordAudit(AuditResult.FALLO,
                    Map.of("fileName", safeFileName(file.getOriginalFilename()), "sha256", hash, "code", "WORKBOOK_ENCRYPTED"));
            throw error("WORKBOOK_ENCRYPTED", "El libro Excel esta cifrado o protegido", exception);
        } catch (NotOfficeXmlFileException exception) {
            recordAudit(AuditResult.FALLO,
                    Map.of("fileName", safeFileName(file.getOriginalFilename()), "sha256", hash, "code", "WORKBOOK_CORRUPT"));
            throw error("WORKBOOK_CORRUPT", "El libro Excel esta corrupto o no es valido", exception);
        } catch (IOException exception) {
            // A valid PK/OLE signature is not enough: truncated ZIP/OLE streams
            // and parse failures are corrupt workbooks, not unreadable uploads.
            recordAudit(AuditResult.FALLO,
                    Map.of("fileName", safeFileName(file.getOriginalFilename()), "sha256", hash, "code", "WORKBOOK_CORRUPT"));
            throw error("WORKBOOK_CORRUPT", "El libro Excel esta corrupto o truncado", exception);
        } catch (Exception exception) {
            recordAudit(AuditResult.FALLO,
                    Map.of("fileName", safeFileName(file.getOriginalFilename()), "sha256", hash, "code", "WORKBOOK_UNREADABLE"));
            throw error("WORKBOOK_UNREADABLE", "No se pudo abrir el libro Excel", exception);
        }
    }

    private ReadResult readWorkbook(byte[] bytes, String fileName, String hash) throws IOException {
        try (InputStream input = FileMagic.prepareToCheckMagic(new ByteArrayInputStream(bytes));
             Workbook workbook = WorkbookFactory.create(input)) {
            String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if ((lowerName.endsWith(".xlsx") && !(workbook instanceof XSSFWorkbook))
                    || (lowerName.endsWith(".xls") && !(workbook instanceof HSSFWorkbook))) {
                throw error("FILE_SIGNATURE_INVALID", "El contenido del fichero no corresponde a su extensión XLS/XLSX");
            }
            if (workbook instanceof XSSFWorkbook xssf && xssf.isMacroEnabled()) {
                throw error("WORKBOOK_MACRO_UNSUPPORTED", "Los libros con macros no son compatibles");
            }
            if (workbook instanceof HSSFWorkbook && hasVbaProject(bytes)) {
                throw error("WORKBOOK_MACRO_UNSUPPORTED", "Los libros con macros no son compatibles");
            }
            if (workbook.getNumberOfSheets() == 0) throw error("SHEET_MISSING", "El libro no contiene hojas");
            var sheet = workbook.getSheetAt(0);
            List<List<CellView>> rows = new ArrayList<>();
            List<FormulaView> formulas = new ArrayList<>();
            int columns = 0;
            int nonEmpty = 0;
            int nonEmptyRows = 0;
            long materializedCells = 0;
            long textCharacters = 0;
            boolean date1904 = isDate1904(workbook);
            Map<Integer, NumericFormatDescriptor> numericFormats = new HashMap<>();
            if (sheet.getLastRowNum() >= MAX_GRID_ROWS) {
                throw error("GRID_ROW_LIMIT", "El libro contiene demasiadas filas, incluidas filas dispersas");
            }
            DataFormatter formatter = new DataFormatter();
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                int rowWidth = lastRelevantColumn(row);
                columns = Math.max(columns, rowWidth);
                if (columns > MAX_COLUMNS) throw error("COLUMN_LIMIT", "El libro supera 256 columnas");
                materializedCells += rowWidth;
                if (materializedCells > MAX_MATERIALIZED_CELLS) {
                    throw error("GRID_CELL_LIMIT", "El libro contiene demasiadas celdas de cuadrícula materializables");
                }
                List<CellView> output = new ArrayList<>();
                boolean rowHasValue = false;
                for (int columnIndex = 0; columnIndex < rowWidth; columnIndex++) {
                    Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    CellRead read = readCell(cell, rowIndex + 1, columnIndex + 1, formulas,
                            date1904, formatter, numericFormats);
                    int valueLength = read.value() == null ? 0 : read.value().length();
                    int formulaLength = read.formula() == null ? 0 : read.formula().length();
                    int rawNumericLength = read.rawNumeric() == null ? 0 : read.rawNumeric().length();
                    if (valueLength > MAX_CELL_CHARACTERS || formulaLength > MAX_CELL_CHARACTERS
                            || rawNumericLength > MAX_CELL_CHARACTERS) {
                        throw errorAt("CELL_TEXT_LIMIT", "La celda supera 32.767 caracteres", rowIndex + 1,
                                columnIndex + 1, null, read.value() == null ? read.formula() : read.value());
                    }
                    textCharacters += (long) valueLength + formulaLength + rawNumericLength;
                    if (textCharacters > MAX_TEXT_CHARACTERS) {
                        throw errorAt("TEXT_LIMIT", "El libro supera el limite total de texto materializado",
                                rowIndex + 1, columnIndex + 1, null, read.value());
                    }
                    if (formulas.size() > MAX_FORMULAS) {
                        throw errorAt("FORMULA_LIMIT", "El libro supera 20.000 formulas", rowIndex + 1,
                                columnIndex + 1, null, read.formula());
                    }
                    if (read.value() != null && !read.value().isBlank()) {
                        rowHasValue = true;
                        nonEmpty++;
                    }
                    if (nonEmpty > MAX_NON_EMPTY_CELLS) throw error("CELL_LIMIT", "El libro supera 250.000 celdas no vacias");
                    output.add(new CellView(read.value(), read.formula(), read.rawNumeric(), read.percentage(), read.numeric(), read.errorCode()));
                }
                if (rowHasValue) {
                    nonEmptyRows++;
                }
                rows.add(output);
            }
            return new ReadResult(safeFileName(fileName), hash, sheet.getSheetName(), rows, formulas,
                    nonEmptyRows, columns, nonEmpty);
        }
    }

    /**
     * Inspects an OOXML container in streaming mode before WorkbookFactory sees
     * it. This prevents a highly-compressed ZIP entry from causing an
     * unbounded expansion in POI; no entry is written to disk or retained.
     */
    private static void preflightXlsxZip(byte[] bytes, String fileName) throws IOException {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") || !startsWith(bytes, OOXML_SIGNATURE)) return;
        long total = 0;
        int entries = 0;
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(new ByteArrayInputStream(bytes))) {
            ZipArchiveEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextZipEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw error("WORKBOOK_LIMIT", "El contenedor XLSX contiene demasiadas entradas");
                }
                long declared = entry.getSize();
                if (declared > MAX_ZIP_ENTRY_BYTES) {
                    throw error("WORKBOOK_LIMIT", "Una entrada XLSX supera el limite de expansion permitido");
                }
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_UNCOMPRESSED_BYTES) {
                        throw error("WORKBOOK_LIMIT", "La expansion descomprimida del XLSX supera el limite permitido");
                    }
                }
            }
        }
    }

    private CellRead readCell(Cell cell, int row, int column, List<FormulaView> formulas,
                              boolean date1904, DataFormatter formatter,
                              Map<Integer, NumericFormatDescriptor> numericFormats) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return new CellRead(null, null, null, false, false);
        if (cell.getCellType() == CellType.ERROR) {
            String received = formulaError(cell.getErrorCellValue());
            return new CellRead(received, null, null, false, false, "CELL_ERROR_VALUE");
        }
        if (cell.getCellType() == CellType.FORMULA) {
            CellType cachedType = cell.getCachedFormulaResultType();
            if (cachedType == CellType._NONE || !hasCachedFormulaResult(cell, cachedType)) {
                return formulaDiagnostic(cell, formulas, "FORMULA_NO_CACHE", null);
            }
            if (cachedType == CellType.ERROR) {
                String received = formulaError(cell.getErrorCellValue());
                return formulaDiagnostic(cell, formulas, "FORMULA_RESULT_ERROR", received);
            }
            NumericFormatDescriptor numericFormat = cachedType == CellType.NUMERIC
                    ? numericFormat(cell, row, column, numericFormats) : GENERAL_FORMAT;
            rejectFictitious1900Date(cell, cachedType, numericFormat, date1904, row, column);
            String value;
            try {
                value = cachedValue(cell, cachedType, numericFormat, date1904, formatter, true);
            } catch (DateTimeException | ArithmeticException | IllegalArgumentException exception) {
                throw errorAt("DATE_INVALID", "El serial de fecha Excel no representa una fecha real", row, column, null,
                        String.valueOf(cell.getNumericCellValue()));
            }
            String formula = cell.getCellFormula();
            formulas.add(new FormulaView(cell.getAddress().formatAsString(), formula, value));
            return new CellRead(value, formula, rawNumeric(cell, cachedType, numericFormat),
                    isPercentageFormat(cell, cachedType, numericFormat), cachedType == CellType.NUMERIC);
        }
        CellType type = cell.getCellType();
        NumericFormatDescriptor numericFormat = type == CellType.NUMERIC
                ? numericFormat(cell, row, column, numericFormats) : GENERAL_FORMAT;
        rejectFictitious1900Date(cell, type, numericFormat, date1904, row, column);
        try {
            return new CellRead(cachedValue(cell, type, numericFormat, date1904, formatter, false), null,
                    rawNumeric(cell, type, numericFormat), isPercentageFormat(cell, type, numericFormat),
                    type == CellType.NUMERIC);
        } catch (DateTimeException | ArithmeticException | IllegalArgumentException exception) {
            throw errorAt("DATE_INVALID", "El serial de fecha Excel no representa una fecha real", row, column, null,
                    cell.getCellType() == CellType.NUMERIC ? String.valueOf(cell.getNumericCellValue()) : null);
        }
    }

    private static CellRead formulaDiagnostic(Cell cell, List<FormulaView> formulas, String code, String cachedValue) {
        String formula = cell.getCellFormula();
        formulas.add(new FormulaView(cell.getAddress().formatAsString(), formula, cachedValue));
        // Preserve the stored error, or display the unevaluated formula when no result exists.
        // Preview revalidates this diagnostic before any operation can consume the cell.
        return new CellRead(cachedValue == null ? "=" + formula : cachedValue, formula, null, false, false, code);
    }

    private static void rejectFictitious1900Date(Cell cell, CellType effectiveType,
            NumericFormatDescriptor numericFormat, boolean date1904, int row, int column) {
        // POI's date helpers may inspect the cell value and invoke a numeric
        // getter.  Never run them for STRING/BOOLEAN/ERROR cells: a text cell
        // can legitimately use a date-looking number format.
        if (effectiveType != CellType.NUMERIC) return;
        if (!numericFormat.dateFormat()) return;
        double serial = cell.getNumericCellValue();
        double maximumExclusive = date1904 ? 2_957_004d : 2_958_466d;
        if (!Double.isFinite(serial) || serial < 0d || serial >= maximumExclusive || !DateUtil.isValidExcelDate(serial)
                || (!date1904 && serial >= 60d && serial < 61d)) {
            throw errorAt("DATE_INVALID", "El serial de fecha Excel no representa una fecha real", row, column, null,
                    String.valueOf(serial));
        }
    }

    private static int lastRelevantColumn(Row row) {
        if (row == null) return 0;
        int last = -1;
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.BLANK) continue;
            if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()) continue;
            last = Math.max(last, cell.getColumnIndex());
        }
        return last + 1;
    }

    private String cachedValue(Cell cell, CellType type, NumericFormatDescriptor numericFormat,
            boolean date1904, DataFormatter formatter, boolean formula) {
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> cell.getBooleanCellValue() ? "1" : "0";
            case NUMERIC -> numericFormat.dateFormat()
                    ? DateUtil.getLocalDateTime(cell.getNumericCellValue(), date1904).toLocalDate().toString()
                    : numericText(cell, formatter, formula);
            default -> null;
        };
    }

    private static String numericText(Cell cell, DataFormatter formatter, boolean formula) {
        String formatted = formula
                ? formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString()).trim()
                : formatter.formatCellValue(cell).trim();
        return SCIENTIFIC_NUMBER.matcher(formatted).matches()
                ? BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString()
                : formatted;
    }

    private static String rawNumeric(Cell cell, CellType type, NumericFormatDescriptor numericFormat) {
        if (type != CellType.NUMERIC || numericFormat.dateFormat()) {
            return null;
        }
        BigDecimal value = BigDecimal.valueOf(cell.getNumericCellValue());
        int multiplier = numericFormat.multiplier(cell.getNumericCellValue());
        if (multiplier != 1) value = value.multiply(BigDecimal.valueOf(multiplier));
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean isPercentageFormat(Cell cell, CellType type,
            NumericFormatDescriptor numericFormat) {
        return type == CellType.NUMERIC && numericFormat.multiplier(cell.getNumericCellValue()) > 1;
    }

    /**
     * Compiles each shared style once. Conditional numeric sections are
     * rejected because selecting them by sign would silently diverge from
     * Excel's value-based rules.
     */
    private static NumericFormatDescriptor numericFormat(Cell cell, int row, int column,
            Map<Integer, NumericFormatDescriptor> formats) {
        int styleIndex = cell.getCellStyle().getIndex();
        NumericFormatDescriptor cached = formats.get(styleIndex);
        if (cached != null) return cached;
        String format = cell.getCellStyle().getDataFormatString();
        if (format == null) format = "";
        if (format.length() > MAX_NUMBER_FORMAT_CHARACTERS) {
            throw new ProductExcelImportException("NUMBER_FORMAT_UNSUPPORTED",
                    "El formato numerico supera 1.024 caracteres", row, column,
                    "numberFormat", format, null);
        }
        if (hasNumericCondition(format)) {
            throw new ProductExcelImportException("NUMBER_FORMAT_UNSUPPORTED",
                    "Los formatos numericos condicionales no son compatibles", row, column,
                    "numberFormat", format, null);
        }
        List<String> sections = formatSections(format);
        int positive = multiplier(sections.getFirst(), row, column, format);
        int negative = multiplier(sections.size() > 1 ? sections.get(1) : sections.getFirst(), row, column, format);
        int zero = multiplier(sections.size() > 2 ? sections.get(2) : sections.getFirst(), row, column, format);
        NumericFormatDescriptor descriptor = new NumericFormatDescriptor(
                DateUtil.isADateFormat(cell.getCellStyle().getDataFormat(), format), positive, negative, zero);
        formats.put(styleIndex, descriptor);
        return descriptor;
    }

    private static int multiplier(String section, int row, int column, String format) {
        int percentageCount = percentageCount(section);
        if (percentageCount <= 2) return switch (percentageCount) {
            case 0 -> 1;
            case 1 -> 100;
            default -> 10_000;
        };
        throw new ProductExcelImportException("NUMBER_FORMAT_UNSUPPORTED",
                "El formato numérico tiene más de dos operadores de porcentaje", row == 0 ? null : row,
                column == 0 ? null : column, "numberFormat", format, null);
    }

    private static List<String> formatSections(String format) {
        if (format == null || format.isEmpty()) return List.of("");
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        int brackets = 0;
        for (int index = 0; index < format.length(); index++) {
            char character = format.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                current.append(character);
                escaped = true;
            } else if (character == '"') {
                current.append(character);
                quoted = !quoted;
            } else if (character == '[' && !quoted) {
                brackets++;
                current.append(character);
            } else if (character == ']' && !quoted && brackets > 0) {
                brackets--;
                current.append(character);
            } else if (character == ';' && !quoted && brackets == 0) {
                sections.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        sections.add(current.toString());
        return sections;
    }

    private static int percentageCount(String section) {
        int count = 0;
        boolean quoted = false;
        boolean escaped = false;
        int brackets = 0;
        for (int index = 0; index < section.length(); index++) {
            char character = section.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == '[' && !quoted) {
                brackets++;
            } else if (character == ']' && !quoted && brackets > 0) {
                brackets--;
            } else if (brackets > 0) {
                continue;
            } else if (!quoted && (character == '_' || character == '*')) {
                if (index + 1 < section.length()) index++;
            } else if (!quoted && character == '%') {
                count++;
            }
        }
        return count;
    }

    private static boolean hasNumericCondition(String format) {
        if (format == null || format.isEmpty()) return false;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < format.length(); index++) {
            char character = format.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == '[' && !quoted) {
                int end = format.indexOf(']', index + 1);
                if (end < 0) return false;
                String bracket = format.substring(index + 1, end).trim();
                if (NUMERIC_FORMAT_CONDITION.matcher(bracket).matches()) return true;
                index = end;
            }
        }
        return false;
    }

    private static String formulaError(byte code) {
        try {
            return FormulaError.forInt(Byte.toUnsignedInt(code)).getString();
        } catch (IllegalArgumentException exception) {
            return "#ERROR(" + Byte.toUnsignedInt(code) + ")";
        }
    }

    private static boolean hasCachedFormulaResult(Cell cell, CellType cachedType) {
        if (cell instanceof XSSFCell xssfCell) {
            if (!xssfCell.getCTCell().isSetV()) return false;
            // An empty cached string is a legitimate result of a formula such
            // as ="". Numeric/boolean/error caches, however, need an actual
            // stored value: POI otherwise exposes an empty <v/> as numeric 0.
            if (cachedType == CellType.STRING) return true;
            String stored = xssfCell.getCTCell().getV();
            if (stored == null || stored.isBlank()) return false;
            if (cachedType == CellType.NUMERIC) {
                try {
                    return Double.isFinite(Double.parseDouble(stored.trim()));
                } catch (NumberFormatException exception) {
                    return false;
                }
            }
            return true;
        }
        return cachedType != CellType._NONE;
    }

    private static boolean hasVbaProject(byte[] bytes) throws IOException {
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            var root = filesystem.getRoot();
            return root.hasEntry("VBA") || root.hasEntry("_VBA_PROJECT_CUR")
                    || root.hasEntry("VBA_PROJECT") || root.hasEntry("VBA_PROJECT_CUR");
        }
    }

    private static boolean isDate1904(Workbook workbook) {
        if (workbook instanceof HSSFWorkbook hssf) return hssf.getWorkbook().isUsing1904DateWindowing();
        if (!(workbook instanceof XSSFWorkbook xssf) || xssf.getCTWorkbook().getWorkbookPr() == null) return false;
        return xssf.getCTWorkbook().getWorkbookPr().getDate1904();
    }

    private static void verifyOfficeSignature(byte[] bytes, String fileName) {
        boolean ooxml = startsWith(bytes, OOXML_SIGNATURE);
        boolean ole2 = startsWith(bytes, OLE2_SIGNATURE);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if ((!name.endsWith(".xlsx") && !name.endsWith(".xls")) || (!ooxml && !ole2)
                || (name.endsWith(".xls") && !ole2)) {
            throw error("FILE_SIGNATURE_INVALID", "La firma del fichero no corresponde a su extensión XLS/XLSX");
        }
    }

    private static void validateExtension(String fileName) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xls") && !name.endsWith(".xlsx")) {
            throw error("FILE_EXTENSION_INVALID", "Solo se admiten ficheros .xls o .xlsx");
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (value[index] != prefix[index]) return false;
        return true;
    }

    private static ProductExcelImportException error(String code, String message) {
        return new ProductExcelImportException(code, message, null, null, null);
    }

    private static ProductExcelImportException error(String code, String message, Throwable cause) {
        return new ProductExcelImportException(code, message, null, null, cause);
    }

    private static ProductExcelImportException errorAt(String code, String message, int row, int column, Throwable cause) {
        return new ProductExcelImportException(code, message, row, column, "cell", null, cause);
    }

    private static ProductExcelImportException errorAt(
            String code, String message, int row, int column, Throwable cause, String receivedValue) {
        return new ProductExcelImportException(code, message, row, column, "cell", truncateErrorValue(receivedValue), cause);
    }

    private static String truncateErrorValue(String value) {
        return value == null ? null : value.substring(0, Math.min(256, value.length()));
    }

    private static Map<String, Object> auditDetails(ReadResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fileName", result.fileName());
        details.put("sha256", result.sha256());
        details.put("sheet", result.sheetName());
        details.put("rows", result.nonEmptyRows());
        details.put("columns", result.columns());
        details.put("nonEmptyCells", result.nonEmptyCells());
        details.put("formulaCount", result.formulas().size());
        details.put("cellErrorCount", result.rows().stream().flatMap(List::stream).filter(cell -> cell.errorCode() != null).count());
        return details;
    }

    private void recordAudit(AuditResult result, Map<String, Object> details) {
        if (audit == null) return;
        Map<String, Object> safeDetails = new LinkedHashMap<>(details);
        if (organization != null) {
            try {
                safeDetails.put("storeId", organization.currentStore().getId().toString());
                safeDetails.put("companyId", organization.currentCompany().getId().toString());
            } catch (RuntimeException ignored) {
                // Read errors must retain their structured cause when no session is active.
            }
        }
        try {
            audit.record("PRODUCT_EXCEL_IMPORT_READ", result, safeDetails);
        } catch (RuntimeException ignored) {
            // Audit is observability only; it must never change read semantics.
        }
    }

    private void recordFailure(String fileName, String hash, String code) {
        recordAudit(AuditResult.FALLO,
                Map.of("fileName", safeFileName(fileName), "sha256", hash == null ? "" : hash, "code", code));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static String safeFileName(String name) {
        String value = name == null ? "import.xlsx" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return value.isBlank() ? "import.xlsx" : value.substring(0, Math.min(255, value.length()));
    }

    private record NumericFormatDescriptor(
            boolean dateFormat,
            int positiveMultiplier,
            int negativeMultiplier,
            int zeroMultiplier) {

        int multiplier(double value) {
            return value < 0d ? negativeMultiplier : value == 0d ? zeroMultiplier : positiveMultiplier;
        }
    }

    private record CellRead(String value, String formula, String rawNumeric, boolean percentage, boolean numeric, String errorCode) {
        private CellRead(String value, String formula, String rawNumeric, boolean percentage, boolean numeric) {
            this(value, formula, rawNumeric, percentage, numeric, null);
        }
    }

    public record CellView(String value, String formula, String rawNumeric, boolean percentage, boolean numeric, String errorCode) {
        public CellView(String value, String formula, String rawNumeric, boolean percentage, boolean numeric) {
            this(value, formula, rawNumeric, percentage, numeric, null);
        }
        public CellView(String value, String formula) { this(value, formula, null, false); }
        public CellView(String value, String formula, String rawNumeric, boolean percentage) {
            this(value, formula, rawNumeric, percentage, rawNumeric != null);
        }
    }

    public record FormulaView(String cell, String formula, String calculatedValue) { }

    public record ReadResult(String fileName, String sha256, String sheetName, List<List<CellView>> rows,
                             List<FormulaView> formulas, int nonEmptyRows, int columns, int nonEmptyCells) { }

    public static final class ProductExcelImportException extends RuntimeException {
        private final String code;
        private final Integer row;
        private final Integer column;
        private final String attribute;
        private final String receivedValue;

        public ProductExcelImportException(String code, String message, Integer row, Integer column, Throwable cause) {
            this(code, message, row, column, null, null, cause);
        }

        public ProductExcelImportException(String code, String message, Integer row, Integer column,
                String attribute, String receivedValue, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.row = row;
            this.column = column;
            this.attribute = attribute;
            this.receivedValue = truncateErrorValue(receivedValue);
        }

        public String code() { return code; }
        public Integer row() { return row; }
        public Integer column() { return column; }
        public String attribute() { return attribute; }
        public String receivedValue() { return receivedValue; }
    }
}
