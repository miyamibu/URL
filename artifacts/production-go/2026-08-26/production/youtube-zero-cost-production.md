# Zero-cost YouTube production execution

- Date: 2026-08-26 (Asia/Tokyo)
- Implementation commit: `b33a6c76806fc8b87efd95b211be02f82fedbc26`
- Required fixed monthly cost: `$0`
- Railway plan: Free only
- Render plan: Free only
- Paid plan, card, and subscription actions: not used

## Railway Free

- Service: `rinbam-youtube-resolver`
- Deployment: `50be593b-5765-4e63-ba33-24ce95c494b8`
- Status: Active
- Public host: `rinbam-youtube-resolver-production.up.railway.app`
- Public health: HTTP 200 in 0.776 seconds; automatic PO provider reported
  `configured=true`, `reachable=true`.
- Public YouTube resolve for `jNQXAC9IVRw`: HTTP 200 in 0.705 seconds,
  `ok=true`, `video/mp4`.
- Public proxy range: HTTP 206, 1,024 bytes,
  `Content-Range: bytes 0-1023/629172`.
- A second public video, `M7lc1UVf-VE`, independently returned HTTP 200 in
  0.825 seconds with `video/mp4`; its proxy returned HTTP 206 with 256 bytes of
  43,776,160. This reduces the chance that the GO decision depends on one
  previously cached video-bound token.
- Deploy logs showed the health 200, resolve 200, and proxy 206 requests and no
  generated PO-token material.

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
- Public health: HTTP 200 in 0.222 seconds; YouTube delegate configured.
- Public Render-to-Railway YouTube resolve: HTTP 200 in 1.289 seconds,
  `ok=true`, `video/mp4`.
- Returned proxy host: Railway Free.
- Public proxy range: HTTP 206, 1,024 bytes,
  `Content-Range: bytes 0-1023/629172`.

## Decision

`ZERO_COST_PRODUCTION_YOUTUBE_PATH: GO`

`PUBLIC_LAUNCH: NO_GO` remains because App Store Connect still reports iOS
1.0.19 as `審査待ち`. Production infrastructure GO does not upgrade the Apple
review/publication gate.
