#!/usr/bin/env python3
"""Idempotently inject the Wazeology hooks + launcher activity into a fresh apktool decompile.

Usage: apply_patches.py <decompiled_tree>   (default: build/base_apktool)

Runs in or out of the container (pure Python, no tools). Each smali hook is anchored to its
method and guarded by a unique marker, so re-running is a no-op. Because the toolchain (apktool)
and the APK version are pinned, the decompiled smali is deterministic and these anchors are stable.

The hooks feed com.waze.debug.InstructionReporter, which forwards to com.waze.wazeology.ClusterBridge:
  onCurrentInstructionChanged(I)            -> resolve Instruction$Type.name() -> setManeuver(code,name)
  onCurrentInstructionDistanceChanged(..)   -> read distance fields            -> onDistance(m,text,unit)
  onExitNumberChanged(I)                    -> onExitNumber(exit)
  onNavigationStateChanged(ZI)              -> onNavState(navigating)
Plus a startup hook in the main launcher activity that brings the motorcycle link up whenever Waze opens:
  FreeMapAppActivity.onCreate(Bundle)       -> init() -> auto-reconnect to the saved bonded motorcycle
"""
import os
import re
import sys

NAV_SMALI = "smali_classes6/com/waze/navigate/NavigationInfoNativeManager.smali"
APP_SMALI = "smali_classes5/com/waze/FreeMapAppActivity.smali"
MANIFEST_REL = "AndroidManifest.xml"
PATCHES_DIR = os.path.dirname(os.path.abspath(__file__))

# Each hook names the smali "file" it patches (relative to the decompiled tree).
# position: "after_locals" inserts just after the method's .locals/.registers directive;
#           "before_last_return" inserts just before the method's final return-void.
HOOKS = [
    {
        "file": NAV_SMALI,
        "method": "onCurrentInstructionChanged(I)V",
        "position": "after_locals",
        "marker": "Lcom/waze/debug/InstructionReporter;->setManeuver",
        "code": """    # --- wazeology hook: maneuver ---
    invoke-static {p1}, Lcom/waze/jni/protos/navigate/Instruction$Type;->forNumber(I)Lcom/waze/jni/protos/navigate/Instruction$Type;
    move-result-object v0
    if-eqz v0, :dbg_instr_unk
    invoke-virtual {v0}, Ljava/lang/Enum;->name()Ljava/lang/String;
    move-result-object v0
    goto :dbg_instr_named
    :dbg_instr_unk
    const-string v0, "UNKNOWN"
    :dbg_instr_named
    invoke-static {p1, v0}, Lcom/waze/debug/InstructionReporter;->setManeuver(ILjava/lang/String;)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onCurrentInstructionDistanceChanged(Lcom/waze/jni/protos/navigate/DistanceUpdate;)V",
        "position": "before_last_return",
        "marker": "Lcom/waze/debug/InstructionReporter;->onDistance",
        "code": """    # --- wazeology hook: distance ---
    iget v0, p0, Lcom/waze/navigate/NavigationInfoNativeManager;->distanceMeters:I
    iget-object v1, p0, Lcom/waze/navigate/NavigationInfoNativeManager;->instructionDistance:Ljava/lang/String;
    iget-object v2, p0, Lcom/waze/navigate/NavigationInfoNativeManager;->distanceUnit:Ljava/lang/String;
    invoke-static {v0, v1, v2}, Lcom/waze/debug/InstructionReporter;->onDistance(ILjava/lang/String;Ljava/lang/String;)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onExitNumberChanged(I)V",
        "position": "after_locals",
        "marker": "Lcom/waze/debug/InstructionReporter;->onExitNumber",
        "code": """    # --- wazeology hook: exit ordinal ---
    invoke-static {p1}, Lcom/waze/debug/InstructionReporter;->onExitNumber(I)V
    # --- end hook ---
""",
    },
    {
        "file": NAV_SMALI,
        "method": "onNavigationStateChanged(ZI)V",
        "position": "after_locals",
        "marker": "Lcom/waze/debug/InstructionReporter;->onNavState",
        "code": """    # --- wazeology hook: nav state ---
    invoke-static {p1}, Lcom/waze/debug/InstructionReporter;->onNavState(Z)V
    # --- end hook ---
""",
    },
    {
        "file": APP_SMALI,
        "method": "onCreate(Landroid/os/Bundle;)V",
        "position": "after_locals",
        "marker": "Lcom/waze/debug/InstructionReporter;->init",
        "code": """    # --- wazeology hook: bring up the link when Waze opens (no active route required) ---
    invoke-static {}, Lcom/waze/debug/InstructionReporter;->init()V
    # --- end hook ---
""",
    },
]


