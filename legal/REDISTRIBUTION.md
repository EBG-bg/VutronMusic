# Redistribution and corresponding source

Product: **Vutron Music - Mobile 1.0.1**. Maintainer: **EBG-bg and contributors**. Review date: **2026-10-26**.

## Project and upstream notices

The root `LICENSE` is the original MIT text, including `Copyright (c) 2022-2024 jooy2`. It has not been replaced or reduced. `NOTICE` additionally attributes stark81/VutronMusic and identifies the mobile modifications by EBG-bg and contributors. The original README also requests personal research and no commercial/illegal use. Those original statements can be read at **https://github.com/stark81/VutronMusic/blob/d5ed97b267f0a1ee6115c648f066969f2b4f3449/README.md** and remain in inherited Git history. Preserve this attribution and the original license; if a proposed use depends on resolving that discrepancy or rights to particular graphics, obtain clarification from the relevant rights holder rather than inventing another license.

The maintainer's fork permission is not a music catalogue license, service provider approval, trademark license or a guarantee that a recording is authorized in every territory. No permission to redistribute downloaded songs, artwork, lyrics, account data, or personal playlists is granted by the software notices.

## LGPL matcher

The embedded `@unblockneteasemusic/server` **0.28.0** is declared **LGPL-3.0-only**. LGPL v3 incorporates GPL v3 with additional permissions. Both full documents are retained:

- `vendor/android-runtime/src/main/assets/sources-runtime/node_modules/@unblockneteasemusic/server/COPYING`
- `vendor/android-runtime/src/main/assets/sources-runtime/node_modules/@unblockneteasemusic/server/COPYING.LESSER`
- Copies in `legal/licenses/embedded-node/` and `legal/THIRD_PARTY_LICENSES.txt`.

The project source repository is https://github.com/EBG-bg/VutronMusic. Any distributed binary must be accompanied by its matching corresponding source and build materials; a published release should preserve that exact source under its matching mobile-v version tag. The current public branch must not be assumed to contain unpublished local changes. The shipped matcher source remains readable JavaScript in `vendor/android-runtime/src/main/assets/sources-runtime/node_modules/@unblockneteasemusic/server/`. The adaptation uses its main matcher, not its Windows service helpers or proxy/hook CLI. The application wrapper and quality/identity changes are source files `server.js`, `quality-selection.cjs` and `strict-identity.cjs` in the same runtime directory, plus `EmbeddedSources.java` and the Android source/build configuration. The license inventory records the exact packaged dependency versions.

Do not restrict modifications of the LGPL library, or reverse engineering necessary to debug those modifications. This project's user guidance adds no such restriction. To use a modified matcher, edit the supplied JavaScript source and rebuild the Android app from the matching source release. The included Gradle/Vite/Capacitor build configuration and the native runtime files are the corresponding application materials; no private signing key is required to build and install a separately signed modified app. A separately signed app may require a different package or uninstalling a previously signed install; user data should be backed up by its owner before changing installations.

If the source is relocated, publish an accessible replacement source location with the binary and keep the matching source version available. Merely naming a license, linking only to today's upstream branch, or offering a binary alone does not replace the corresponding-source obligations.

## Native binary provenance

The Android Java/NIO backports use `com.android.tools:desugar_jdk_libs_nio:2.1.5`. The unmodified official source archive with a 2.1.5 NIO version marker is retained in `vendor/java-compat/` with its revision, SHA-256, source/build materials and origin. Its GPL v2 text, Classpath Exception, assembly exception and additional licensing information are included in `legal/licenses/java-compat/` and the APK notices. Retain these materials when redistributing the corresponding backported classes; the independent application modules retain their own terms under the stated exception.

- **Nodejs-Mobile 18.20.4:** official release at `https://github.com/nodejs-mobile/nodejs-mobile/releases/tag/v18.20.4`. Existing project acquisition notes identify the official Android release archive. The retained ARM64 `libnode.so` is 62,475,584 bytes. Node's full license contains notices for its bundled V8/OpenSSL/ICU/libuv and other components; do not substitute only a short MIT label.
- **Android NDK r24 libc++:** official archive `https://dl.google.com/android/repository/android-ndk-r24-windows.zip`. Full NDK toolchain and libc++abi notices are retained. ARM64 `libc++_shared.so` is 6,761,584 bytes.
- **JNA 5.17.0:** Maven artifact `net.java.dev.jna:jna:5.17.0@aar`, official repository `https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/`. The Apache-2.0 licensing option is used; preserve its dual-license statement and Apache text.
- The public vendor tree provides the ARM64 runtime binaries required by the build. `third-party-inventory.json` records each published binary's SHA-256.

Nodejs-Mobile's matching source is available from **https://github.com/nodejs-mobile/nodejs-mobile/tree/v18.20.4** and its source archive **https://github.com/nodejs-mobile/nodejs-mobile/archive/refs/tags/v18.20.4.tar.gz**. Android build instructions are retained upstream under `doc_mobile/`. Use that exact tag when auditing or replacing the retained runtime; do not substitute current `main` as if it were the source of this binary. No claim is made that the current maintainer independently built the retained Node binary or verified an upstream archive checksum that was not recorded. The official origin, local exact hash, existing notices and corresponding build/source links are kept explicit.

## Notice collection and release scope

`python legal/collect_notices.py` collects existing installed production package licenses and resolved cached Maven notices. First refresh `legal/gradle-runtime-dependencies.txt` with `:app:dependencies --configuration releaseRuntimeClasspath`. The collector fetches a small set of missing canonical upstream license texts and records those source URLs. Review any `unresolvedLicenseFiles` before release. License metadata alone is not a substitute for retaining the full copyright/permission text.

Ship `legal/licenses/`, `legal/THIRD_PARTY_LICENSES.txt`, the inventory, root `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md` with the binary. Retain the embedded runtime package licenses and source. Build/test SDKs, debug probes, development tools, device logs, music exports and credentials are not release assets.
