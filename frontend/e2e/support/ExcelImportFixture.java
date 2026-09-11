import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Source-launcher fixture generator for Excel import E2E tests. */
public class ExcelImportFixture {
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Uso: java ExcelImportFixture.java <xls|xlsx> <outputPath> <code> [rows]");
        }
        String format = args[0].toLowerCase(Locale.ROOT);
        if (!format.equals("xls") && !format.equals("xlsx")) {
            throw new IllegalArgumentException("El formato debe ser xls o xlsx");
        }
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        int count = args.length == 4 ? Integer.parseInt(args[3]) : 1;
        if (count < 1 || count > 5000) throw new IllegalArgumentException("Entre 1 y 5000 filas");
        Files.createDirectories(output.getParent());
        try (Workbook workbook = format.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook();
                OutputStream stream = Files.newOutputStream(output)) {
            Sheet sheet = workbook.createSheet("Productos");
            String[] headers = {"code", "name", "purchasePrice", "salePrice", "quantity"};
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            if (count > 1) header.createCell(26).setCellValue("Auxiliar AA");
            for (int index = 1; index <= count; index++) {
                Row product = sheet.createRow(index);
                product.createCell(0).setCellValue(args[2]);
                product.createCell(1).setCellValue("Fixture " + args[2]);
                product.createCell(2).setCellValue(10.00d);
                product.createCell(3).setCellValue(20.00d);
                // Stock must ignore these differing quantities when merging duplicates.
                product.createCell(4).setCellValue(index);
                if (count > 1) product.createCell(26).setCellValue("Auxiliar " + index);
            }
            workbook.write(stream);
        }
    }
}
