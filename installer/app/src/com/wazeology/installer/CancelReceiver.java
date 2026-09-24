package com.wazeology.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The progress notification's Cancel action. */
public class CancelReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        JobStore store = JobStore.get(context);
        if (store.job.cancel()) {
            store.changedNow();
        }
    }
}
