import assert from "node:assert/strict";
import { test } from "node:test";
import { resolvePortalUrl } from "../src/portalAccess.ts";

test("local portal entries preserve the actual loopback hostname", () => {
  for (const host of ["localhost", "127.0.0.1", "[::1]"]) {
    for (const entry of ["venta", "#/login"]) {
      for (const configured of [undefined, "", "   "]) {
        assert.equal(resolvePortalUrl(configured, `http://${host}:4190`, entry), `http://${host}:${entry === "venta" ? "5173/" : "5175/#/login"}`);
      }
    }
  }
});

test("unconfigured published sites never send customers to a local machine", () => {
  for (const origin of [undefined, "https://espos.example", "http://192.168.1.2:4190", "http://localhost.example", "http://127.0.0.1.example", "http://127.1:4190", "http://2130706433:4190", "http://[::2]:4190", "http://user@localhost:4190", "file:///", "http://localhost:4190/path"]) {
    assert.equal(resolvePortalUrl(undefined, origin, "venta"), undefined, origin);
  }
});

test("configured public HTTPS and same-origin paths are used without marketing data", () => {
  for (const value of ["https://venta.example/", "https://saas.example/#/login", "/venta/", "/saas/#/login", "/portal/?mode=client"]) {
    assert.equal(resolvePortalUrl(value, "http://127.0.0.1:4190", "venta"), value);
  }
  assert.equal(resolvePortalUrl(" https://portal.example ", undefined, "venta"), "https://portal.example/");
});

test("explicit local HTTP destinations are permitted for development", () => {
  for (const host of ["localhost", "127.0.0.1", "[::1]"]) {
    assert.equal(resolvePortalUrl(`http://${host}:5173/`, "https://public.example", "venta"), `http://${host}:5173/`);
  }
});

test("invalid configured values never fall back to an apparently working local link", () => {
  for (const value of ["javascript:alert(1)", "data:text/html,login", "ftp://portal.example", "//other.example/login", "\\\\other.example\\login", "/\\other.example/login", "https://user:password@portal.example", "https://user@portal.example", "https://portal.example/a b", "https://portal.example/\nlogin", "https://portal.example\\login", "http://portal.example", "http://localhost.example", "http://127.0.0.1.example", "http://127.1/login", "http://2130706433/login", "http://0177.0.0.1/login", "http://0x7f000001/login", "http://[::2]/login", "http://user@localhost/login", "login", "#/login", "https:portal.example", "https://", "https://portal.example:99999"]) {
    assert.equal(resolvePortalUrl(value, "http://127.0.0.1:4190", "venta"), undefined, value);
  }
});