def find_method_range(lines, method_sig):
    start = None
    for i, ln in enumerate(lines):
        s = ln.strip()
        if s.startswith(".method") and s.endswith(method_sig):
            start = i
            break
    if start is None:
        raise SystemExit("method not found: " + method_sig)
    for j in range(start + 1, len(lines)):
        if lines[j].strip() == ".end method":
            return start, j
    raise SystemExit(".end method not found for " + method_sig)


def apply_hook(lines, hook):
    start, end = find_method_range(lines, hook["method"])
    body = "".join(lines[start:end])
    if hook["marker"] in body:
        return lines, False  # already applied
    block = [l + "\n" for l in hook["code"].rstrip("\n").split("\n")]
    if hook["position"] == "after_locals":
        for i in range(start, end):
            s = lines[i].strip()
            if s.startswith(".locals ") or s.startswith(".registers "):
                lines[i + 1:i + 1] = block
                return lines, True
        raise SystemExit("no .locals in " + hook["method"])
    elif hook["position"] == "before_last_return":
        last = None
        for i in range(start, end):
            if lines[i].strip() == "return-void":
                last = i
        if last is None:
            raise SystemExit("no return-void in " + hook["method"])
        lines[last:last] = block
        return lines, True
    raise SystemExit("bad position " + hook["position"])


def patch_smali(tree):
    applied = 0
    # Group hooks by their target smali file so each file is read/written once.
    files = []
    for hook in HOOKS:
        if hook["file"] not in files:
            files.append(hook["file"])
    for rel in files:
        path = os.path.join(tree, rel)
        with open(path) as f:
            lines = f.readlines()
        touched = 0
        for hook in HOOKS:
            if hook["file"] != rel:
                continue
            lines, did = apply_hook(lines, hook)
            state = "applied" if did else "already present"
            print("  hook {:<38} {}".format(hook["method"].split("(")[0], state))
            if did:
                touched += 1
        if touched:
            with open(path, "w") as f:
                f.writelines(lines)
        applied += touched
    return applied


def patch_manifest(tree):
    path = os.path.join(tree, MANIFEST_REL)
    with open(path) as f:
        text = f.read()
    with open(os.path.join(PATCHES_DIR, "manifest-activity.xml")) as f:
        snippet = f.read().rstrip("\n")

    # Update-safe: if our activity is already present, strip it first so an edited snippet
    # (e.g. new launchMode/taskAffinity) is re-applied rather than skipped.
    existing = re.search(r"\s*<activity\b[^>]*com\.waze\.wazeology\.WazeologyActivity.*?</activity>", text, re.S)
    if existing:
        current = existing.group(0).strip()
        if current == snippet.strip():
            print("  manifest activity            already present")
            return 0
        text = text[:existing.start()] + text[existing.end():]
        action = "updated"
    else:
        action = "applied"

    m = re.search(r"<application\b[^>]*>", text)
    if not m:
        raise SystemExit("no <application> tag in manifest")
    idx = m.end()
    text = text[:idx] + "\n" + snippet + text[idx:]
    with open(path, "w") as f:
        f.write(text)
    print("  manifest activity            " + action)
    return 1


def main():
    tree = sys.argv[1] if len(sys.argv) > 1 else "build/base_apktool"
    if not os.path.isdir(tree):
        raise SystemExit("decompiled tree not found: " + tree + " (run scripts/decompile.sh)")
    print("patching " + tree)
    patch_smali(tree)
    patch_manifest(tree)
    print("done.")


if __name__ == "__main__":
    main()
