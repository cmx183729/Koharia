---
name: comic-reader-refactor
description: Project-local rules for the Koharia comic reader refactor, covering staged Android delivery, metadata-aware local/WebDAV libraries, performance, and tablet/mobile UI work.
---

# Comic Reader Refactor

## Delivery protocol

- Execute exactly one requested phase at a time. After each phase, report changed files, verification results, and remaining limitations; wait for explicit approval before the next phase.
- Preserve unrelated working-tree changes, credentials, connection data, downloads, caches, and existing provider behavior.
- Do not commit, push, or run destructive Git/device commands unless explicitly requested.
- Keep task plans, reports, logs, screenshots, and generated fixtures under `.test-artifacts/<task>/`.

## Architecture

- Follow Koharia's existing Kotlin/JVM 17, Compose Material 3, Voyager, Injekt, SQLDelight, moko-resources, and provider-adapter architecture.
- Reuse shared connection, shelf, details, settings, cache, reader, and E-Ink motion APIs before adding new abstractions.
- Keep provider-specific protocol behavior behind its adapter/backend. Shared UI must remain capability-driven and preserve connection/account identity.
- Any SQLDelight schema change requires the next consecutive migration and aligned adapters, queries, and tests.
- Keep manual downloads, metadata caches, comic page caches, EPUB caches, and thumbnails separate.

## Metadata and library behavior

- Prefer structured parsers for `ComicInfo.xml` and `ametadata`; validate malformed, missing, and oversized input.
- JHenTai archive exports use an extensionless `ametadata` JSON file; also accept `ametadata.json`. Bound reads before allocating the whole payload, retain namespaced tags, and avoid applying a parent folder's metadata to unrelated individual files. Metadata suggestions must respect saved local edits and locked fields.
- Folder-image reader regression coverage must exercise all pages and natural ordering, not merely discovery of the first image. Keep page streams lazy and propagate enumeration errors/cancellation rather than silently falling back to a partial directory list.
- Selecting a global storage root must not eagerly create unused backup/local/download directories. Preserve on-demand consumer resolution, download `.nomedia` protection, and existing directory contents; do not mistake this limited behavior for complete storage consolidation.
- Pending product requirements: consolidate downloads/autobackup/local storage under the selected global storage root without moving or deleting existing data implicitly; avoid per-library clutter; modernize shared UI/settings with conditional E-Ink adaptation and legible translucent styling. Treat these as unfinished until integrated and verified, not as released behavior.
- WebDAV/local scanning must be incremental where possible, bounded by explicit concurrency and request-delay settings, and must not block the UI thread.
- Use HTTP Range requests only when the backend supports them; preserve a correct fallback for servers that do not.
- Persist generated thumbnail files and make shelf rendering read local thumbnails asynchronously. Apply configured size, quality, natural ordering, and double-page crop rules deterministically.
- Treat remote catalogue reads as read-only unless the user explicitly authorizes mutations. Do not log cookies, API keys, or personal metadata.

## UI and settings

- Use localized moko-resources strings rather than hardcoded user-facing text.
- Support ordinary animated mode and the existing E-Ink policy-aware alternatives; run `verifyEInkMotion` for UI or motion changes.
- Keep compact controls stable and accessible. Use responsive phone/tablet layouts and preserve the canonical shared series-details route.
- Settings must persist through the existing shared/global preference boundaries and must not leak provider-specific credentials or options across connections.

## Verification

- For code, schema, resource, dependency, or build changes run at least `./gradlew.bat :app:compileDebugKotlin`; run `spotlessCheck` for formatting-sensitive work.
- For UI/motion changes also run `./gradlew.bat verifyEInkMotion`.
- For release workflow changes validate YAML structure and the relevant Gradle task configuration; never print signing material or secret files.
- Distinguish static checks, isolated fixtures, installed-dev checks, and real-server checks in reports. Do not claim unverified provider parity.
