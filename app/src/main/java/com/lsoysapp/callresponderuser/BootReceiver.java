package com.lsoysapp.callresponderuser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Received boot completed, scheduling service start");
            // Delay service start to avoid restrictions
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent serviceIntent = new Intent(context, CallStateService.class);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Log.d(TAG, "Started CallStateService after boot");
                } catch (Exception e) {
                    Log.e(TAG, "Error starting service after boot: " + e.getMessage());
                }
            }, 30000); // 30-second delay
        }
    }
}