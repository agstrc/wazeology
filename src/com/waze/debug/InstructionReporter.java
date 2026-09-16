package com.waze.debug;

import com.waze.wazeology.ClusterBridge;

/**
 * Thin bridge from Waze's injected smali hooks (in NavigationInfoNativeManager) to the Kawasaki cluster.
 * Each entry point forwards to the process-wide {@link ClusterBridge}, which keeps its own in-app log.
 * All failures are swallowed so instrumentation can never affect Waze.
 */
public final class InstructionReporter {

    private InstructionReporter() {}

    /**
     * Waze main-activity startup hook: bring the motorcycle link up whenever Waze is opened, even with no
     * active route. Constructing the bridge runs its auto-reconnect to the saved bonded motorcycle; the
     * usual link-lost retry loop then keeps trying until it establishes.
     */
    public static void init() {
        try {
            ClusterBridge.peek();
        } catch (Throwable ignored) {
        }
    }

    /** onCurrentInstructionChanged hook: raw maneuver code + resolved Instruction$Type name. */
    public static void setManeuver(int code, String name) {
        try {
            ClusterBridge.maneuver(code, name);
        } catch (Throwable ignored) {
        }
    }

    /** onCurrentInstructionDistanceChanged hook: fresh distance to the current maneuver. */
    public static void onDistance(int meters, String distanceText, String unit) {
        try {
            ClusterBridge.distance(meters, distanceText, unit);
        } catch (Throwable ignored) {
        }
    }

    /** onExitNumberChanged hook: roundabout/exit ordinal for the current maneuver. */
    public static void onExitNumber(int exit) {
        try {
            ClusterBridge.exitNumber(exit);
        } catch (Throwable ignored) {
        }
    }

    /** onNavigationStateChanged hook: navigating flag (false => clear the cluster). */
    public static void onNavState(boolean navigating) {
        try {
            ClusterBridge.navState(navigating);
        } catch (Throwable ignored) {
        }
    }
}
