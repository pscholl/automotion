package de.uni_freiburg.bgrecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

/** This receiver starts and stops a sensor recording based on the POWER status of the device:
 *
 *  1. device is plugged off power or device is rebooted and not plugged in
 *  1. delay for a few minutes and start a recording
 *  1. power is connected -> stop an ongoing recording
 *
 * Created by phil on 04.06.18.
 */

public class MainReceiver extends BroadcastReceiver {

    private static final long DELAY = 100;
    private static final String TAG =MainReceiver.class.getSimpleName();
    public static Handler mHandler = new Handler();
    public static Runnable mStart = null;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();

        Log.e(TAG, "received " + action);

        if (Intent.ACTION_POWER_CONNECTED == action) {
            // stop an ongoing recording, or a delayed start

            Intent stop = new Intent(context, RecorderService.class);
            stop.setAction(RecorderService.ACTION_STOP);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(stop);
            else
                context.startService(stop);

            Log.e(TAG, "power connected -> stopping");
        }
        else if (Intent.ACTION_POWER_DISCONNECTED == action  ||
                 Intent.ACTION_BOOT_COMPLETED == action) {
            // start a new recording

            Intent start = new Intent(context, RecorderService.class);
            start.setAction(RecorderService.ACTION_STRT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(start);
            else
                context.startService(start);

            Log.e(TAG, "power disconnected -> starting");
        }
    }
}
