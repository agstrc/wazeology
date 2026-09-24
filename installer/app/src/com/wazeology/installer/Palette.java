package com.wazeology.installer;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/**
 * Night-aware Material 3 colour tokens, resolved once from the current UI mode. A forest-green seed
 * scheme reproduced programmatically because we may not add resources (no dynamic colour, no theme
 * attributes to lean on). No new resources: every value is a literal parsed here.
 */
final class Palette {

    final boolean dark;

    final int primary;
    final int onPrimary;
    final int primaryContainer;
    final int onPrimaryContainer;
    final int surface;
    final int surfaceVariant;
    final int onSurface;
    final int onSurfaceVariant;
    final int outline;
    final int error;
    final int errorContainer;
    final int onErrorContainer;

    Palette(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        this.dark = mode == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            primary            = Color.parseColor("#9FD67D");
            onPrimary          = Color.parseColor("#133800");
            primaryContainer   = Color.parseColor("#235106");
            onPrimaryContainer = Color.parseColor("#BAF296");
            surface            = Color.parseColor("#1A1C18");
            surfaceVariant     = Color.parseColor("#22251F");
            onSurface          = Color.parseColor("#E3E3DC");
            onSurfaceVariant   = Color.parseColor("#C4C8BA");
            outline            = Color.parseColor("#8E9285");
            error              = Color.parseColor("#F2B8B5");
            errorContainer     = Color.parseColor("#8C1D18");
            onErrorContainer   = Color.parseColor("#F2B8B5");
        } else {
            primary            = Color.parseColor("#3A6A1E");
            onPrimary          = Color.parseColor("#FFFFFF");
            primaryContainer   = Color.parseColor("#BAF296");
            onPrimaryContainer = Color.parseColor("#0A2100");
            surface            = Color.parseColor("#FDFDF6");
            surfaceVariant     = Color.parseColor("#F0F3E9");
            onSurface          = Color.parseColor("#1A1C18");
            onSurfaceVariant   = Color.parseColor("#44483E");
            outline            = Color.parseColor("#74796D");
            error              = Color.parseColor("#B3261E");
            errorContainer     = Color.parseColor("#F9DEDC");
            onErrorContainer   = Color.parseColor("#410E0B");
        }
    }
}
