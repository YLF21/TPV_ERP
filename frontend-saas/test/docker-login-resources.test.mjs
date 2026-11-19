import assert from "node:assert/strict";
import { readFile, stat } from "node:fs/promises";
import test from "node:test";

test("the SaaS image includes the shared resources required by portal login", async () => {
  const dockerfile = await readFile(new URL("../Dockerfile", import.meta.url), "utf8");
  const buildAt = dockerfile.indexOf("RUN npm --prefix frontend-saas run build");
  assert.ok(buildAt >= 0);
  for (const resource of ["frontend-product/src/portalAccess.ts", "frontend/branding/espos-wordmark.png"]) {
    assert.ok((await stat(new URL(`../../${resource}`, import.meta.url))).isFile());
    const copyAt = dockerfile.indexOf(`COPY ${resource} ./${resource}`);
    assert.ok(copyAt >= 0 && copyAt < buildAt, `${resource} must be copied before building SaaS`);
  }
});
