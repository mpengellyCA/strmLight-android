package com.limelight.strmqt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.limelight.LimeLog;
import com.limelight.R;

/**
 * Optional, off by default: brings the stream back when the device wakes.
 * The system only tells a running program that the screen came on, so this
 * has to stay running to hear it.
 */
public class WakeService extends Service {
    private static final String CHANNEL = "strmqt_wake";
    private static final int NOTIFICATION_ID = 0x5714;

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            StrmQtPrefs prefs = new StrmQtPrefs(context);
            if (prefs.returnOnWake() && prefs.connectOnLaunch() && prefs.computerUuid() != null) {
                BootReceiver.bringToFront(context);
            }
        }
    };

    /** Starts or stops the service to match the setting. */
    public static void sync(Context context) {
        Intent service = new Intent(context, WakeService.class);
        try {
            if (new StrmQtPrefs(context).returnOnWake()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            } else {
                context.stopService(service);
            }
        } catch (RuntimeException e) {
            LimeLog.warning("Could not start the wake service: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, getString(R.string.strmqt_wake_notification),
                            NotificationManager.IMPORTANCE_MIN));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(getString(R.string.strmqt_wake_notification))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenOn);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
