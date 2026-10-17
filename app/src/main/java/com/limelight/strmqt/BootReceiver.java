package com.limelight.strmqt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.limelight.LimeLog;

/**
 * Optional, off by default: opens the stream when the device has booted.
 *
 * Starting an activity from the background needs the "display over other
 * apps" permission on current Fire OS; see docs/strmqt-fork.md.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        StrmQtPrefs prefs = new StrmQtPrefs(context);
        WakeService.sync(context);
        if (prefs.startOnBoot() && prefs.connectOnLaunch() && prefs.computerUuid() != null) {
            bringToFront(context);
        }
    }

    /** The same thing as choosing the app from the home screen. */
    static void bringToFront(Context context) {
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, LaunchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(launch);
        } catch (RuntimeException e) {
            LimeLog.warning("Could not open the stream from the background: " + e);
        }
    }
}
