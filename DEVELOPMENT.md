# Development

How to build wazeology from source. The overview comes first, and everything below it walks through how the
patch actually works end to end: every non-obvious decision, the traps to watch for, and the reasons the
pipeline is shaped the way it is. It is written so that a later maintainer, human or agent, can rebuild or
extend the patch without stepping on the same landmines again. For user-facing install and usage see
[`README.md`](README.md), and for the working rules in this repo see [`CLAUDE.md`](CLAUDE.md).

## Requirements

Everything runs inside the pinned toolchain container (`docker/Dockerfile`). The host only needs Docker to
build, plus an Android device to sideload the finished apk onto. Nothing else, not apktool, the Android SDK,
or apkeep, gets installed on the host. Every script sources `scripts/lib.sh` and invokes its tools through
`run_tools`.

Pinned versions, kept in sync between `scripts/lib.sh` and `docker/Dockerfile`: Waze 5.23.0.2 (versionCode
1030725), apktool 2.10.0, Android build-tools 34.0.0, platform android-34, apkeep 1.0.0, APKEditor 1.4.9
(the split bundling in §11), and JDK 17.

## Build pipeline

```bash
scripts/build-image.sh   # once: build the pinned toolchain image (~1.5 GB first time)
scripts/fetch-apk.sh     # fetch the pinned Waze into apk/ (gitignored), skipping if already present
scripts/decompile.sh     # apktool d into build/base_apktool, skipping if already present
scripts/patch.sh         # inject 4 smali hooks + the launcher <activity>
scripts/framecheck.sh    # off-bike frame byte-layout test (build.sh also runs this as a gate)
scripts/build.sh         # compile the Wazeology package -> dex, graft onto the pristine base, then
                         # bundle the base + splits into one signed apk (./wazeology.apk), §11
```

`scripts/all.sh` chains `fetch` through `build` and leaves the finished `./wazeology.apk` at the repo root to
sideload onto the device (§8). Each step is documented in detail, with its rationale, in the sections
below. `fetch-apk.sh` and `decompile.sh` skip their work when their outputs are already in the workspace.
Setting `FORCE=1` (e.g. `FORCE=1 scripts/all.sh`) re-downloads and re-decompiles from scratch.

After editing Java in `src/`, keep it Java 8-compatible and Android-framework-only (no Kotlin, no AppCompat,
no new resources). Validate frame-builder changes with `scripts/framecheck.sh` (a host-JVM byte-layout test).

---

# The full mechanism

---

## 0. The mental model: Waze ships as a split bundle, we ship one apk

Modern Play apps ship as an **App Bundle**, delivered to the device as several APKs that share one identity:

