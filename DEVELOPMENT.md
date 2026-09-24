# Development

How to build wazeology from source, install it without the Installer, and publish the Installer. The
overview comes first, and everything below it walks through how the patch actually works end to end: every
non-obvious decision, the traps to watch for, and the reasons the pipeline is shaped the way it is. It is
written so that a later maintainer, human or agent, can rebuild or extend the patch without stepping on the
same landmines again. The rider-facing install guide is [`README.md`](README.md), and the working rules for
this repo are in [`CLAUDE.md`](CLAUDE.md).

## Two ways to get Waze with Wazeology

One build engine (`core/`) turns the pinned Waze into **Waze with Wazeology**: it checks the input, grafts the
Wazeology pieces onto a copy of the pristine base and signs the base plus every split. Two build targets run
that same engine:

- The Installer (`dist/wazeology-installer.apk`) is an Android app that downloads Waze on the phone,
  runs the engine there and installs the result. This is what riders use (see the README), and what gets
  published on GitHub Releases.
- The direct build (`dist/waze/*.apk`) is the engine run on the host over the Waze in `apk/`, producing the
  signed base and splits for `adb install-multiple`. See [Manual install](#manual-install-without-the-installer).

Both sign with the same key, so a Waze installed one way can be updated the other way, and the Installer
recognizes a directly installed Waze as its own.

## Requirements

Everything runs inside the pinned toolchain container (`docker/Dockerfile`). The host only needs Docker to
build, plus an Android device to install on. Nothing else, not apktool, the Android SDK, or apkeep, gets
installed on the host. Every script sources `scripts/lib.sh` and invokes its tools through `run_tools`.

Pinned versions, kept in sync between `scripts/lib.sh`, `docker/Dockerfile`, and
`core/src/com/wazeology/core/Pins.java`: Waze 5.23.0.2 (versionCode 1030725), apktool 2.10.0, Android
build-tools 34.0.0, platform android-34, apkeep 1.0.0, apksig 8.7.3, and JDK 17. `build-assets.sh` greps the
pins on all three sides and fails the build when they drift.

## Build pipeline

```bash
cp .env.example .env         # optional: set the download source
scripts/build-image.sh       # once: build the pinned toolchain image (~1.5 GB first time)
scripts/all.sh               # fetch -> decompile -> patch -> build -> test
```

`scripts/all.sh` builds both targets; `TARGET=installer scripts/all.sh` or `TARGET=waze scripts/all.sh`
builds just one. Step by step:

```bash
scripts/fetch-apk.sh         # fetch the pinned Waze into apk/ (gitignored), skipping if already present
scripts/decompile.sh         # apktool d into build/base_apktool, skipping if already present
scripts/patch.sh             # inject the smali hooks + the launcher <activity>
scripts/build.sh [installer|waze|all]   # default all; runs the three scripts below
  scripts/build-assets.sh    #   bake the patch assets + signing key into build/assets/ (runs framecheck.sh first)
  scripts/build-installer.sh #   -> dist/wazeology-installer.apk
  scripts/build-waze.sh      #   -> dist/waze/base.apk + every split, verified with apksigner + zipalign
scripts/test.sh              # host gates: the engine against apk/base.apk, then the installer logic
```

Each step is documented in detail, with its rationale, in the sections below. `fetch-apk.sh` and
`decompile.sh` skip their work when their outputs are already in the workspace; `FORCE=1` (as in
`FORCE=1 scripts/all.sh`) re-downloads and re-decompiles from scratch.

A few notes on inputs and reproducibility:

- APKs and keys are never committed. `fetch-apk.sh` downloads the pinned Waze on the host, and the signing
  keys are generated locally the first time you build (see [Keys](#keys)).
- By default it downloads from apk-pure, which needs no credentials. You can switch to google-play through
  `.env` for a byte-exact copy; that source needs an account email and an AAS token, and `.env.example`
  shows how to set it.
- `ABIS` (comma-separated, default `arm64-v8a`) tells `build-waze.sh` which native split the direct build
  must include.

After editing Java in `payload/`, keep it Java 8-compatible and Android-framework-only (no Kotlin, no
AppCompat, no new resources). Validate frame-builder changes with `scripts/framecheck.sh` (a host-JVM
byte-layout test). `core/`, `installer/` and `cli/` follow the same plain-Java style; `core/` and
`installer/core/` have no `android.*` imports, so the host gates run the exact classes the phone runs.

### Layout

```
payload/            the Wazeology package injected into Waze (com.waze.wazeology + the hook bridge)
patches/            the smali hook + manifest injections (apply_patches.py)
core/               the build engine both targets share: Graft, MiniAxml, Signing, BuildPipeline, Pins, ...
core/test/          the engine gate (GraftHostTest)
installer/core/     the Installer's own logic, host-testable: download, caches, job and screen state
installer/app/      the Installer app (Android, programmatic UI, en + pt-BR)
installer/test/     the installer logic gate (StateHostTest)
cli/                BuildWaze, the direct build's entry point on the host
build/assets/       generated: the baked patch assets + signing key, read by both targets (gitignored)
dist/               generated: wazeology-installer.apk and waze/*.apk (gitignored)
```

## Manual install (without the Installer)

This path builds on a computer and installs over USB. You need the build set up as above, and a
phone with USB debugging on (Settings > About phone > tap Build number seven times, then Settings >
Developer options > USB debugging).

1. If the phone has the Play Store Waze (or any Waze not signed by your build), uninstall it first. Android
   refuses to replace an app signed by someone else, and uninstalling deletes Waze's local data (your Waze
   account data comes back when you sign in).
2. Build the direct target and install it:

   ```bash
   scripts/build.sh waze
   adb install-multiple dist/waze/*.apk
   ```

3. Open the new **Wazeology** icon, **Scan for motorcycle**, pick yours and confirm the pairing on the
   dashboard.

Re-running the same two commands updates it in place. Because the direct build and the Installer sign with
the same key, you can switch between them at any time without uninstalling.

## Publishing the Installer

Riders download the Installer from GitHub Releases:

```bash
scripts/build.sh installer
gh release create vX.Y dist/wazeology-installer.apk --title "Wazeology X.Y" --notes "..."
```

Bump `--version-code` in `scripts/build-installer.sh` for every release, so Android treats it as an update.

### Keys

Two locally generated keys decide who can update what, so back both up before the first release:

- `build/debug.keystore` signs the Installer app itself. Android only installs a newer Installer over an
  older one when both carry the same signature, so every release must be built with the keystore that built
  the first one.
- `build/gen/wazeology-sign.p12` signs Waze with Wazeology (baked into the Installer and read by the direct
  build). If it changes, riders can't update their Waze with Wazeology without uninstalling it. The scripts
  never regenerate it while it exists, and they move a key left by an older layout (`installer-sign.p12`,
  `builder-sign.p12`) into place instead of creating a new one.

---

# The full mechanism

---

## 0. The mental model: a split bundle goes in, Waze with Wazeology comes out

Modern Play apps ship as an **App Bundle**, delivered to the device as several APKs that share one identity:

- `base.apk`: the code (`classes*.dex`), `AndroidManifest.xml`, `resources.arsc`, and default resources.
- `split_config.arm64_v8a.apk`: native libraries for the device ABI.
- `split_config.xxxhdpi.apk`: density-specific resources.
- `split_config.pt.apk`: a language config split (third-party library strings; Waze's own strings are in base).

apkeep hands us the base and all these config splits (§1). They are the input to the **patch asset build**,
not to the final artifact. We patch only **code + manifest**, which live in `base.apk`, and leave the
splits' own contents alone.

The build engine (`core/`) turns that input into **Waze with Wazeology**: it grafts the prebuilt patched
dexes, manifest and icon onto a copy of the pristine base and signs the base plus every split with the
Wazeology key. It runs in two places. The **Installer** (`dist/wazeology-installer.apk`) runs it on the phone
(§8): it downloads the pinned Waze from APKPure (or takes Waze apks the rider picks), builds, and installs the
result through the package installer (or exports the files). The **direct build** (`BuildWaze` in `cli/`,
`scripts/build-waze.sh`) runs the same engine on the host over `apk/`, writing `dist/waze/*.apk` for
`adb install-multiple`. Both read the same baked assets (§7), so they produce the same apks.

---

## 1. Fetch the APK, never committed (`scripts/fetch-apk.sh`)

We never store Waze in git. `apkeep` (EFF's downloader) fetches the **pinned** version inside the container:

```
apkeep -a com.waze@5.23.0.2 -d apk-pure  apk/_dl
```

- **apk-pure** (default) needs no credentials and returns a split app as an **XAPK** (a zip of `base` +
  `config.*` APKs). `scripts/normalize_apks.py` unpacks it and classifies each inner APK by content, the
  base is the one that contains `classes.dex`; the rest are splits, writing `apk/base.apk` +
  `apk/split_config.*.apk`.
- **google-play** (`APK_SOURCE=google-play` + `GOOGLE_EMAIL`/`AAS_TOKEN` in `.env`) yields byte-exact store
  APKs but needs an account and an AAS token. Version is pinned by **versionCode** there, not versionName.

Reproducibility caveat: mirror bytes (apk-pure) may differ from Play. For byte-exact inputs use google-play.
Either way the *inputs* are pinned to one version; the *outputs* differ only by the local signing key.

Verify identity: `aapt2 dump badging apk/base.apk` shows `package: name='com.waze' versionName='5.23.0.2'`.

The download is slow, so `fetch-apk.sh` skips it when `apk/base.apk` and the `apk/split_config.*.apk` are
already in the workspace. Run `FORCE=1 scripts/fetch-apk.sh` to re-download.

---

## 2. Decompile (`scripts/decompile.sh`)

```
apktool d --force apk/base.apk -o build/base_apktool
```

`decompile.sh` skips this when `build/base_apktool` already exists, so patches you have applied to the tree
survive a re-run of the pipeline. Run `FORCE=1 scripts/decompile.sh` to discard it and decompile afresh.

This baksmalis every `classes*.dex` into `smali/`, `smali_classes2/` … and decodes resources + the binary
manifest into editable form. We edit **smali** (code) and **AndroidManifest.xml** here. We will **not** ship
apktool's rebuilt resources (see §3). `apktool.yml` records `minSdk 32`, `targetSdk 36`, `forcedPackageId
127`, needed so apktool reassembles with the original resource IDs.

Note `smali_classesN/` maps 1:1 to `classesN.dex`: the classes that were in `classes6.dex` baksmali into
`smali_classes6/`, and rebuild back into `classes6.dex`. This is why individual reassembled dexes can be
extracted and shipped as patch assets (§7). A hook lands in whichever dex holds its target class: the nav
hooks live in `NavigationInfoNativeManager` in `classes6.dex`, the `FreeMapAppActivity` startup hook in
`classes5.dex`. The assets must carry **every patched hook dex**. `build-assets.sh` derives that set from the
injected hook markers and asserts it matches `Pins.HOOK_DEXES`, so a hook added to a new dex is never
silently dropped.

---

## 3. THE GOLDEN RULE: keep resources pristine (why the graft exists)

**Symptom we hit:** after a naive `apktool b`, Waze launched fine but **tapping "see routes" crashed**:

```
android.view.InflateException: ... com.waze.trip_overview.views.route_card_options.RouteCardOptionsView
Caused by: org.xmlpull.v1.XmlPullParserException: Binary XML file line #4:
  <item> tag requires a 'drawable' attribute or child tag defining a drawable
  ... res/drawable/abc_switch_thumb_material.xml
```

**Cause:** apktool's resource **re-encode** (aapt2 recompile of `res/` + `resources.arsc`) mangled an
AndroidX drawable, a selector `<item>` lost its `drawable` attribute. It's a silent semantic corruption,
not a build error, and it only surfaces on a screen that inflates that drawable (the route card's Switch).

**Fix / architecture:** never let aapt2 recompile the resource XML. Start from the **pristine `base.apk`**
and swap in only patched **code + manifest**, keeping every `res/*` file **byte-identical**. That is the
"graft", and it now runs on the device (§8) with the same assertions the old host graft had:
`resources.arsc` must come out **byte-identical**, enforced by a hash comparison before the patched apk is
accepted, and proven again by the host gate (§9).

The rule's real constraint is that aapt2 must not recompile the resource XML, not that `resources.arsc` can
never change. The new pipeline never changes it at all: the split install (§8) keeps the splits as separate
APKs, so no resource-table merge ever happens, on the host or on the device.

---

## 4. Smali hooks: reading Waze's live guidance (`patches/apply_patches.py`)

Waze resolves turn-by-turn in native code (`libwaze.so`), but the results surface through a **plain Java**
manager, `com.waze.navigate.NavigationInfoNativeManager`, whose `on…Changed(...)` methods are called from
native per guidance tick. We inject four one-line hooks there (each idempotent, anchored to the method's
`.locals` directive or its final `return-void`):

| Waze method | reads | calls |
|---|---|---|
| `onCurrentInstructionChanged(I)` | maneuver code `p1` → `Instruction$Type.forNumber(p1).name()` | `InstructionReporter.setManeuver(code, name)` |
| `onCurrentInstructionDistanceChanged(DistanceUpdate)` | instance fields `distanceMeters`/`instructionDistance`/`distanceUnit` (set earlier in the method) | `InstructionReporter.onDistance(m, text, unit)` |
| `onExitNumberChanged(I)` | roundabout exit ordinal `p1` | `InstructionReporter.onExitNumber(exit)` |
| `onNavigationStateChanged(ZI)` | navigating flag `p1` | `InstructionReporter.onNavState(navigating)` |

Smali details that matter:

- **Register budget.** Injecting at method entry may clobber `v0`; it's safe only because the original code
  reassigns `v0` before reading it. Check the method's `.locals N` and first `v0` use before reusing it. The
  maneuver hook needs `v0` (to hold the resolved enum name); the exit/nav hooks use only `p1`.
- **Distance hook placement.** `distanceMeters`/`instructionDistance`/`distanceUnit` are populated *during*
  the method from the `DistanceUpdate` param, so reading them at entry gives **stale** values. The hook is
  therefore inserted **before the method's final `return-void`**, after the fields are set. (Alternative:
  read them from the `DistanceUpdate` param at entry, but the proto accessor names are less certain.)
- **Enum name resolution.** `Instruction$Type` extends `java.lang.Enum`, so `invoke-virtual … Enum;->name()`
  yields readable names (`TURN_LEFT`, `KEEP_LEFT`, `ROUNDABOUT_EXIT_LEFT`, …). We map by **name**, not by
  the proto's numeric id, so the mapping is robust to proto-number churn.
- The reassembled `smali_classes6/` becomes `classes6.dex` (nav hooks) and `smali_classes5/` becomes
  `classes5.dex` (the `FreeMapAppActivity` startup hook); both are baked into `build/assets` (§7).

`InstructionReporter` (in `payload/java/com/waze/debug/`) is the tiny bridge the hooks call; it forwards to
the process-wide `ClusterBridge` singleton and swallows every exception so instrumentation can never crash
Waze.

---

## 5. Adding a screen without touching resources: the manifest graft

Wazeology needs a UI. An Activity must be declared in the manifest, but we must not rebuild resources. So:

- **Permissions:** none added, Waze already declares `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, legacy
  `BLUETOOTH`/`ADMIN`, `ACCESS_FINE/COARSE/BACKGROUND_LOCATION`, `FOREGROUND_SERVICE(+_LOCATION)`, `INTERNET`.
  A BLE central/GATT client needs nothing more. (It bundles a BLE *scanner* but no GATT/bond client, that's
  what we add.)
- **One `<activity>`** (`patches/manifest-activity.xml`) is inserted after `<application …>` with a
  `MAIN`/`LAUNCHER` intent-filter → a **second launcher icon** "Wazeology", same process as Waze. It sets
  **no `android:theme`** (inherits `Theme.Main`) and its only resource reference is
  `android:icon="@mipmap/launch_icon_round"`, an **existing** resource id (see §5.1), so it introduces
  **no new resource id**. That is what makes the graft safe: apktool preserves resource IDs
  (forcedPackageId 127, no new resources), so the rebuilt binary manifest's references still resolve against
  the untouched pristine `resources.arsc`.
- **The UI is fully programmatic** (`WazeologyActivity` builds `LinearLayout`/`Button`/`TextView` in code,
  uses only `android.R.*`). No layout XML, no AppCompat, no new drawables/strings → resources stay pristine.

No foreground `Service` is used (Waze's manifest lacks `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and adding it
would mean touching permissions). Instead `ClusterBridge` is a **process-wide singleton** obtained via
reflection (`ActivityThread.currentApplication()`), so the smali hooks can feed it even before the screen is
opened. The BLE link lives as long as the Waze process. A saved, bonded motorcycle is waited for **passively**
(an `autoConnect=true` GATT handle, so the stack links up whenever the bike appears, with no retry timer);
"Connect" forces one direct attempt, "Disconnect" pauses the wait until the next launch, and "Forget" drops
it.

### 5.1 A distinct launcher icon without a new resource

Wazeology gets its own launcher art (Rideology's green slash + custom "R" on Waze blue), but the golden
rule forbids adding a resource id, since that means re-encoding `resources.arsc`. Three facts make it
possible anyway:

- **`mipmap/launch_icon_round` is an orphan.** Waze's `<application>` sets only
  `android:icon="@mipmap/launch_icon"`, never `android:roundIcon`, so `launch_icon_round` is never displayed
  by Waze. We repurpose it: the Wazeology `<activity>` points `android:icon` at it.
- **`resources.arsc` maps ids → file *paths*, not bytes.** Overwriting the *content* of an existing
  `res/*` entry (same path) leaves the resource table byte-identical. So we replace only the bytes of
  `launch_icon_round`'s `(anydpi)` file (`res/gBz.xml` in the pinned base.apk); Waze's `launch_icon` and the
  shared `launch_icon_foreground` are untouched. `minSdk 32` means every device uses the adaptive `(anydpi)`
  variant, so the density webp fallbacks never load and don't need patching.
- **The replacement references no app resource id.** `payload/icon.xml` is a *self-contained*
  `<adaptive-icon>`: both background (Waze blue `#33ccff`) and foreground (the mark) are **inline
  `<vector>`s**, so it uses only framework (`android:*`) attributes. `build-assets.sh` compiles it standalone with
  `aapt2` (compile + link against `android.jar`), and the compiled binary XML becomes an asset (§7) that the
  on-device graft drops over the orphan entry. Because it names no app id, nothing needs to be relinked
  against the pristine `resources.arsc`.

The mark art itself is the Rideology vector logo (extracted from the Rideology APK), scaled/centred on the
108×108 adaptive grid via a `<group>` transform, with the "R" recoloured white.

---

## 6. Compile the Wazeology package to a dex (`scripts/build-assets.sh`)

```
javac -source 8 -target 8 -cp /opt/android-sdk/platforms/android-34/android.jar payload/java/**/*.java
d8 --min-api 32 --output … build/gen/cls/**.class     # -> build/gen/patches/classes11.dex
```

Two gotchas:

- **`android.jar` goes on `-classpath`, NOT `-bootclasspath`.** The Android stub `android.jar` has no
  `java.lang.invoke.LambdaMetafactory`; with it as bootclasspath, compiling any lambda fails
  (`Unable to find method metafactory`). Putting it on the classpath lets the JDK supply `java.*`
  (lambdas compile) while `android.*` resolves from the stub. `d8` desugars the lambdas for `--min-api 32`.
- **Java 8 / framework-only.** The Wazeology package is deliberately plain Java (no Kotlin → no
  `kotlin-stdlib` bloating the dex) and uses only the Android framework, so the dex is tiny and
  self-contained.

Before compiling, `build-assets.sh` runs `scripts/framecheck.sh`, a host-JVM test that asserts the exact byte
layout of `turnByTurn` / `meterIndication` / `phoneName` / `initSequence` / `isResponseTo`. This catches any
regression in the frame builders without hardware.

---

## 7. Bake the patch assets (`scripts/build-assets.sh`)

The patched hook dexes are deterministic for the pinned Waze build, so they are computed **once on the
host** and baked into `build/assets/` (gitignored, generated), which both targets read: the Installer packs
the directory as its app assets (`aapt2 link -A build/assets`), and `BuildWaze` reads it through
`FileAssets`. The names come from `Pins`:

- `patches/classes5.dex` / `patches/classes6.dex`: the **patched** hook dexes, extracted from an
  `apktool b build/base_apktool` reassembly. Every dex a hook touched must be extracted; `build-assets.sh`
  derives the set from the `Lcom/waze/debug/InstructionReporter;->` markers and asserts it equals
  `Pins.HOOK_DEXES`.
- `patches/manifest.bin`: the rebuilt **binary** `AndroidManifest.xml` from the same reassembly (it carries
  the injected activity + provider, §5).
- `patches/classes11.dex`: the Wazeology payload dex (§6), named after the next free dex index in the
  pristine base (asserted against `Pins.PAYLOAD_DEX`).
- `patches/icon.bin`: the compiled adaptive icon (§5.1). The target path is resolved from
  `aapt2 dump resources apk/base.apk` and asserted against `Pins.ICON_PATH`.
- `signing/wazeology.p12`: a copy of `build/gen/wazeology-sign.p12`, the PKCS12 that signs Waze with
  Wazeology in both targets. It is generated once and reused (see [Keys](#keys)), so every build from either
  target can update every other. It is a throwaway key, not a secret, but it must never be committed.

The rebuilt **resources** of the apktool reassembly are discarded as always. Only code + manifest come out
of it.

---

## 8. The engine on the phone: the Installer (`installer/` + `core/`)

The Installer app (`com.wazeology.installer`, label **Wazeology Installer**, minSdk 26, plain Java,
programmatic UI, en + pt-BR) runs the whole patch on the phone. The rider sees two actions, **Prepare Waze
with Wazeology** (download + build, one job) and **Install**, plus **Remove current Waze** when a
conflicting Waze is installed. The pipeline underneath:

1. **Input.** By default the app downloads the pinned bundle from APKPure (`ApkPure` in `installer/core`),
   the same source `fetch-apk.sh` uses through apkeep, and with the same request: the
   `api.pureapk.com/m/v3/cms/app_version` endpoint, the app's `x-cv`/`x-sv`/`x-gp` headers, and `x-abis`
   set to the device's ABIs. The response is protobuf, read without a schema like apkeep does. Each asset
   record holds a 40-hex SHA-1, its size (a varint right after it), the type (`XAPK`/`APK`) and the
   download URL, whose path segment is base64url of `<package>_<versionCode>_<id>`. The app keeps the
   assets whose versionCode is the pinned one, in response order (the server ranks them by `x-abis`;
   5.23.0.2 has an arm64 + xxxhdpi bundle and an armeabi-v7a + mdpi one). Before downloading an XAPK it
   reads the zip's central directory with a tail `Range` request and skips a bundle without the
   `config.<abi>` native split for the device's primary ABI. The fallback is picking files through the
   Storage Access Framework: either the `.xapk` bundle or loose `base.apk` + `config.*` splits. Picked files
   are checked (step 2) before they replace anything.
2. **Download and cache.** `Downloader` fetches into `download.part` and resumes it: the APKPure link 302s
   to a CDN (`data.winudf.com`) that honors `Range` and sends an `ETag`, so a retry sends `Range: bytes=N-`
   with `If-Range` (a changed file answers 200 and restarts). Redirects are followed by hand so the range
   headers survive, and `Accept-Encoding: identity` keeps Content-Length and ranges meaningful. Download
   links carry a time-limited token, so a resume looks the asset up again and continues only for the same
   SHA-1. `Preparer` retries with backoff, parks in a *waiting for network* phase when the connection drops,
   verifies the SHA-1, and promotes the file into `SourceCache`: `noBackupFilesDir/sources/<versionCode>/`
   with a `source.properties` written last (temp file + rename), so only complete, verified sources count.
   The no-backup files dir is never evicted by the OS (unlike `cacheDir`), so rebuilds, reinstalls and
   retries never download again; **Delete downloaded files** is the rider's way to reclaim the space.
3. **Version gate.** `BuildPipeline.inspect` unpacks and classifies the bundle (the base is the apk that contains
   `classes.dex`), then `MiniAxml` (a small hand-rolled binary-XML reader) pulls `package`, `versionCode` and
   `versionName` out of the base's compiled manifest. The patch assets are deterministic only for the pinned
   build, so anything other than `com.waze` versionCode 1030725 is refused. The dex layout is checked too
   (exactly `classes.dex` … `classes10.dex`), which also catches a pre-patched apk, and so is the presence of
   a native split for one of the device's ABIs. Every refusal is a typed `Outcome` the app explains in plain
   words.
4. **Graft.** `Graft` (pure `java.util.zip`) rewrites a copy of the pristine base, entry by entry: swap
   `classes5.dex`/`classes6.dex` with the patched assets, swap `AndroidManifest.xml` with `manifest.bin`,
   overwrite the bytes of `res/gBz.xml` with `icon.bin` (skipped with a warning if the entry is absent),
   append `classes11.dex`, and copy everything else with its compression method preserved. Stale
   `META-INF/` v1 signature remnants are dropped. The writer **page-aligns every STORED entry to 4096 bytes**
   via local-header extra-field padding, so dex entries (stored uncompressed, like the originals) can be
   memory-mapped. Untouched deflated entries are re-deflated at BEST_SPEED so a ~90 MB rewrite stays quick on
   phone hardware. After the rewrite, the app hashes `resources.arsc` and **refuses the output** unless it is
   byte-identical to the pristine base's, the golden rule (§3) enforced on the device.
5. **Sign and seal.** `Signing` calls apksig, the same library `apksigner` wraps; the jar is dexed into the
   app at build time. The grafted base and every split are signed with the embedded PKCS12 key, v2+v3 only
   (Waze needs API 32, so nothing older than 28 ever verifies them). Splits are re-signed in place, entry
   bytes untouched, so their own alignment survives. The signed set is written into
   `noBackupFilesDir/builds/<key>.tmp/` and renamed into place only after a `COMPLETE` list (names + sizes)
   is written, so a crash or cancel never leaves an installable partial set. The key hashes the source's
   content id, every baked asset (including the p12), the pins and `Graft.FORMAT` (bump it by hand when the
   graft or signing logic changes the output bytes); an installer with new assets rebuilds from the cached
   download, and the startup sweep evicts stale builds.
6. **Install.** Before anything is downloaded, `WazeProbe` classifies the installed Waze: none, ours (signed
   with the embedded cert, checked with `hasSigningCertificate`) at the pinned, an older or a newer version,
   another signer (the Play build), preinstalled with the system, or present only in another profile. A
   conflicting Waze turns Install off and puts **Remove current Waze** (`PackageInstaller.uninstall`) first.
   The install itself writes base + splits into one `PackageInstaller` session (`setSize` so Android frees
   space up front, `setRequireUserAction(NOT_REQUIRED)` on 31+ for reinstalls over our own build,
   `setRequestUpdateOwnership(true)` on 34+ so the Play Store must ask before replacing it) and commits.
   `InstallStatus` classifies the result from the legacy status, then the `INSTALL_FAILED_*` message prefix,
   then the public status: a newer Play Waze fails as `INSTALL_FAILED_VERSION_DOWNGRADE` (surfacing as
   `STATUS_FAILURE_INVALID`) before signatures are ever compared, and a user cancel shares
   `STATUS_FAILURE_ABORTED` with a Play Protect rejection, so the coarse status alone is misleading.
7. **Export.** As an alternative to installing, the app writes the signed apks (plus a short README) into a
   new folder the rider picks, to move the build to another device.

**State and interruptions.** The activity holds no progress state: it renders `UiModel.derive(Facts)` (in
`installer/core`, host-tested) over facts from `JobStore`, a process-wide holder of the running job
(`JobState`, with a compare-and-set gate against double starts), the disk caches, the installed-Waze probe
and a few persisted flags (the outcome banner, the committed install session, a pending Settings round
trip). Every long job runs on one worker thread in `JobService`, a `dataSync` foreground service with a
progress notification and a Cancel action, so screen-off, app switching and swiping the app away do not stop
it. Cancellation is cooperative (`CancelToken`, checked in the download, unpack, graft and session-write
loops and between signings; a blocked socket read is unblocked by disconnecting). Install and uninstall
results go to a manifest-declared receiver through an explicit-component `PendingIntent`, so a result that
arrives after a process death is still recorded. When Android needs the rider's confirmation while the app is
in the background, a notification carries it; back on screen, an unanswered one is shown directly. A job
marker in the prefs turns a process death mid-prepare into an "interrupted, tap Continue" banner. The
activity is `singleTop` (a `singleTask` relaunch would destroy the install confirmation above it) and
handles every configuration change in place.

The split install is what makes the whole model work without a resource-table merge: the base and the
splits stay separate APKs, so `resources.arsc` is never re-serialized anywhere. There is no single-apk
bundling step anymore, on the host or on the device.

The engine itself (`Graft`, `MiniAxml`, `Signing`, `BundleInput`, `BuildPipeline`) lives in `core/`; the
Installer's own logic (download, caches, job and screen state) lives in `installer/core/`. Neither has
`android.*` imports, so the host gates (§9) exercise the exact classes the phone runs. The engine reports its own steps (`BuildPipeline.Step`: unpack, check, graft, sign), and the Installer
shows them as the job phases of the same name (`JobState.steps`).

---

## 9. The host gates (`scripts/test.sh`)

The build engine is proven on the host before either artifact is trusted. `test.sh` runs two gates on the
plain host JVM. The first compiles `core` + `core/test` against apksig and runs `GraftHostTest` (the engine
gate), which:

- builds the **same PatchSet the app assembles from the same assets**, then grafts the pristine
  `apk/base.apk` with it;
- asserts every expected outcome: `resources.arsc` byte-identical, patched dexes swapped, manifest
  swapped, icon overwritten, payload dex appended as `classes11.dex`, untouched dexes passthrough, stale
  `META-INF` dropped;
- checks the pins against the pristine base (manifest package/versionCode via `MiniAxml`, dex count, icon
  path);
- signs the graft and the arm64 native split with the embedded key, using the same apksig call the app
  makes;
- runs the whole `BuildPipeline.build`, the same call both targets make, on the base plus three splits,
  checks its refusals (no native split, another processor, an already modified Waze, splits without a
  base) and that a cancel mid-graft signs nothing.

The second compiles `core` + `installer/core` + `installer/test` and runs `StateHostTest` (the installer
gate), which needs no apk. It covers the APKPure response parser, the install-result classifier table, the
derived screen state, the job gate, the build cache (sealing, eviction, tamper detection), and the
resumable downloader against a local `com.sun.net.httpserver` (range resume through a redirect, a stream
cut mid-body and resumed by `Preparer`, an expired link looked up again, a resume without an advertised
SHA-1, an ETag change, a tail read, the remote ABI probe, cancel keeping the part, a SHA-1 mismatch
discarding it, and a cached download never fetched again).

Then the platform tools verify the results: `apksigner verify` on every signed apk, and `zipalign -c -p 4`
to prove the stored entries (including the split's `.so` files) are page-aligned through the apksig pass.

The frame byte-layout gate (`scripts/framecheck.sh`) runs inside `build-assets.sh` before the assets are
baked, and `build-waze.sh` verifies its own output with `apksigner` + `zipalign` too.

---

## 10. Install and verify

- **Off-bike:** `scripts/test.sh` and `scripts/framecheck.sh` pass. After you install the Installer and
  run **Prepare Waze with Wazeology** → **Install Waze with Wazeology** (or install `dist/waze/*.apk`
  directly, see [Manual install](#manual-install-without-the-installer)), Waze cold-starts, and the
  Installer's **Waze on this phone** card reports Waze with Wazeology at the pinned version.
- **Motorcycle path** (the sequence that passed on a Z900 SE): open **Wazeology → Scan** (surrounding BLE
  devices list) → tap the motorcycle → accept the passkey shown on the cluster → the log shows
  `CONNECTING → BONDING → SUBSCRIBING → INITIALIZING → READY`, then `meterIndication` every 5 s. Start a
  route → `0x14` frames render on the cluster; the in-app log shows each cue and its hex (and `(dry-run)`
  frames even when no motorcycle is connected). Export via **Share**/**Copy**.
- **Repo hygiene:** `git status` shows no APK/keystore;
  `git check-ignore dist/wazeology-installer.apk apk/base.apk build/debug.keystore
  build/gen/wazeology-sign.p12 build/assets` confirms they're ignored.

## 11. Updating to a new Waze version

The patch assets are compiled against exactly one Waze build: the smali anchors, the swapped dexes, the
rebuilt manifest, the icon target path (`res/gBz.xml` is a resource-shortening artifact of that build), and
the payload dex index all encode it. Updating is a deliberate, pinned change, never a silent bump:

1. Bump `WAZE_VERSION`/`WAZE_VERSION_CODE` in `scripts/lib.sh` and `Pins.java` (and the READMEs).
2. `FORCE=1 scripts/fetch-apk.sh` to pull the new bundle, `FORCE=1 scripts/decompile.sh` to decompile it.
3. `scripts/patch.sh` and re-check every smali anchor in `patches/apply_patches.py` (method signatures,
   register budgets, the distance hook's field names); the patch script fails loudly on any anchor that
   moved.
4. `scripts/build-assets.sh` resolves the new dex layout and icon path itself, but only updates the *assertions*;
   fix `Pins.java` (`BASE_DEX_COUNT`, `PAYLOAD_DEX`, `ICON_PATH`, `HOOK_DEXES`) until the pins gate passes.
5. `scripts/test.sh` must pass; then a bike pass (§10) before trusting the field build.

Until that loop is done, the build (in either target) refuses every copy of the new Waze it is handed.
The version gate is strict on purpose, because half-matching dexes silently lose hooks.

## 12. Gotchas & troubleshooting

| Symptom | Cause / fix |
|---|---|
| Route card / some screens crash with `InflateException` | apktool rebuilt resources. The graft never ships rebuilt resources (§3/§8); the app refuses any graft whose `resources.arsc` hash changed. |
| `javac … Unable to find method metafactory` | `android.jar` on `-bootclasspath`. Put it on `-classpath` (§6). |
| Installer refuses picked files ("That's a different Waze version") | the patch matches one pinned version only (§8, §11). Let the app download it, or pick 5.23.0.2 (versionCode 1030725). |
| Download fails ("Couldn't download Waze", "APKPure isn't responding") | APKPure dropped the pinned build, changed its API, or the network blocked it. **More → Show details** has the HTTP code or the reason; what was downloaded is kept for the next try. "The download was damaged" means the SHA-1 did not match: try again. Otherwise use **Use Waze files I already have**. |
| Install says "Another Waze is in the way" or "The Waze on this phone is newer" | a differently signed Waze (or a newer one) is installed. **Remove current Waze**, then install again. |
| Install succeeds but Waze crashes on launch | should not happen: the build refuses a bundle without a native split for the device. Check the details log for the splits that were installed. |
| `adb install-multiple` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | the installed Waze has another signer (the Play build, or a build made with a different `wazeology-sign.p12`). Uninstall it first ([Manual install](#manual-install-without-the-installer)). |
| `BuildWaze` stops with `FATAL: MISSING_NATIVE` | `apk/` has no native split for `ABIS` (default `arm64-v8a`). Fetch the bundle for that processor or set `ABIS` to the split you have. |
| A new Installer release won't install over the old one | it was signed with a different `build/debug.keystore`. Build releases from the original keystore ([Keys](#keys)). |
| Motorcycle never appears in Scan | it may not advertise the service UUID; the scan surfaces all named devices too, pick by name/MAC. Confirm Bluetooth + location permissions were granted. |
| Distance shows stale/zero | ensure the distance hook is before the final `return-void`, not at method entry (§4). |

## Reference

The Kawasaki BLE5 link is reverse-engineered: GATT service `92faec07…`, control point `acf1b15c…`, 3
notify characteristics, MTU 300, bond-before-CCCD, the init sequence, `0x14` navigation / `0x13` keepalive
frames, and `0x20` ACK. It has been validated on a real cluster: a Kawasaki Z900 SE, sold as the "R Edition"
in Brazil, model year 2026. Model-year naming varies by market; in some places, Brazil among them, the model
year runs ahead of the calendar year, so this "2026" unit was the current bike back in 2025. A clean pair,
init, and `0x14` navigation frames render on the cluster, and the maneuver→icon mapping works. The link
speaks the same BLE protocol as Kawasaki's Rideology app, so it connects to any Rideology-compatible
Kawasaki cluster. Only these models can display turn-by-turn navigation on the dash, though:

| Model        | Model year |
| ------------ | ---------- |
| Ninja ZX-10R | 2026 -     |
| Z1100        | 2026 -     |
| Z900         | 2025 -     |
| Z900 (70kW)  | 2025 -     |

Even among those, the Z900 SE is the only one confirmed; the rest are expected to work but remain untested.
Source: https://www.global-kawasaki-motors.com/kawasaki_connect/en/mc.html
