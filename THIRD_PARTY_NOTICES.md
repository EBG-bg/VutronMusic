# Vutron Music - Mobile: Third-party notices

Release scope: **1.0.0**. Inventory reviewed **2026-10-25**. Maintained by **EBG-bg and contributors**.

The original [LICENSE](LICENSE) is preserved verbatim. Individual third-party components retain their own licenses; the application is **not a statement that every bundled component is MIT**. Full retained texts are in [legal/licenses](legal/licenses) and [legal/THIRD_PARTY_LICENSES.txt](legal/THIRD_PARTY_LICENSES.txt). The machine-readable inventory is [legal/third-party-inventory.json](legal/third-party-inventory.json).

| Component actually used or retained | Version/source | License and notice location |
| --- | --- | --- |
| VutronMusic / Vutron scaffold, shared original UI and assets | stark81/VutronMusic; jooy2/Vutron | Original MIT notice, `LICENSE`, `legal/licenses/Vutron-MIT.txt`; upstream attribution retained |
| YesPlayMusic design/code attribution referenced by upstream | qier222/YesPlayMusic | MIT, `legal/licenses/YesPlayMusic-MIT.txt`; included conservatively, without asserting every upstream UI asset's origin |
| Vue and used `@vue/runtime-core`, `runtime-dom`, `reactivity`, `shared` | 3.5.44 resolved in mobile lock | MIT, `legal/licenses/frontend/` |
| Pinia | 2.3.1 | MIT, `legal/licenses/frontend/pinia-2.3.1/` |
| vue-demi / Vue Devtools API transitive code | 0.14.10 / 6.6.4 | MIT, component copyright notices retained in `legal/licenses/frontend/` |
| Capacitor core and Android | 7.6.9 | MIT, Drifty Co., `legal/licenses/frontend/` |
| Capacitor App plugin | 7.1.2 | MIT, Ionic, `legal/licenses/frontend/` |
| ZXing Core (QR code generation) | 3.5.3 | Apache-2.0, `legal/licenses/ZXing-*`; repository notice may describe modules not present in the APK |
| Java Native Access, including native dispatch | 5.17.0 | Apache-2.0 option of `Apache-2.0 OR LGPL-2.1-or-later`; `legal/licenses/native-runtime/JNA-*` |
| AndroidX / Kotlin standard library / coroutines / Cordova / small Android transitives | Exact resolved coordinates in `legal/gradle-runtime-dependencies.txt` | Apache-2.0; retained artifact notices under `legal/licenses/maven/`, plus Kotlin/Cordova notice files |
| Nodejs-Mobile `libnode.so` | Node 18.20.4; official Nodejs-Mobile Android release | Node's MIT notice **and embedded third-party notices**, `legal/licenses/native-runtime/Nodejs-Mobile-LICENSE.txt` |
| Android NDK `libc++_shared.so` | r24, ARM64 in the mobile APK | LLVM/Apache-2.0 with LLVM exceptions and included subcomponent terms; full NDK and libc++abi notices retained under `legal/licenses/native-runtime/` |
| Native NetEase protocol adaptation | References `@neteasecloudmusicapienhanced/api` 4.36.1 source; the full npm API server is not shipped in the mobile APK | MIT, Binaryify; `legal/licenses/netease-api-4.36.1-MIT.txt` |
| Embedded alternative-source matcher | `@unblockneteasemusic/server` 0.28.0 | **LGPL-3.0-only**, `vendor/android-runtime/src/main/assets/sources-runtime/node_modules/@unblockneteasemusic/server/COPYING*`; corresponding application and library source are public |
| Matcher production JavaScript dependencies | 42 retained dependency package directories, including nested versions | MIT / ISC / BSD-3-Clause, exact individual texts in `legal/licenses/embedded-node/` and the shipped package directories |

The APK contains the Android adapter, generated mobile web bundle, ARM64 native runtime and embedded matcher. Desktop-only Electron, better-sqlite3, Fastify, desktop audio/worker packages and development tools are not represented as mobile runtime dependencies merely because the upstream repository references them. Exact APK contents and the resolved dependency graph take precedence over this summary. Android's system WebView and framework are supplied by the device rather than bundled as a new WebView engine.

## Preserved upstream wording

The upstream README says personal study/research and no commercial or unlawful use, while the separately supplied `LICENSE` is the MIT text, which includes commercial redistribution rights. These statements are not identical. The original MIT text is unchanged and the upstream README is preserved in upstream Git history; this fork does not silently rewrite MIT, pretend the README language is part of standard MIT, or claim that a conversational permission resolves every asset/service right. See [legal/REDISTRIBUTION.md](legal/REDISTRIBUTION.md).

## Distribution contents

Recipients of the public APK must also receive the full notices. The build includes `legal/` under APK assets alongside `LICENSE`, `NOTICE` and this file. Existing runtime license files remain in `assets/sources-runtime/` and individual matcher package paths. The public source and matching release tag provide the build/adaptation materials described in [legal/REDISTRIBUTION.md](legal/REDISTRIBUTION.md). Account sessions, exported music and device diagnostics are not release assets.