- `base.apk`: the code (`classes*.dex`), `AndroidManifest.xml`, `resources.arsc`, and default resources.
- `split_config.arm64_v8a.apk`: native libraries for the device ABI.
- `split_config.xxxhdpi.apk`: density-specific resources.
- `split_config.pt.apk`: a language config split (third-party library strings; Waze's own strings are in base).

apkeep hands us the base and all these config splits (§1). They are the input to this build and its
intermediate stage. We patch only **code + manifest**, which live in `base.apk`, and leave the splits' own
contents alone. The last step (§11) bundles the patched base and the splits into one standalone apk,
`./wazeology.apk` at the repo root. That bundled apk is the repository's only artifact, which you sideload
onto the device (§8).

---

## 1. Fetch the APK (never committed) — `scripts/fetch-apk.sh`

We never store Waze in git. `apkeep` (EFF's downloader) fetches the **pinned** version inside the container:

```
apkeep -a com.waze@5.23.0.2 -d apk-pure  apk/_dl
```

- **apk-pure** (default) needs no credentials and returns a split app as an **XAPK** (a zip of `base` +
  `config.*` APKs). `scripts/normalize_apks.py` unpacks it and classifies each inner APK by content — the
  base is the one that contains `classes.dex`; the rest are splits — writing `apk/base.apk` +
  `apk/split_config.*.apk`.
- **google-play** (`APK_SOURCE=google-play` + `GOOGLE_EMAIL`/`AAS_TOKEN` in `.env`) yields byte-exact store
  APKs but needs an account and an AAS token. Version is pinned by **versionCode** there, not versionName.

Reproducibility caveat: mirror bytes (apk-pure) may differ from Play. For byte-exact inputs use google-play.
Either way the *inputs* are pinned to one version; the *outputs* differ only by the local signing key.

Verify identity: `aapt2 dump badging apk/base.apk` → `package: name='com.waze' versionName='5.23.0.2'`.

The download is slow, so `fetch-apk.sh` skips it when `apk/base.apk` and the `apk/split_config.*.apk` are
already in the workspace. Run `FORCE=1 scripts/fetch-apk.sh` to re-download.

---

## 2. Decompile — `scripts/decompile.sh`

```
apktool d --force apk/base.apk -o build/base_apktool
```

`decompile.sh` skips this when `build/base_apktool` already exists, so patches you have applied to the tree
survive a re-run of the pipeline. Run `FORCE=1 scripts/decompile.sh` to discard it and decompile afresh.

This baksmalis every `classes*.dex` into `smali/`, `smali_classes2/` … and decodes resources + the binary
manifest into editable form. We edit **smali** (code) and **AndroidManifest.xml** here. We will **not** ship
apktool's rebuilt resources (see §3). `apktool.yml` records `minSdk 32`, `targetSdk 36`, `forcedPackageId
127` — needed so apktool reassembles with the original resource IDs.

Note `smali_classesN/` ↔ `classesN.dex` is 1:1: the classes that were in `classes6.dex` baksmali into
`smali_classes6/`, and rebuild back into `classes6.dex`. This is why we can graft a single rebuilt
`classes6.dex` back onto the pristine APK (§7).

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
AndroidX drawable — a selector `<item>` lost its `drawable` attribute. It's a silent semantic corruption,
not a build error, and it only surfaces on a screen that inflates that drawable (the route card's Switch).

**Fix / architecture:** never let aapt2 recompile the resource XML. Start from the **pristine `base.apk`**
and swap in only patched **code + manifest**, keeping every `res/*` file **byte-identical**. That is the
"graft" in §7, and `scripts/graft.py` asserts the grafted base's `resources.arsc` SHA is unchanged. The
bundling step (§11) then merges the split resource tables at the binary level. `resources.arsc` changes, but
no resource XML is recompiled, so the rule still holds in the shipped apk.

---

## 4. Smali hooks — reading Waze's live guidance — `patches/apply_patches.py`

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
- The reassembled `smali_classes6/` becomes `classes6.dex`, which the graft (§7) swaps in.

`InstructionReporter` (in `src/com/waze/debug/`) is the tiny bridge the hooks call; it forwards to the
process-wide `ClusterBridge` singleton and swallows every exception so instrumentation can never crash Waze.

---

## 5. Adding a screen without touching resources — the manifest graft

Wazeology needs a UI. An Activity must be declared in the manifest, but we must not rebuild resources. So:

- **Permissions:** none added — Waze already declares `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, legacy
  `BLUETOOTH`/`ADMIN`, `ACCESS_FINE/COARSE/BACKGROUND_LOCATION`, `FOREGROUND_SERVICE(+_LOCATION)`, `INTERNET`.
  A BLE central/GATT client needs nothing more. (It bundles a BLE *scanner* but no GATT/bond client — that's
  what we add.)
- **One `<activity>`** (`patches/manifest-activity.xml`) is inserted after `<application …>` with a
  `MAIN`/`LAUNCHER` intent-filter → a **second launcher icon** "Wazeology", same process as Waze. It sets
  **no `android:theme`** (inherits `Theme.Main`) and its only resource reference is
  `android:icon="@mipmap/launch_icon_round"` — an **existing** resource id (see §5.1), so it introduces
  **no new resource id**. That is what makes the graft safe: apktool preserves resource IDs
  (forcedPackageId 127, no new resources), so the rebuilt binary manifest's references still resolve against
  the untouched pristine `resources.arsc`.
- **The UI is fully programmatic** (`WazeologyActivity` builds `LinearLayout`/`Button`/`TextView` in code, uses
  only `android.R.*`). No layout XML, no AppCompat, no new drawables/strings → resources stay pristine.

No foreground `Service` is used (Waze's manifest lacks `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and adding it
would mean touching permissions). Instead `ClusterBridge` is a **process-wide singleton** obtained via
reflection (`ActivityThread.currentApplication()`), so the smali hooks can feed it even before the screen is
opened. The BLE link lives as long as the Waze process. A saved, bonded motorcycle is waited for **passively**
(an `autoConnect=true` GATT handle, so the stack links up whenever the bike appears, with no retry timer);
"Connect" forces one direct attempt, "Disconnect" pauses the wait until the next launch, and "Forget" drops it.

### 5.1 A distinct launcher icon without a new resource

Wazeology gets its own launcher art (Rideology's green slash + custom "R" on Waze blue) — but the golden
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
- **The replacement references no app resource id.** `patches/wazeology-icon.xml` is a *self-contained*
  `<adaptive-icon>`: both background (Waze blue `#33ccff`) and foreground (the mark) are **inline `<vector>`s**,
  so it uses only framework (`android:*`) attributes. `build.sh` compiles it standalone with `aapt2`
  (compile + link against `android.jar`), and `graft.py --res-sub res/gBz.xml=…` drops the compiled binary
  XML over the orphan entry. Because it names no app id, nothing needs to be relinked against the pristine
  `resources.arsc`.

The mark art itself is the Rideology vector logo (extracted from the Rideology APK), scaled/centred on the
108×108 adaptive grid via a `<group>` transform, with the "R" recoloured white.

---

## 6. Compile the Wazeology package to a dex — `scripts/build.sh`

```
javac -source 8 -target 8 -cp /opt/android-sdk/platforms/android-34/android.jar src/com/**/*.java
d8 --min-api 32 --output … build/gen/cls/**.class     # -> build/gen/pkg.dex
```

Two gotchas:

- **`android.jar` goes on `-classpath`, NOT `-bootclasspath`.** The Android stub `android.jar` has no
  `java.lang.invoke.LambdaMetafactory`; with it as bootclasspath, compiling any lambda fails
  (`Unable to find method metafactory`). Putting it on the classpath lets the JDK supply `java.*`
  (lambdas compile) while `android.*` resolves from the stub. `d8` desugars the lambdas for `--min-api 32`.
- **Java 8 / framework-only.** The Wazeology package is deliberately plain Java (no Kotlin → no `kotlin-stdlib`
  bloating the dex) and uses only the Android framework, so the dex is tiny (~55 KB) and self-contained.

Before compiling, `build.sh` runs `scripts/framecheck.sh` — a host-JVM test that asserts the exact byte
layout of `turnByTurn` / `meterIndication` / `phoneName` / `initSequence` / `isResponseTo`. This catches any
regression in the frame builders without hardware.

---

## 7. Assemble — graft onto the pristine base — `scripts/graft.py`

`apktool b build/base_apktool` produces an APK we use **only** as a source of two entries: the rebuilt binary
`AndroidManifest.xml` (with our activity) and the patched `classes6.dex` (with our hooks). Its rebuilt
resources are **discarded**.

`graft.py` then rewrites a copy of the **pristine `apk/base.apk`**, entry by entry, preserving each entry's
compression, and:

- replaces `AndroidManifest.xml` with the rebuilt binary manifest,
- replaces `classes6.dex` with the rebuilt one (stored uncompressed),
- appends the Wazeology dex as the **next contiguous** `classesN.dex` (currently `classes11.dex`) stored
  uncompressed — ART loads every `classesN.dex` from the base APK, so no code references it explicitly; it
  just needs to exist and be contiguous (`classes.dex, classes2 … classesN`, no gaps),
- optionally overwrites the **bytes** of specific existing `res/*` entries via `--res-sub ZIPPATH=FILE`
  (used for the launcher icon, §5.1) — same path, so `resources.arsc` is unaffected; asserts each target
  path existed,
- keeps `resources.arsc` and all other `res/*` **verbatim**, then **asserts the `resources.arsc` SHA is
  unchanged** (the `--res-sub` overwrites change only res file *content*, never the table).

---

## 8. Sign and install the bundled apk (`scripts/build.sh`)

The graft (§7) leaves an unsigned intermediate `build/gen/base.apk`. `build.sh` (§11) bundles it with the
splits, aligns, and signs the result into `./wazeology.apk` at the repo root:

```
zipalign -p -f 4 …                       # 4-byte align; -p page-aligns stored .so
apksigner sign --ks build/debug.keystore … wazeology.apk        # v2/v3
```

- The debug keystore is generated once (`ensure_keystore` in `lib.sh`) and reused, so its signature is stable
  on a machine, and a reinstall needs no uninstall.
- Installing is a manual step. Sideload `./wazeology.apk` onto the device: copy it over and open it with the
  device's package installer, or run `adb install ./wazeology.apk` from any machine that has adb (the
  toolchain image does not include adb).
- Installing over a Waze signed with Google's key fails with a signature mismatch, so uninstall the Play Waze
  first (e.g. `adb uninstall com.waze`, or remove it from the device). You lose its login and data, which is
  expected for a debug build.

---

## 9. Verify

- **Off-bike:** `scripts/framecheck.sh` passes (frame byte layouts). After you install `./wazeology.apk`, Waze cold-starts.
- **Motorcycle path** (the sequence that passed on a Z900 SE): open **Wazeology → Scan** (surrounding BLE devices
  list) → tap the motorcycle → accept the passkey shown on the cluster → the log shows
  `CONNECTING → BONDING → SUBSCRIBING → INITIALIZING → READY`, then `meterIndication` every 5 s. Start a route →
  `0x14` frames render on the cluster; the in-app log shows each cue and its hex (and `(dry-run)` frames even when
  no motorcycle is connected). Export via **Share**/**Copy**.
- **Repo hygiene:** `git status` shows no APK/keystore; `git check-ignore wazeology.apk apk/base.apk build/debug.keystore`
  confirms they're ignored.

---

## 10. Gotchas & troubleshooting

| Symptom | Cause / fix |
|---|---|
| Route card / some screens crash with `InflateException` | apktool rebuilt resources. Use the graft (§3/§7); never ship rebuilt resources. |
| `javac … Unable to find method metafactory` | `android.jar` on `-bootclasspath`. Put it on `-classpath` (§6). |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch | a differently-signed Waze is installed. Uninstall the existing Waze first (§8). |
| Motorcycle never appears in Scan | it may not advertise the service UUID; the scan surfaces all named devices too — pick by name/MAC. Confirm Bluetooth + location permissions were granted. |
| Distance shows stale/zero | ensure the distance hook is before the final `return-void`, not at method entry (§4). |

---

## 11. Bundle the base and splits into one apk (`scripts/build.sh`)

The graft (§7) produces the patched base. The last step bundles it with the config splits into one standalone
apk, `./wazeology.apk` at the repo root. That apk carries the base code and manifest, the arm64 native libs,
and the density and language resources under one `resources.arsc`, and you sideload it onto the device (§8).

Bundling means merging the split resource tables into one, which sounds like what the golden rule (§3)
forbids. It is not: the rule's constraint is that aapt2 must not recompile the resource XML, because that is
what corrupts the route-card drawable. Changing `resources.arsc` is fine.

`build.sh` runs **APKEditor** (`apkeditor m`, ARSCLib), which merges the base and split resource tables at the
binary level and copies every `res/*` entry verbatim. It never invokes aapt2 on resource XML, so the
corruption cannot happen: the `abc_switch_thumb_material.xml` binary is byte-identical before and after.
APKEditor also sanitizes the manifest (it drops `requiredSplitTypes`, `isSplitRequired`, the
`com.android.vending.splits` metadata, and `res/xml/splits0.xml`) and sets `extractNativeLibs=false`, so the
OS accepts the result as a standalone apk.

`LANGS` selects which language splits to bundle. The default, `all`, bundles every language apkeep fetched.
Set it to a space-separated list to bundle fewer, for example `LANGS="pt en" scripts/build.sh`. The ABI and
density splits are always bundled. A Play install on a device carries only that device's languages, so a
subset is closer to a Play install, though the size difference is small: the language splits are tiny next to
the base and the native libs.

`build.sh` ends with a proof gate (`scripts/verify_merge.py`). It diffs the final apk's `res/*` against the
pristine `apk/base.apk` and fails unless the only changed entries are the ones the graft intentionally patched
(the launcher icon, §5.1) and the only dropped entry is `res/xml/splits0.xml`. That check confirms the merge
rebuilt the resource table while leaving every resource file verbatim. It also asserts the native `.so` are
STORED (uncompressed, required by `extractNativeLibs=false`) and that `classes6.dex` (hooks) and the Wazeology
dex survived.

The bundled apk is statically verified: structure, signature, and the resource gate above. It has not been
re-validated end to end on the cluster, so do a bike pass (§9) before trusting it in the field.

## Reference

The Kawasaki BLE5 link is reverse-engineered: GATT service `92faec07…`, control point `acf1b15c…`, 3
notify characteristics, MTU 300, bond-before-CCCD, the init sequence, `0x14` navigation / `0x13` keepalive
frames, and `0x20` ACK. It has been validated on a real cluster: a Kawasaki Z900 SE, sold as the "R Edition"
in Brazil, model year 2026. Model-year naming varies by market; in some places, Brazil among them, the model
year runs ahead of the calendar year, so this "2026" unit was the current bike back in 2025. A clean pair,
init, and `0x14` navigation frames render on the cluster, and the maneuver→icon mapping works. Since the link
speaks the same BLE protocol as Kawasaki's Rideology app, it should generally work on any Rideology-compatible
Kawasaki. The Z900 SE is the only one confirmed, though; other models and years are expected to work but
remain untested.
