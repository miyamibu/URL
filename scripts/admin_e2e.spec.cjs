/*
 * Optional admin E2E contract.
 * The runner injects an absolute @playwright/test module path so this file can
 * remain under scripts/ without changing web/admin or adding dependencies.
 */

const { test, expect } = require(process.env.RINBAM_PLAYWRIGHT_TEST_MODULE);

const baseURL = process.env.RINBAM_ADMIN_E2E_BASE_URL;
const storageState = process.env.RINBAM_ADMIN_E2E_STORAGE_STATE;
const protectedPath = process.env.RINBAM_ADMIN_E2E_PROTECTED_PATH || "/admin";
const protectedApiPath = process.env.RINBAM_ADMIN_E2E_PROTECTED_API_PATH || "/api/admin/audit";
const sensitivePath = process.env.RINBAM_ADMIN_E2E_SENSITIVE_PATH || protectedPath;
const roleSelector = process.env.RINBAM_ADMIN_E2E_ROLE_SELECTOR;
const expectedRole = process.env.RINBAM_ADMIN_E2E_EXPECTED_ROLE;
const stepUpSelector = process.env.RINBAM_ADMIN_E2E_STEP_UP_SELECTOR;
const submitSelector = process.env.RINBAM_ADMIN_E2E_SUBMIT_SELECTOR;
const errorSelector = process.env.RINBAM_ADMIN_E2E_ERROR_SELECTOR;
const reasonSelector = process.env.RINBAM_ADMIN_E2E_REASON_SELECTOR || '[data-testid="admin-operation-reason"]';
const mutationRoute = process.env.RINBAM_ADMIN_E2E_MUTATION_ROUTE || '**/api/admin/promo-codes/send';
const a11yScope = process.env.RINBAM_ADMIN_E2E_A11Y_SCOPE || "body";
const errorStatus = Number(process.env.RINBAM_ADMIN_E2E_ERROR_STATUS || "500");

test.use({ baseURL, storageState });

function pathUrl(path) {
  return new URL(path, baseURL).toString();
}

test.describe("りんばむ admin release E2E contract", () => {
  test("unauthenticated admin API is rejected", async ({ browser }) => {
    const context = await browser.newContext({ baseURL, storageState: undefined });
    const response = await context.request.get(protectedApiPath, { maxRedirects: 0 });
    expect([401, 403, 302, 303, 307, 308]).toContain(response.status());
    await context.close();
  });

  test("authenticated role and step-up surface is present", async ({ page }) => {
    const response = await page.goto(pathUrl(protectedPath), { waitUntil: "domcontentloaded" });
    expect(response).not.toBeNull();
    expect(response.status()).toBeLessThan(400);
    await expect(page.locator(roleSelector)).toBeVisible();
    await expect(page.locator(roleSelector)).toContainText(new RegExp(expectedRole.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"));
    await expect(page.locator(stepUpSelector)).toBeVisible();
  });

  test("server error and duplicate submit are surfaced without an external mutation", async ({ page }) => {
    let requestCount = 0;
    await page.route(mutationRoute, async (route) => {
      requestCount += 1;
      await route.fulfill({
        status: errorStatus,
        contentType: "application/json",
        body: JSON.stringify({ ok: false, error: "fixture-error" }),
      });
    });
    await page.goto(pathUrl(sensitivePath), { waitUntil: "domcontentloaded" });
    const submit = page.locator(submitSelector);
    await expect(submit).toBeVisible();
    const reason = page.locator(reasonSelector);
    await expect(reason).toBeVisible();
    await reason.fill("Playwright fixture operation reason");
    await Promise.all([
      submit.click(),
      submit.click().catch(() => undefined),
    ]);
    await expect(page.locator(errorSelector)).toBeVisible();
    expect(requestCount).toBe(1);
  });

  test("basic accessibility contract has no unnamed visible controls", async ({ page }) => {
    await page.goto(pathUrl(protectedPath), { waitUntil: "domcontentloaded" });
    const issues = await page.locator(a11yScope).evaluate((root) => {
      const problems = [];
      const visible = (element) => {
        const style = window.getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.visibility !== "hidden" && style.display !== "none" && rect.width > 0 && rect.height > 0;
      };
      const nameOf = (element) => {
        const labelledBy = element.getAttribute("aria-labelledby");
        const labelledText = labelledBy ? labelledBy.split(/\s+/).map((id) => document.getElementById(id)?.textContent || "").join(" ") : "";
        const labelText = element.labels ? Array.from(element.labels).map((label) => label.textContent || "").join(" ") : "";
        return (element.getAttribute("aria-label") || labelledText || labelText || element.getAttribute("title") || element.textContent || element.getAttribute("placeholder") || element.getAttribute("value") || "").trim();
      };
      root.querySelectorAll("button, a, input, select, textarea").forEach((element) => {
        if (!visible(element) || element.getAttribute("aria-hidden") === "true") return;
        if (!nameOf(element)) problems.push(`${element.tagName.toLowerCase()} has no accessible name`);
      });
      root.querySelectorAll("img").forEach((element) => {
        if (visible(element) && element.getAttribute("aria-hidden") !== "true" && element.getAttribute("alt") === null) problems.push("visible img has no alt");
      });
      return problems;
    });
    expect(issues).toEqual([]);
  });
});
