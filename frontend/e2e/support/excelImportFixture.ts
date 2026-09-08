import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const reportName = 'TEST-com.tpverp.backend.excel.ProductExcelImportReadServiceTest.xml';

function workspaceRoot(): string {
  return resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
}

function reportPath(): string {
  const root = workspaceRoot();
  return join(root, 'backend', 'target', 'surefire-reports', reportName);
}

function javaClassPath(): string {
  const report = reportPath();
  if (!existsSync(report)) throw new Error(`No existe el informe Surefire requerido: ${report}`);
  const xml = readFileSync(report, 'utf8');
  const match = xml.match(/<property\s+name="java\.class\.path"\s+value="([\s\S]*?)"\s*\/>/);
  if (!match) throw new Error(`El informe Surefire no contiene java.class.path: ${report}`);
  return match[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}

/** Generates a real POI workbook using Java's source launcher, then returns its bytes. */
export async function excelImportFixture(format: 'xls' | 'xlsx', code: string, rows = 1): Promise<Buffer> {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), 'tpv-excel-fixture-'));
  const outputPath = join(temporaryDirectory, `import.${format}`);
  const sourcePath = join(workspaceRoot(), 'frontend', 'e2e', 'support', 'ExcelImportFixture.java');
  try {
    const result = spawnSync('java', ['--class-path', javaClassPath(), sourcePath, format, outputPath, code, String(rows)], {
      encoding: 'utf8',
      windowsHide: true,
    });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`No se pudo generar el fixture Excel: ${result.stderr || result.stdout}`);
    return readFileSync(outputPath);
  } finally {
    const cleanupTarget = resolve(temporaryDirectory);
    if (dirname(cleanupTarget) !== resolve(tmpdir()) || !basename(cleanupTarget).startsWith('tpv-excel-fixture-')) {
      throw new Error(`Directorio temporal fuera del ámbito del fixture: ${cleanupTarget}`);
    }
    rmSync(cleanupTarget, { recursive: true, force: true });
  }
}

export const generateExcelImportFixture = excelImportFixture;
