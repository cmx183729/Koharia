# Koharia Development Memory

This document is a durable troubleshooting record for the Koharia reader. Update it after each confirmed bug fix with the symptom, evidence, changed files, verification, and remaining limits. Do not record credentials, tokens, private server paths, device identifiers, or user-content details.

## 2026-10-29: SAF and WebDAV scan reports zero works

### User-visible symptom

Refresh could report failure or `LocalLibraryPartialScanException` saying that no supported works were found. The reported local library shape is: one directory per comic, image files inside each directory, and E-Hentai metadata files alongside the images. WebDAV and local storage both showed the issue, including an upstream build. The device is HyperOS 2.0.

### Evidence and diagnosis

- The app does not use a broad legacy storage permission for a user-selected SAF tree. It receives a directory URI from `OpenDocumentTree` and calls `takePersistableUriPermission` with read permission, or read/write permission for managed-library setup.
- A successful first folder selection therefore does not prove that every later nested SAF listing is readable.
- The scanner used `DocumentsContract` only when the current URI was itself a tree URI. A child directory returned from a tree query is normally a document URI. On providers that do not make `UniFile.listFiles()` reliable for that document URI, nested comic folders could appear empty even though the root selection was valid. This is a software compatibility defect and can be amplified by provider behavior on HyperOS.
- E-Hentai metadata is not required for discovery. Image files are the discovery signal; metadata is applied after a candidate is found. A missing or malformed metadata file should not make an image-folder comic disappear.
- WebDAV uses a different backend and does not depend on Android SAF permission. If both WebDAV and SAF fail, the shared scan/candidate/index path still needs separate runtime evidence; this patch does not claim to prove a NAS protocol failure.

### Fix

`app/src/main/java/koharia/source/local/LocalScanDirectoryReader.kt` now carries the originating SAF tree URI for every directory discovered below the root. Nested document URIs are enumerated with `DocumentsContract.buildChildDocumentsUriUsingTree` and child document URIs are built from the original tree URI. This avoids falling back to provider-specific `UniFile.listFiles()` for nested SAF directories.

The existing root-level scan candidate logic remains responsible for image-folder comics and recognizes image files independently of E-Hentai metadata.

### Existing related behavior

- `app/src/main/java/koharia/source/local/LocalFolderSettingsScreen.kt` persists selected tree permissions. The UI can add multiple roots one at a time and rejects overlapping roots; Android's standard `OpenDocumentTree` is not a batch multi-folder picker.
- `.koharia/storage-id` is a stable storage identity marker and must not be deleted.
- Managed `Comics`, `Books`, and `.koharia/metadata/library.json` directories belong to the managed-library layout and should not be removed without a migration plan.
- An empty scan now produces an actionable localized message instead of silently reporting a successful refresh when no candidate and no previous index entries exist.

### Verification

Static checks completed in this workspace: XML parsing, duplicate resource-key checks, and `git diff --check`. The device fixture test `LocalScanDirectoryReaderDeviceTest` covers SAF enumeration, caching, empty results, loading state, and provider failure. Gradle compilation, JVM tests, and device tests remain unexecuted when no Java/JDK is available. Real HyperOS, WebDAV, and NAS routes still require a changed APK and data-preserving runtime validation.

### Next diagnostic procedure

1. Rebuild with a JDK and run `:app:compileDebugKotlin`, `spotlessCheck`, and the focused local scan tests.
2. Install the changed dev APK only with data-preserving replacement after verifying package, certificate, version, and hash.
3. Re-select the actual local root and scan a directory containing at least one comic folder with image files. Do not delete app data or existing files.
4. Separately test the WebDAV root with a read-only scan and record whether the backend lists children and whether candidate count is zero.
5. If SAF succeeds but WebDAV remains empty, investigate WebDAV response/path/cache behavior separately; do not attribute it to Android storage permission.
