# The public web origin — `sites/`

Runbook for `sites/`: the static pages that have to be reachable **without the app
installed**. The privacy policy Play requires as a listing URL, the account-deletion page
the internal baseline commits to at `/delete-account`, and the terms of service. OD-716.

Live at `https://omnideck-sites.pages.dev` since 2026-08-29.

## Why this file is not in `sites/`

**Everything in `sites/` is published, whether or not it is a page.** Cloudflare Pages
serves the deployed directory as-is; there is no notion of a file that is present but
private. This runbook shipped inside `sites/` for one deploy and was readable at
`/README.md` — a document naming ticket numbers, ADR-011 and the domain strategy, on the
same origin we hand to Play.

So the directory holds only what is meant to be public, and this lives outside it. That
invariant is worth keeping: it fails safe when someone adds a file without thinking about
who can read it, which a routing rule hiding one filename would not.

The exceptions are Cloudflare's own control files. `_headers`, `_redirects`, `_routes.json`
and `_worker.js` are read as configuration and never served — verified: `/_headers` returns
404 while `/robots.txt` returns 200. Nothing else gets that treatment, underscore prefix or
not.

## Not part of the Gradle build

No convention plugin sees `sites/`, `settings.gradle.kts` only discovers projects under
`modules/`, and the quality gates that run in CI are Kotlin and Android tools. The one gate
that reaches any of this is Spotless's `misc` format, which holds `**/*.md` — this file
included — to no trailing whitespace and a final newline.

## Deploying it

One Cloudflare Pages project, connected to this repository, serving `sites/` as the site
root. There is no build step: the files are served as they are.

| Setting | Value |
|---|---|
| Production branch | `main` |
| Framework preset | None |
| Root directory | `sites` |
| Build command | *(empty)* |
| Build output directory | `/` |

Root directory is the field that matters. It scopes the project to this subtree, so the rest
of the repository is neither built nor deployed, and it leaves room for a second Pages project
(a marketing site, hosted docs) pointed at a sibling directory later without disturbing this
one. Cloudflare supports several projects from one repository, each with its own root.

Because the root directory *is* the site root, paths under `sites/` map to URLs directly:

| File | URL |
|---|---|
| `sites/index.html` | `/` |
| `sites/privacy/index.html` | `/privacy/` |
| `sites/delete-account/index.html` | `/delete-account/` |
| `sites/terms/index.html` | `/terms/` |
| `sites/assets/style.css` | `/assets/style.css` |
| `sites/404.html` | served for anything unmatched |
| `sites/robots.txt` | `/robots.txt` |
| `sites/_headers` | response headers; the file itself 404s |

A path without its trailing slash redirects: `/delete-account` returns 308 to
`/delete-account/`. Both forms are safe to hand to Play.

Preview deployments are worth turning off (Settings → Builds & deployments → Preview
deployments → None) until the pages clear legal review. They are public URLs, and every
branch that touches this directory would otherwise publish its draft of a privacy policy.

Preview locally with any static server — the directory-index and trailing-slash behaviour
matches what Pages does:

```bash
python -m http.server 8080 --directory sites
```

## Before Play submission

Every placeholder must be replaced. They are deliberately unmistakable rather than
plausible-looking defaults, so a missed one fails loudly instead of publishing something
wrong:

```bash
grep -rn '{{[A-Z_]*}}' sites/ --include='*.html'
```

| Placeholder | Replace with |
|---|---|
| `{{ENTITY}}` | The publisher name shown in the Play listing — the individual or company that is actually the data controller |
| `{{CONTACT_EMAIL}}` | A monitored address for privacy, deletion and security mail. Not a personal inbox that leaks an identity you did not intend to publish |
| `{{JURISDICTION}}` | Where `{{ENTITY}}` is established. A decision, not a fill-in-the-blank |
| `{{LIABILITY_CAP}}` | A figure and currency, once the entity and jurisdiction are settled |

The privacy policy and terms also carry a visible **pre-release draft** notice. It stays until
OD-702 (legal review) signs off, and comes out in the same change that replaces the
placeholders — not before, and not separately.

## What must not go in here

**No `.well-known/assetlinks.json`, and no `/go/` pages.** Both belong to OD-321, which needs
a *permanent* origin, and this one is not permanent. The reasoning is in ADR-011's amendment
and summarised in the repository `CLAUDE.md`: an App Link, once shared, outlives the host it
was published under, so standing them up on a Cloudflare-supplied `*.pages.dev` subdomain buys
working links today at the price of breaking every shared URL on migration — or serving
redirects from an abandoned host forever.

Policy pages are the opposite case, and that asymmetry is the whole reason this directory can
exist now. They are reached through the Play listing rather than through links people have
saved, so moving one costs a listing edit. That is why OD-716 is unblocked on a free origin
while OD-321 is not, and why `robots.txt` here disallows indexing: search results are durable
references too.

`scripts/assetlinks.py verify` runs against any host, so the App Link flow can be rehearsed
here without publishing a single shareable `/go/` URL.

## Checking a deploy

Pages rebuilds on every push to `main`. What is worth re-checking after a change to
`sites/` — the last line is the one that matters, and the reason this runbook moved:

```bash
B=https://omnideck-sites.pages.dev
for p in / /privacy/ /delete-account/ /terms/ /robots.txt /nope \
         /.well-known/assetlinks.json /README.md; do
  printf '%-32s %s\n' "$p" "$(curl -sS -o /dev/null -w '%{http_code}' "$B$p")"
done
curl -sSI "$B/privacy/" | grep -i content-security-policy
```

Expected: 200 for the four pages and `robots.txt`, **404** for `/nope`,
`/.well-known/assetlinks.json` and `/README.md`, and the CSP header present. An
`assetlinks.json` that starts answering 200 means someone published App Links under a
throwaway origin — see below.

## When the permanent domain arrives

In roughly this order:

1. Attach the domain to this same Pages project as a custom domain. The content does not move.
2. Delete `robots.txt`.
3. Update the privacy policy URL in the Play Console listing.
4. Then, and only then, OD-321: generate `.well-known/assetlinks.json` with the **Play App
   Signing** SHA-256 (`scripts/assetlinks.py generate --fingerprint <SHA256>`), publish it,
   confirm `scripts/assetlinks.py verify` exits 0, and restore the App Link intent filter —
   the exact snippet is in `app/src/main/AndroidManifest.xml`.
