import assert from "node:assert/strict";
import test from "node:test";

import {
  capabilitiesForRole,
  isStepUpCurrent,
  parseAdminAssurance,
  stepUpExpiresAt,
} from "./auth-policy.ts";

test("Supabase mfa/totp AMR is accepted as a fresh AAL2 step-up", () => {
  const assurance = parseAdminAssurance({
    aal: "aal2",
    amr: [{ method: "password", timestamp: 100 }, { method: "mfa/totp", timestamp: 1_000 }],
  });
  assert.deepEqual(assurance, {
    aal: "aal2",
    acr: null,
    methods: ["password", "mfa/totp"],
    verifiedAt: 1_000,
  });
  assert.equal(isStepUpCurrent(assurance, 1_899, 900, 60), true);
  assert.equal(isStepUpCurrent(assurance, 1_901, 900, 60), false);
});

test("legacy totp is accepted while unrelated MFA cannot satisfy the TOTP policy", () => {
  const legacy = parseAdminAssurance({ aal: "aal2", amr: [{ method: "totp", timestamp: 500 }] });
  const unrelated = parseAdminAssurance({ aal: "aal2", amr: [{ method: "mfa/phone", timestamp: 500 }] });
  assert.equal(legacy?.verifiedAt, 500);
  assert.equal(unrelated?.verifiedAt, null);
  assert.equal(isStepUpCurrent(unrelated, 500, 900, 60), false);
});

test("malformed AMR and weak assurance context fail closed", () => {
  assert.equal(parseAdminAssurance({ aal: "aal2", amr: "mfa/totp" }), null);
  assert.equal(parseAdminAssurance({ aal: "aal2", amr: [{ method: "mfa/totp" }] }), null);
  const weak = parseAdminAssurance({ aal: "aal2", acr: "aal1", amr: [{ method: "mfa/totp", timestamp: 500 }] });
  assert.equal(stepUpExpiresAt(weak, 900), null);
});

test("role capabilities keep read-only access meaningful without write permission", () => {
  const readonly = capabilitiesForRole("readonly");
  assert.deepEqual(readonly, ["promos.read", "support.read", "moderation.read", "audit.read"]);
  assert.equal(readonly?.includes("promos.issue"), false);
  assert.equal(readonly?.includes("users.read"), false);
  assert.equal(capabilitiesForRole("owner")?.includes("users.read"), true);
  assert.equal(capabilitiesForRole("owner")?.includes("users.manage"), true);
  assert.equal(capabilitiesForRole("billing")?.includes("users.read"), false);
  assert.equal(capabilitiesForRole("billing")?.includes("users.manage"), false);
  assert.equal(capabilitiesForRole("unknown"), null);
});
