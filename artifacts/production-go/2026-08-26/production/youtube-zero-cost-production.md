# Zero-cost YouTube production execution

- Date: 2026-08-26 (Asia/Tokyo)
- Implementation commit: `b7c1022b1a99cd67a51690a8d4b24b7bb4ac08d6`
- Required fixed monthly cost: `$0`
- Railway plan: Free only
- Render plan: Free only
- Paid plan, card, and subscription actions: not used

## Railway Free

- Service: `rinbam-youtube-resolver`
- Deployment: `da76813b-e4b7-454b-85f6-89765df118f0`
- Status: Active
- Public host: `rinbam-youtube-resolver-production.up.railway.app`
- Public health: HTTP 200 in 0.309 seconds; automatic PO provider reported
  `configured=true`, `reachable=true`.
- Public YouTube resolve for `jNQXAC9IVRw`: HTTP 200 in 47.304 seconds,
  `ok=true`, `video/mp4`, using the server-download `/files` fallback.
- Public cached-file range: HTTP 206 and exactly 1,024 bytes,
  `Content-Range: bytes 0-1023/680652`, `Content-Length: 1024`, and
  `Accept-Ranges: bytes`.
- A second public video, `M7lc1UVf-VE`, independently returned HTTP 200 in
  48.324 seconds with `video/mp4`, also through `/files`; its range returned
  HTTP 206 with exactly 256 bytes, `Content-Range: bytes 0-255/51434099`, and
  `Content-Length: 256`. This reduces the chance that the GO decision depends
  on one previously cached video-bound token.
- Deploy logs showed the health 200, two resolve 200 records, and the expected
  `/files` 206 records. No generated PO-token material was visible.

The preceding docs-only deployment `329666b6-e29b-4c68-bcf0-0ec56ad43d27`
exposed a production-only regression: server-downloaded `/files` ignored Range
and returned HTTP 200 with the entire 43,776,160-byte file for a 256-byte
request. That observation invalidated the earlier production GO until commit
`b7c1022b` added bounded single-range parsing and streaming. The active
deployment above proves the corrected code on the same server-download path;
the GO decision no longer relies only on the direct `/proxy` path.

This deployment closes the regression where a newly allocated Railway egress
received YouTube `LOGIN_REQUIRED`/bot challenges after the earlier raw
Innertube success. The current image uses automatic, video-bound PO tokens and
Deno EJS solving instead of depending on a favorable egress IP.

## Render Free app-facing path

- Service: `rinbam-media-resolver`
- Deployment: `dep-da76lj142hec73afne3g`
- Status: Deploy succeeded / Live
- Source revision reported by public health:
  `b33a6c76806fc8b87efd95b211be02f82fedbc26`
- Public health recheck: HTTP 200 in 0.393 seconds; YouTube delegate configured.
- Public Render-to-Railway YouTube resolve: HTTP 200 in 1.969 seconds,
  `ok=true`, `video/mp4`.
- Returned `/files` host: Railway Free.
- Public cached-file range through the app-facing path: HTTP 206 and exactly
  1,024 bytes, `Content-Range: bytes 0-1023/680652`,
  `Content-Length: 1024`, and `Accept-Ranges: bytes`.

## Decision

`ZERO_COST_PRODUCTION_YOUTUBE_PATH: GO`

`PUBLIC_LAUNCH: NO_GO` remains because App Store Connect still reports iOS
1.0.19 as `審査待ち`. Production infrastructure GO does not upgrade the Apple
review/publication gate.
