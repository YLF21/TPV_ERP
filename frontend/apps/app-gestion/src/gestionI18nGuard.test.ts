import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { describe, expect, it } from "vitest";
import { messages } from "@tpverp/app-common";

const gestionSourceDirectory = fileURLToPath(new URL(".", import.meta.url));
const sharedComponentsDirectory = fileURLToPath(
  new URL("../../../packages/app-common/src/components/", import.meta.url)
);

const sharedManagementComponents = [
  "CustomerDocumentsDialog.tsx",
  "AppFrame.tsx",
  "LoginScreen.tsx",
  "PromotionListScreen.tsx",
  "SalesReportScreen.tsx",
  "SharedExcelImportDialog.tsx",
  "StockScreen.tsx",
  "WarehouseDocumentDialog.tsx",
  "WarehouseScreen.tsx"
];
const technicalVisibleLiterals = new Set([
  "v",
  "x",
  "15 s",
  "30 s",
  "60 s",
  "4 h",
  "8 h",
  "24 h",
  "48 h",
  "Ctrl K"
]);
const translationKeySetters = new Set(["setErrorKey", "setExportErrorKey"]);

function auditedFiles() {
  const gestionFiles = readdirSync(gestionSourceDirectory)
    .filter((name) => name.endsWith(".tsx") && !name.includes(".test."))
    .map((name) => join(gestionSourceDirectory, name));
  return [
    ...gestionFiles,
    ...sharedManagementComponents.map((name) => join(sharedComponentsDirectory, name))
  ];
}

function visibleLiterals(source: ts.SourceFile) {
  const fileName = source.fileName;
  const findings: string[] = [];
  const visibleAttributes = new Set(["aria-label", "title", "placeholder", "alt"]);
  const stateSetters = new Set(["setStatus", "setError", "setFormError", "setConfirmationError"]);

  function location(node: ts.Node) {
    return source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
  }

  function visit(node: ts.Node) {
    if (ts.isJsxText(node)) {
      const text = node.getText(source).replace(/\s+/g, " ").trim();
      if (/[\p{L}\p{N}]/u.test(text) && !technicalVisibleLiterals.has(text)) {
        findings.push(`${fileName}:${location(node)} JSX: ${text}`);
      }
    }

    if (
      ts.isJsxAttribute(node)
      && visibleAttributes.has(node.name.getText(source))
      && node.initializer
      && ts.isStringLiteral(node.initializer)
      && /[\p{L}\p{N}]/u.test(node.initializer.text)
    ) {
      findings.push(`${fileName}:${location(node)} ${node.name.getText(source)}: ${node.initializer.text}`);
    }

    if (
      ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && stateSetters.has(node.expression.text)
      && node.arguments.length > 0
      && ts.isStringLiteral(node.arguments[0])
      && node.arguments[0].text.trim()
    ) {
      findings.push(`${fileName}:${location(node)} ${node.expression.text}: ${node.arguments[0].text}`);
    }

    ts.forEachChild(node, visit);
  }

  visit(source);
  return findings;
}

function literalTranslationKeys(source: ts.SourceFile) {
  const keys: string[] = [];

  function visit(node: ts.Node) {
    if (
      ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && (node.expression.text === "t" || translationKeySetters.has(node.expression.text))
      && node.arguments.length > 0
      && (
        ts.isStringLiteral(node.arguments[0])
        || ts.isNoSubstitutionTemplateLiteral(node.arguments[0])
      )
      && node.arguments[0].text.trim()
    ) {
      keys.push(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  }

  visit(source);
  return keys;
}

function sourceFile(fileName: string) {
  return ts.createSourceFile(fileName, readFileSync(fileName, "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
}

function missingTranslations(keys: string[]) {
  return (["es", "en", "zh"] as const).flatMap((locale) => (
    keys
      .filter((key) => messages[locale][key] === undefined)
      .map((key) => `${locale}:${key}`)
  ));
}

describe("APP GESTIÓN i18n guard", () => {
  it("does not allow new visible literals outside the translation catalog", () => {
    expect(auditedFiles().flatMap((fileName) => visibleLiterals(sourceFile(fileName)))).toEqual([]);
  });

  it("resolves every literal translation key in Spanish, English and Chinese", () => {
    const keys = [...new Set(auditedFiles().flatMap((fileName) => literalTranslationKeys(sourceFile(fileName))))];
    expect(missingTranslations(keys)).toEqual([]);
  });

  it("checks translation keys stored in state against every locale", () => {
    const sample = ts.createSourceFile("sample.tsx", 'setErrorKey("gestion.serverSetup.offline"); setErrorKey("gestion.serverSetup.missingKey"); setError("Visible literal")', ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    expect(missingTranslations(literalTranslationKeys(sample))).toEqual([
      "es:gestion.serverSetup.missingKey",
      "en:gestion.serverSetup.missingKey",
      "zh:gestion.serverSetup.missingKey"
    ]);
    expect(visibleLiterals(sample)).toEqual(["sample.tsx:1 setError: Visible literal"]);
  });
});
