import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { capabilitiesForRole } from "./auth-policy.ts";

const routeContracts = [
  {
    file: "../app/api/admin/audit/route.ts",
    guards: ["const admin = await requireAdmin(request);", "assertCapability(admin, \"audit.read\");"],
  },
  {
    file: "../app/api/admin/moderation/route.ts",
    guards: [
      "const admin = await requireAdmin(request);",
      "assertCapability(admin, \"moderation.read\");",
      "assertCapability(admin, \"moderation.manage\");",
      "assertHighRisk(admin);",
    ],
  },
  {
    file: "../app/api/admin/support/route.ts",
    guards: [
      "const admin = await requireAdmin(request);",
      "assertCapability(admin, \"support.read\");",
      "assertCapability(admin, \"support.write\");",
      "assertHighRisk(admin);",
    ],
  },
  {
    file: "../app/api/admin/promo-codes/route.ts",
    guards: ["const admin = await requireAdmin(request);", "assertCapability(admin, \"promos.read\");"],
  },
  {
    file: "../app/api/admin/promo-codes/send/route.ts",
    guards: ["const admin = await requireAdmin(request);", "assertWritable(admin);", "assertHighRisk(admin);"],
  },
  {
    file: "../app/api/admin/promo-codes/[id]/revoke/route.ts",
    guards: ["const admin = await requireAdmin(request);", "assertWritable(admin);", "assertHighRisk(admin);"],
  },
  {
    file: "../app/api/admin/users/route.ts",
    guards: [
      "const admin = await requireAdmin(request);",
      "assertCanReadUsers(admin);",
      "assertCanSearchUsers(admin);",
    ],
  },
  {
    file: "../app/api/admin/users/[id]/route.ts",
    guards: [
      "const admin = await requireAdmin(request);",
      "assertCanReadUsers(admin);",
      "assertCanManageUsers(admin);",
      "assertHighRisk(admin);",
    ],
  },
];

for (const contract of routeContracts) {
  test(`${contract.file} keeps authentication and authorization guards`, async () => {
    const source = await readFile(new URL(contract.file, import.meta.url), "utf8");
    let previousIndex = -1;
    for (const guard of contract.guards) {
      const index = source.indexOf(guard);
      assert.notEqual(index, -1, `${contract.file} is missing ${guard}`);
      assert.ok(index > previousIndex, `${contract.file} guard order changed around ${guard}`);
      previousIndex = index;
    }
  });
}

test("roles without high-risk capabilities remain denied by the route capability policy", () => {
  const deniedRolesByCapability = {
    "promos.issue": ["readonly", "moderator"],
    "support.write": ["readonly", "billing"],
    "moderation.manage": ["readonly", "billing"],
    "users.manage": ["readonly", "billing", "moderator"],
  };

  for (const [capability, roles] of Object.entries(deniedRolesByCapability)) {
    for (const role of roles) {
      assert.equal(
        capabilitiesForRole(role)?.includes(capability),
        false,
        `${role} must not access ${capability}`,
      );
    }
  }
});
