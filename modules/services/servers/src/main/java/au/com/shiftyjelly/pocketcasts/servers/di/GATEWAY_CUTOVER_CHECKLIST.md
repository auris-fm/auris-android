# Gateway cutover — staging checklist

Use this checklist when validating gateway cutover on a debug/staging build before enabling `AURIS_GATEWAY_URL` in production.

## Configuration

- [ ] Set gateway base URL via SharedPreferences `auris_cloud` → `base_url`, or via `BuildConfig.AURIS_GATEWAY_URL` in the build.
- [ ] Confirm kill switch: `auris_cloud` → `direct_upstream=true` restores all Pocket Casts upstream hosts without reinstall.
- [ ] Confirm empty `base_url` override (or empty build default) keeps direct upstream behavior.

## Pocket Casts-compatible traffic (proxied through gateway)

**Interim Android cutover (until gateway multi-host routing):** only
`serverApiUrl` → gateway. Refresh/static/search/cache/list/sharing stay on
direct Pocket Casts hosts. Collapsing those onto api.pocketcasts.com via the
single-upstream proxy yields 401 on discover/OPML (static/refresh return 200).
Auris `/api/v1/...` still uses the gateway via CloudConfig. Failed HTTP
responses log `HTTP <code> <method> <url>`. OPML fails closed on non-2xx.

- [ ] Login / token refresh (gateway)
- [ ] Sync up and sync down (gateway)
- [ ] Discovery / search (direct static/search)
- [ ] Episode metadata and podcast cache
- [ ] Playback file URL resolution
- [ ] List / sharing hosts
- [ ] OPML import (direct refresh; failure toast on HTTP error)

## Auris-owned routes (same gateway host, Bearer `user_{uuid}`)

- [ ] `GET /api/v1/episodes/{uuid}/fingerprints` — reference fetch during playback prep
- [ ] `POST /api/v1/cloud/route` — one full assistant turn (SSE tokens + optional action + `done`)

## Rollback

- [ ] Enable `direct_upstream` kill switch; verify app works against direct Pocket Casts hosts with no gateway dependency.
