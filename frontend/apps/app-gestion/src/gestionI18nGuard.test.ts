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
  "AppFrame.tsx",
  "LoginScreen.tsx",
  "PromotionListScreen.tsx",
  "SalesReportScreen.tsx",
  "SharedExcelImportDialog.tsx",
  "StockScreen.tsx",
  "WarehouseDocumentDialog.tsx",
  "WarehouseScreen.tsx"
];
const technicalVisibleLiterals = new Set(["v", "x"]);

function auditedFiles() {
  const gestionFiles = readdirSync(gestionSourceDirectory)
    .filter((name) => name.endsWith(".tsx") && !name.includes(".test."))
    .map((name) => join(gestionSourceDirectory, name));
  return [
    ...gestionFiles,
    ...sharedManagementComponents.map((name) => join(sharedComponentsDirectory, name))
  ];
}

function visibleLiterals(fileName: string) {
  const source = ts.createSourceFile(
    fileName,
    readFileSync(fileName, "utf8"),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX
  );
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

function literalTranslationKeys(fileName: string) {
  const source = ts.createSourceFile(
    fileName,
    readFileSync(fileName, "utf8"),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX
  );
  const keys: string[] = [];

  function visit(node: ts.Node) {
    if (
      ts.isCallExpression(node)
      && ts.isIdentifier(node.expression)
      && node.expression.text === "t"
      && node.arguments.length > 0
      && (
        ts.isStringLiteral(node.arguments[0])
        || ts.isNoSubstitutionTemplateLiteral(node.arguments[0])
      )
    ) {
      keys.push(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  }

  visit(source);
  return keys;
}

describe("APP GESTIÓN i18n guard", () => {
  it("does not allow new visible literals outside the translation catalog", () => {
    expect(auditedFiles().flatMap(visibleLiterals)).toEqual([]);
  });

  it("resolves every literal translation key in Spanish, English and Chinese", () => {
    const keys = [...new Set(auditedFiles().flatMap(literalTranslationKeys))];
    const missing = (["es", "en", "zh"] as const).flatMap((locale) => (
      keys
        .filter((key) => messages[locale][key] === undefined)
        .map((key) => `${locale}:${key}`)
    ));
    expect(missing).toEqual([]);
  });
});
