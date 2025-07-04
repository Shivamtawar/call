package com.lsoysapp.callresponderuser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class CallStateService extends Service {

    private TelephonyManager telephonyManager;
    private PhoneStateListener listener;

    private boolean isIncoming = false;
    private boolean callReceived = false;
    private String lastNumber = "";

    @Override
    public void onCreate() {
        super.onCreate();

        // Start foreground service
        createNotificationChannel(); // Needed for Android 8+
        Notification notification = new NotificationCompat.Builder(this, "call_channel")
                .setContentTitle("Call Monitoring Active")
                .setContentText("The app is monitoring incoming calls...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(1, notification);

        // Setup call state listener
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        listener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        isIncoming = true;
                        callReceived = false;
                        lastNumber = phoneNumber;
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        callReceived = true;
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        if (isIncoming && !callReceived) {
                            MessageHandler.sendBusyMessage(getApplicationContext(), lastNumber);
                        } else if (isIncoming && callReceived) {
                            MessageHandler.sendAfterCallMessage(getApplicationContext(), lastNumber);
                        }
                        isIncoming = false;
                        callReceived = false;
                        lastNumber = "";
                        break;
                }
            }
        };

        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (telephonyManager != null && listener != null) {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "call_channel",
                    "Call Monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
