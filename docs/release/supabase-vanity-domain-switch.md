# Supabase vanity domain switch

Goal: move the user-visible Supabase Auth/Function host from `xocumgxbylmpoobfqows.supabase.co` to `linbam.supabase.co` without breaking production sign-in, shared tags, password reset, or contact support.

## Current state

- Linked Supabase project ref: `xocumgxbylmpoobfqows`
- Desired vanity subdomain: `linbam`
- Desired production host: `https://linbam.supabase.co`
- Current blocker: Supabase returns `This feature requires the Pro, Team, or Enterprise organization plan.`
- Do not ship mobile or web config pointing at `linbam.supabase.co` until Supabase has activated that vanity subdomain and HTTPS checks pass.

## Pre-activation checks

Run:

```sh
scripts/verify_supabase_vanity_domain.sh xocumgxbylmpoobfqows linbam
```

Expected before continuing:

- `linbam` availability can be checked without a plan error.
- Supabase vanity status can be read.
- `https://linbam.supabase.co` becomes reachable after activation.

## OAuth preparation

Before activation, add the new callback URL in every external OAuth provider used by Supabase Auth:

```text
https://linbam.supabase.co/auth/v1/callback
```

Keep the old callback URL during the transition:

```text
https://xocumgxbylmpoobfqows.supabase.co/auth/v1/callback
```

The screenshot showing Google login text is controlled by the Supabase Auth host. It will not change to `linbam.supabase.co` until Supabase vanity subdomain activation succeeds.

## Activate

After the Supabase organization is on Pro, Team, or Enterprise and OAuth callbacks are prepared:

```sh
SUPABASE_TELEMETRY=false supabase vanity-subdomains check-availability \
  --project-ref xocumgxbylmpoobfqows \
  --desired-subdomain linbam \
  --output json

SUPABASE_TELEMETRY=false supabase vanity-subdomains activate \
  --project-ref xocumgxbylmpoobfqows \
  --desired-subdomain linbam
```

Then verify:

```sh
curl -fsS https://linbam.supabase.co/auth/v1/health
curl -fsS -X POST https://linbam.supabase.co/functions/v1/contact-support \
  -H 'Content-Type: application/json' \
  --data '{"email":"verify@example.com","name":"Release verification","message":"Vanity domain contact support verification","platform":"ops","appVersion":"domain-switch","buildType":"verification","isSignedIn":false}'
```

The contact support check should return an accepted response such as HTTP `202`.

## App and web config switch

Only after activation and health checks pass, update these runtime values:

Android release build inputs:

```properties
release.supabase.url=https://linbam.supabase.co
contact.support.endpoint.url=https://linbam.supabase.co/functions/v1/contact-support
```

iOS archive config:

```xcconfig
URLSAVER_SUPABASE_URL = https:/$()/linbam.supabase.co
URLSAVER_CONTACT_SUPPORT_ENDPOINT_URL = https:/$()/linbam.supabase.co/functions/v1/contact-support
```

Static reset-password page:

```text
web/invite-link/auth/reset-password/index.html
```

Change `SUPABASE_URL` to:

```js
const SUPABASE_URL = "https://linbam.supabase.co";
```

Web admin environment:

```text
NEXT_PUBLIC_SUPABASE_URL=https://linbam.supabase.co
```

## Store impact

After switching mobile build inputs:

1. Build and test Android release.
2. Build and test iOS archive.
3. Confirm Google login screen displays `linbam.supabase.co`.
4. Confirm shared tag sign-in/callback works.
5. Confirm contact support returns accepted from the app.
6. Submit new Android and iOS builds to the stores.

