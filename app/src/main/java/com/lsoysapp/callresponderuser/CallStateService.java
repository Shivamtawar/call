package com.lsoysapp.callresponderuser;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CallStateService extends Service {

    private static final String TAG = "CallStateService";
    private static final int NOTIFICATION_ID = 1;
    private static final int PERMISSION_NOTIFICATION_ID = 2;
    private static final String WORK_NAME = "CallStateServiceCheck";
    private static final String ALARM_ACTION = "com.lsoysapp.callresponderuser.ALARM_RESTART";
    private static final String PREFS_NAME = "CallResponderPrefs";
    private DatabaseReference mDatabase;
    private String currentUserId;
    private boolean isSubscribed = false;
    private boolean isFreeTrialActive = false;
    private boolean isUserStatusCached = false;
    private final HashMap<Integer, TelephonyManager> telephonyManagers = new HashMap<>();
    private final HashMap<Integer, CallStateListener> callStateListeners = new HashMap<>();
    private static final long MIN_CALL_DURATION = 5000; // 5 seconds

    // ENHANCED: Better per-SIM tracking with explicit answered state
    private static class SimCallState {
        String activePhoneNumber = null;
        long callStartTime = 0;
        boolean isIncomingCall = false;
        boolean wasRinging = false;
        boolean callWasAnswered = false;
        int callState = TelephonyManager.CALL_STATE_IDLE;
        long lastProcessedTime = 0;
        String lastProcessedCall = "";

        void reset() {
            activePhoneNumber = null;
            callStartTime = 0;
            isIncomingCall = false;
            wasRinging = false;
            callWasAnswered = false;
            callState = TelephonyManager.CALL_STATE_IDLE;
        }

        boolean hasActiveCall() {
            return activePhoneNumber != null && callStartTime > 0;
        }
    }

    private final HashMap<Integer, SimCallState> simCallStates = new HashMap<>();
    private static final long CALL_PROCESSING_COOLDOWN = 3000;

    private static final HashMap<String, CallInfo> globalCallTracker = new HashMap<>();
    private static final Object globalCallLock = new Object();

    private static class CallInfo {
        int ownerSubscriptionId;
        long startTime;
        boolean isIncoming;
        boolean wasAnswered;

        CallInfo(int subscriptionId, boolean isIncoming) {
            this.ownerSubscriptionId = subscriptionId;
            this.startTime = System.currentTimeMillis();
            this.isIncoming = isIncoming;
            this.wasAnswered = false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate called");
        try {
            loadCachedUserStatus();
            setupForegroundService();
            initializeFirebase();
            checkPermissions();
            registerCallStateListeners();
            registerPermissionRevokedReceiver();
            scheduleServiceCheck();
            scheduleAlarmRestart();
            checkBatteryOptimization();
            showOemSettingsNotification();
            MessageHandler.logDualSimInfo(this);
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            Toast.makeText(this, "Service initialization error", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand called with action: " + (intent != null ? intent.getAction() : "null"));
        if (intent != null && ALARM_ACTION.equals(intent.getAction())) {
            Log.d(TAG, "Service restarted by AlarmManager");
            checkPermissions();
        }
        return START_STICKY;
    }

    private void setupForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "call_channel",
                        "Call Monitoring",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Monitors calls for auto-replies");
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                channel.enableLights(true);
                channel.enableVibration(false);
                android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }

            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, "call_channel")
                    .setContentTitle("Call Responder Active")
                    .setContentText("Monitoring calls for auto-replies")
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build();
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "Foreground service started with ID: " + NOTIFICATION_ID);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up foreground service: " + e.getMessage(), e);
        }
    }

    private void initializeFirebase() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                currentUserId = user.getUid();
                mDatabase = FirebaseDatabase.getInstance().getReference();
                checkUserStatusWithRetry(3, 2000);
                Log.d(TAG, "Firebase initialized for user: " + currentUserId);
            } else {
                Log.w(TAG, "No authenticated user found");
                showPermissionNotification("Authentication Required", "Please log in to continue using Call Responder.");
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Firebase: " + e.getMessage(), e);
            if (isUserStatusCached) {
                Log.d(TAG, "Using cached user status");
            } else {
                stopSelf();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void registerCallStateListeners() {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted");
                showPermissionNotification("Permission Required", "Please grant phone state permission to enable call monitoring.");
                stopSelf();
                return;
            }

            SubscriptionManager subscriptionManager = SubscriptionManager.from(this);
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();

            if (subscriptions != null && !subscriptions.isEmpty()) {
                Log.d(TAG, "🔍 Found " + subscriptions.size() + " active SIM subscriptions");

                for (SubscriptionInfo info : subscriptions) {
                    int subscriptionId = info.getSubscriptionId();
                    String simDisplayName = MessageHandler.getSimDisplayName(this, subscriptionId);

                    TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                    if (telephonyManager != null) {
                        telephonyManager = telephonyManager.createForSubscriptionId(subscriptionId);
                    }
                    if (telephonyManager == null) {
                        Log.e(TAG, "❌ Failed to create TelephonyManager for subscription " + subscriptionId);
                        continue;
                    }

                    telephonyManagers.put(subscriptionId, telephonyManager);
                    simCallStates.put(subscriptionId, new SimCallState());

                    CallStateListener listener = new CallStateListener(subscriptionId);
                    callStateListeners.put(subscriptionId, listener);
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);

                    Log.d(TAG, "✅ Registered call state listener for subscription ID: " + subscriptionId + " (" + simDisplayName + ")");
                }
            } else {
                Log.w(TAG, "No active SIM subscriptions found, using default");
                TelephonyManager defaultTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (defaultTelephonyManager == null) {
                    Log.e(TAG, "Default TelephonyManager is null");
                    showPermissionNotification("Telephony Error", "Telephony service unavailable. Please restart the app.");
                    stopSelf();
                    return;
                }

                int defaultSubId = SubscriptionManager.getDefaultSubscriptionId();
                telephonyManagers.put(defaultSubId, defaultTelephonyManager);
                simCallStates.put(defaultSubId, new SimCallState());

                CallStateListener listener = new CallStateListener(defaultSubId);
                callStateListeners.put(defaultSubId, listener);
                defaultTelephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);

                Log.d(TAG, "✅ Registered call state listener for default SIM (ID: " + defaultSubId + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering call state listeners: " + e.getMessage(), e);
            stopSelf();
        }
    }

    private void checkUserStatusWithRetry(int retryCount, long delay) {
        if (mDatabase == null || currentUserId == null) {
            if (isUserStatusCached) {
                Log.d(TAG, "Using cached user status due to null database or user ID");
                return;
            }
            stopSelf();
            return;
        }

        mDatabase.child("users").child(currentUserId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                try {
                    if (dataSnapshot.exists()) {
                        Boolean enabled = dataSnapshot.child("enabled").getValue(Boolean.class);
                        if (enabled == null || !enabled) {
                            Log.w(TAG, "User access disabled by admin");
                            showPermissionNotification("Access Disabled", "Your account has been disabled by the admin.");
                            stopSelf();
                            return;
                        }

                        String freeTrialStatus = dataSnapshot.child("freeTrialStatus").getValue(String.class);
                        isFreeTrialActive = "active".equals(freeTrialStatus);

                        Boolean subscribed = dataSnapshot.child("subscription").child("isSubscribed").getValue(Boolean.class);
                        isSubscribed = subscribed != null && subscribed;

                        cacheUserStatus(isSubscribed, isFreeTrialActive);
                        Log.d(TAG, "User status updated: subscribed=" + isSubscribed + ", freeTrialActive=" + isFreeTrialActive);
                        if (!isSubscribed && !isFreeTrialActive) {
                            Log.w(TAG, "User is neither subscribed nor in free trial");
                            showPermissionNotification("Subscription Required", "Please subscribe or activate free trial to continue.");
                            stopSelf();
                        }
                    } else {
                        Log.w(TAG, "User data not found");
                        stopSelf();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error checking user status: " + e.getMessage(), e);
                    if (isUserStatusCached) {
                        Log.d(TAG, "Using cached user status due to error");
                    } else {
                        stopSelf();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Failed to check user status: " + databaseError.getMessage());
                if (retryCount > 0) {
                    Log.d(TAG, "Retrying user status check, attempts left: " + (retryCount - 1));
                    new Handler(Looper.getMainLooper()).postDelayed(() ->
                            checkUserStatusWithRetry(retryCount - 1, delay * 2), delay);
                } else if (isUserStatusCached) {
                    Log.d(TAG, "Using cached user status after retries exhausted");
                } else {
                    showPermissionNotification("Network Error", "Failed to verify user status. Please check your internet connection.");
                    stopSelf();
                }
            }
        });
    }

    private void cacheUserStatus(boolean subscribed, boolean freeTrialActive) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putBoolean("isSubscribed", subscribed)
                .putBoolean("isFreeTrialActive", freeTrialActive)
                .putLong("cacheTimestamp", System.currentTimeMillis())
                .apply();
        isUserStatusCached = true;
        Log.d(TAG, "Cached user status: subscribed=" + subscribed + ", freeTrialActive=" + freeTrialActive);
    }

    private void loadCachedUserStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isSubscribed = prefs.getBoolean("isSubscribed", false);
        isFreeTrialActive = prefs.getBoolean("isFreeTrialActive", false);
        long cacheTimestamp = prefs.getLong("cacheTimestamp", 0);
        long cacheAge = System.currentTimeMillis() - cacheTimestamp;
        isUserStatusCached = (isSubscribed || isFreeTrialActive) && cacheAge < TimeUnit.DAYS.toMillis(7);
        Log.d(TAG, "Loaded cached user status: subscribed=" + isSubscribed + ", freeTrialActive=" + isFreeTrialActive + ", cached=" + isUserStatusCached);
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.w(TAG, "Battery optimization is enabled, prompting user to disable");
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    showPermissionNotification("Battery Optimization", "Please disable battery optimization for Call Responder to work reliably.");
                } catch (Exception e) {
                    Log.e(TAG, "Error starting battery optimization intent: " + e.getMessage(), e);
                }
            } else {
                Log.d(TAG, "Battery optimization is disabled");
            }
        }
    }

    private void showOemSettingsNotification() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean shown = prefs.getBoolean("oem_notification_shown", false);
        if (!shown) {
            showPermissionNotification("Device Settings Required", "Please enable 'Auto-start' and 'Allow background activity' in your device settings for reliable call monitoring.");
            prefs.edit().putBoolean("oem_notification_shown", true).apply();
        }
    }

    private void checkPermissions() {
        boolean missingPermissions = false;
        StringBuilder missing = new StringBuilder();
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE permission missing");
            missing.append("Phone State, ");
            missingPermissions = true;
        }
        if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG permission missing");
            missing.append("Call Log, ");
            missingPermissions = true;
        }
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS permission missing");
            missing.append("Send SMS, ");
            missingPermissions = true;
        }
        if (missingPermissions) {
            String message = "Please grant the following permissions in app settings: " + missing.toString().substring(0, missing.length() - 2);
            showPermissionNotification("Permissions Required", message);
            stopSelf();
        } else {
            Log.d(TAG, "All required permissions granted");
        }
    }

    private void showPermissionNotification(String title, String message) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "permission_channel",
                        "Permission Alerts",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Alerts for missing permissions or settings");
                channel.enableLights(true);
                channel.enableVibration(true);
                android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }

            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, "permission_channel")
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .build();

            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(PERMISSION_NOTIFICATION_ID, notification);
                Log.d(TAG, "Displayed notification: " + title + " - " + message);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing permission notification: " + e.getMessage(), e);
        }
    }

    private void scheduleServiceCheck() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest serviceCheckRequest =
                new PeriodicWorkRequest.Builder(ServiceCheckWorker.class, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .addTag(WORK_NAME)
                        .setInitialDelay(5, TimeUnit.MINUTES)
                        .build();

        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, serviceCheckRequest);
        Log.d(TAG, "Scheduled periodic service check with constraints");
    }

    private void scheduleAlarmRestart() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, CallStateService.class);
            intent.setAction(ALARM_ACTION);
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            long triggerTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30);
            alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    TimeUnit.MINUTES.toMillis(30),
                    pendingIntent
            );
            Log.d(TAG, "Scheduled AlarmManager for service restart every 30 minutes");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling AlarmManager: " + e.getMessage(), e);
        }
    }

    public static class ServiceCheckWorker extends Worker {
        public ServiceCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            try {
                Context context = getApplicationContext();
                Log.d(TAG, "Checking if CallStateService is running");
                if (!isServiceRunning(context, CallStateService.class)) {
                    Log.w(TAG, "CallStateService not running, restarting...");
                    Intent serviceIntent = new Intent(context, CallStateService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Toast.makeText(context, "Restarted call monitoring service", Toast.LENGTH_SHORT).show();
                    return Result.success();
                } else {
                    Log.d(TAG, "CallStateService is running");
                    return Result.success();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in ServiceCheckWorker: " + e.getMessage(), e);
                return Result.retry();
            }
        }

        private boolean isServiceRunning(Context context, Class<?> serviceClass) {
            android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
            return false;
        }
    }

    private class CallStateListener extends PhoneStateListener {
        private final int subscriptionId;

        CallStateListener(int subscriptionId) {
            this.subscriptionId = subscriptionId;
            Log.d(TAG, "🎯 Created CallStateListener for subscriptionId: " + subscriptionId + " (" + MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId) + ")");
        }

        @Override
        public void onCallStateChanged(int state, String phoneNumber) {
            super.onCallStateChanged(state, phoneNumber);
            try {
                if (!isSubscribed && !isFreeTrialActive) {
                    Log.w(TAG, "User not subscribed or in trial, ignoring call state change");
                    return;
                }

                String simInfo = MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId);
                Log.d(TAG, "🔔 [SIM " + subscriptionId + " - " + simInfo + "] Call state: " + getCallStateName(state) + ", phone: " + phoneNumber);

                SimCallState simState = simCallStates.get(subscriptionId);
                if (simState == null) {
                    simState = new SimCallState();
                    simCallStates.put(subscriptionId, simState);
                }

                int previousState = simState.callState;
                simState.callState = state;

                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        handleRingingState(phoneNumber, simState);
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        handleOffhookState(phoneNumber, simState, previousState);
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        handleIdleState(simState);
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in call state listener for SIM " + subscriptionId + ": " + e.getMessage(), e);
            }
        }

        private void handleRingingState(String phoneNumber, SimCallState simState) {
            synchronized (globalCallLock) {
                try {
                    Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING RINGING: " + phoneNumber);

                    CallInfo existingCall = globalCallTracker.get(phoneNumber);
                    if (existingCall != null && existingCall.ownerSubscriptionId != subscriptionId) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Call from " + phoneNumber +
                                " already handled by SIM " + existingCall.ownerSubscriptionId + ", ignoring");
                        return;
                    }

                    globalCallTracker.put(phoneNumber, new CallInfo(subscriptionId, true));

                    simState.activePhoneNumber = phoneNumber;
                    simState.callStartTime = System.currentTimeMillis();
                    simState.isIncomingCall = true;
                    simState.wasRinging = true;
                    simState.callWasAnswered = false;

                    Log.d(TAG, "✅ [SIM " + subscriptionId + "] CLAIMED OWNERSHIP - Incoming call from: " + phoneNumber);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error handling ringing state for SIM " + subscriptionId + ": " + e.getMessage(), e);
                }
            }
        }

        private void handleOffhookState(String phoneNumber, SimCallState simState, int previousState) {
            synchronized (globalCallLock) {
                try {
                    if (simState.wasRinging && simState.isIncomingCall &&
                            phoneNumber.equals(simState.activePhoneNumber)) {

                        CallInfo callInfo = globalCallTracker.get(phoneNumber);
                        if (callInfo == null || callInfo.ownerSubscriptionId != subscriptionId) {
                            Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Incoming call ownership mismatch for " + phoneNumber);
                            return;
                        }

                        Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING ANSWERED: " + phoneNumber);
                        simState.callWasAnswered = true;
                        callInfo.wasAnswered = true;

                    } else {
                        if (previousState == TelephonyManager.CALL_STATE_IDLE) {
                            CallInfo existingCall = globalCallTracker.get(phoneNumber);
                            if (existingCall != null && existingCall.ownerSubscriptionId != subscriptionId) {
                                Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Outgoing call to " + phoneNumber +
                                        " already handled by SIM " + existingCall.ownerSubscriptionId + ", ignoring");
                                return;
                            }

                            CallInfo newCall = new CallInfo(subscriptionId, false);
                            newCall.wasAnswered = true;
                            globalCallTracker.put(phoneNumber, newCall);

                            Log.d(TAG, "📞 [SIM " + subscriptionId + "] OUTGOING STARTED: " + phoneNumber);

                            simState.activePhoneNumber = phoneNumber;
                            simState.callStartTime = System.currentTimeMillis();
                            simState.isIncomingCall = false;
                            simState.wasRinging = false;
                            simState.callWasAnswered = true;
                        } else {
                            Log.d(TAG, "⚠️ [SIM " + subscriptionId + "] OFFHOOK state change but not a new outgoing call. Previous state: " + getCallStateName(previousState));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error handling offhook state for SIM " + subscriptionId + ": " + e.getMessage(), e);
                }
            }
        }


        private void handleIdleState(SimCallState simState) {
            synchronized (globalCallLock) {
                try {
                    if (!simState.hasActiveCall()) {
                        Log.d(TAG, "ℹ️ [SIM " + subscriptionId + "] No active call to process in IDLE state");
                        return;
                    }

                    String phoneNumber = simState.activePhoneNumber;
                    CallInfo callInfo = globalCallTracker.get(phoneNumber);
                    if (callInfo == null || callInfo.ownerSubscriptionId != subscriptionId) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Call ownership lost for " + phoneNumber + ", ignoring idle state");
                        simState.reset();
                        return;
                    }

                    long callDuration = System.currentTimeMillis() - simState.callStartTime;

                    Log.d(TAG, "📞 [SIM " + subscriptionId + "] Call ended: " + phoneNumber +
                            ", duration: " + callDuration + "ms" +
                            ", wasAnswered: " + simState.callWasAnswered +
                            ", wasRinging: " + simState.wasRinging +
                            ", isIncoming: " + simState.isIncomingCall);

                    if (simState.isIncomingCall) {
                        // For incoming calls, always check the call log to determine the actual outcome
                        // Add a small delay to allow call log to be updated
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            int callType = getCallTypeFromLog(phoneNumber);
                            int callDurationFromLog = getCallDurationFromLog(phoneNumber);

                            Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING CALL ANALYSIS: " + phoneNumber +
                                    ", callType from log: " + getCallTypeName(callType) +
                                    ", duration from log: " + callDurationFromLog + "s" +
                                    ", wasAnswered flag: " + simState.callWasAnswered);

                            if (callType == CallLog.Calls.INCOMING_TYPE && callDurationFromLog > 0) {
                                // Call was answered and had duration - send after-call message
                                Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING ANSWERED CALL ENDED: " + phoneNumber +
                                        " - SENDING AFTER-CALL MESSAGE");
                                if (checkWhitelist(phoneNumber)) {
                                    scheduleAfterCallMessage(phoneNumber, subscriptionId);
                                }
                            } else if (callType == CallLog.Calls.REJECTED_TYPE) {
                                // Call was rejected/declined - send busy message
                                Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING REJECTED: " + phoneNumber +
                                        " - SENDING BUSY MESSAGE");
                                if (checkWhitelist(phoneNumber)) {
                                    scheduleBusyMessage(phoneNumber, subscriptionId);
                                }
                            } else if (callType == CallLog.Calls.MISSED_TYPE || (callType == CallLog.Calls.INCOMING_TYPE && callDurationFromLog == 0)) {
                                // Call was missed - send cut message
                                Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING MISSED: " + phoneNumber +
                                        " - SENDING CUT MESSAGE");
                                if (checkWhitelist(phoneNumber)) {
                                    scheduleMissedCallMessage(phoneNumber, subscriptionId);
                                }
                            } else {
                                // Fallback case - use internal state
                                if (simState.callWasAnswered || callDuration > MIN_CALL_DURATION) {
                                    Log.d(TAG, "📞 [SIM " + subscriptionId + "] FALLBACK ANSWERED: " + phoneNumber +
                                            " - SENDING AFTER-CALL MESSAGE");
                                    if (checkWhitelist(phoneNumber)) {
                                        scheduleAfterCallMessage(phoneNumber, subscriptionId);
                                    }
                                } else {
                                    Log.d(TAG, "📞 [SIM " + subscriptionId + "] FALLBACK MISSED: " + phoneNumber +
                                            " - SENDING CUT MESSAGE");
                                    if (checkWhitelist(phoneNumber)) {
                                        scheduleMissedCallMessage(phoneNumber, subscriptionId);
                                    }
                                }
                            }
                        }, 2000); // Wait 2 seconds for call log to update
                    } else {
                        // Outgoing call
                        Log.d(TAG, "📞 [SIM " + subscriptionId + "] OUTGOING CALL ENDED: " + phoneNumber +
                                ", duration: " + callDuration + "ms");
                        if (callDuration < MIN_CALL_DURATION) {
                            Log.d(TAG, "📞 [SIM " + subscriptionId + "] SHORT OUTGOING CALL - likely not answered: " + phoneNumber +
                                    " - SENDING OUTGOING MISSED MESSAGE");
                            if (checkWhitelist(phoneNumber)) {
                                scheduleOutgoingMissedMessage(phoneNumber, subscriptionId);
                            }
                        } else {
                            Log.d(TAG, "📞 [SIM " + subscriptionId + "] NORMAL OUTGOING CALL ENDED: " + phoneNumber +
                                    " - SENDING AFTER-CALL MESSAGE");
                            if (checkWhitelist(phoneNumber)) {
                                scheduleAfterCallMessage(phoneNumber, subscriptionId);
                            }
                        }
                    }

                    globalCallTracker.remove(phoneNumber);
                    simState.reset();

                } catch (Exception e) {
                    Log.e(TAG, "❌ Error handling idle state for SIM " + subscriptionId + ": " + e.getMessage(), e);
                }
            }
        }


        private void scheduleMissedCallMessage(String phoneNumber, int exactSubscriptionId) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "🚀 [SIM " + exactSubscriptionId + "] Scheduling MISSED message to " + phoneNumber);

                if (MessageHandler.canSendSmsFromSim(CallStateService.this, exactSubscriptionId)) {
                    sendCutMessage(phoneNumber, exactSubscriptionId);
                } else {
                    Log.e(TAG, "❌ [SIM " + exactSubscriptionId + "] Cannot send SMS, SIM not available");
                    int callLogSubId = getLastCallSubscriptionIdStatic(CallStateService.this, phoneNumber);
                    if (callLogSubId != -1 && MessageHandler.canSendSmsFromSim(CallStateService.this, callLogSubId)) {
                        Log.d(TAG, "🔄 [FALLBACK] Using SIM " + callLogSubId + " from call log");
                        sendCutMessage(phoneNumber, callLogSubId);
                    }
                }
            }, 3000);
        }

        private void scheduleBusyMessage(String phoneNumber, int exactSubscriptionId) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "🚀 [SIM " + exactSubscriptionId + "] Scheduling BUSY message to " + phoneNumber);

                if (MessageHandler.canSendSmsFromSim(CallStateService.this, exactSubscriptionId)) {
                    sendBusyMessage(phoneNumber, exactSubscriptionId);
                } else {
                    Log.e(TAG, "❌ [SIM " + exactSubscriptionId + "] Cannot send SMS, SIM not available");
                    int callLogSubId = getLastCallSubscriptionIdStatic(CallStateService.this, phoneNumber);
                    if (callLogSubId != -1 && MessageHandler.canSendSmsFromSim(CallStateService.this, callLogSubId)) {
                        Log.d(TAG, "🔄 [FALLBACK] Using SIM " + callLogSubId + " from call log");
                        sendBusyMessage(phoneNumber, callLogSubId);
                    }
                }
            }, 3000);
        }

        private int getCallDurationFromLog(String phoneNumber) {
            try {
                if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "READ_CALL_LOG permission not granted");
                    return 0;
                }

                String[] projection = {CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION};
                String selection = CallLog.Calls.NUMBER + "=?";
                String[] selectionArgs = {phoneNumber};
                String sortOrder = CallLog.Calls.DATE + " DESC LIMIT 1";

                Cursor cursor = getContentResolver().query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                );

                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);
                            if (durationIndex != -1) {
                                int duration = cursor.getInt(durationIndex);
                                Log.d(TAG, "📋 [SIM " + subscriptionId + "] Call duration from log: " + duration + "s for " + phoneNumber);
                                return duration;
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
                Log.w(TAG, "⚠️ [SIM " + subscriptionId + "] No call log entry found for duration of " + phoneNumber);
            } catch (Exception e) {
                Log.e(TAG, "❌ Error reading call duration from log for SIM " + subscriptionId + ": " + e.getMessage(), e);
            }
            return 0;
        }

        private void scheduleAfterCallMessage(String phoneNumber, int exactSubscriptionId) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                SimCallState simState = simCallStates.get(exactSubscriptionId);
                if (simState != null && !simState.hasActiveCall()) {
                    Log.d(TAG, "🚀 [SIM " + exactSubscriptionId + "] Scheduling AFTER-CALL message to " + phoneNumber);

                    if (MessageHandler.canSendSmsFromSim(CallStateService.this, exactSubscriptionId)) {
                        sendAfterCallMessage(phoneNumber, exactSubscriptionId);
                    } else {
                        Log.e(TAG, "❌ [SIM " + exactSubscriptionId + "] Cannot send SMS, SIM not available");
                        int callLogSubId = getLastCallSubscriptionIdStatic(CallStateService.this, phoneNumber);
                        if (callLogSubId != -1 && MessageHandler.canSendSmsFromSim(CallStateService.this, callLogSubId)) {
                            Log.d(TAG, "🔄 [FALLBACK] Using SIM " + callLogSubId + " from call log");
                            sendAfterCallMessage(phoneNumber, callLogSubId);
                        }
                    }
                }
            }, 4000);
        }

        private void scheduleMissedCallMessage(String phoneNumber, int exactSubscriptionId, int callType) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "🚀 [SIM " + exactSubscriptionId + "] Scheduling MISSED/REJECTED message to " + phoneNumber +
                        ", callType: " + getCallTypeName(callType));

                if (MessageHandler.canSendSmsFromSim(CallStateService.this, exactSubscriptionId)) {
                    switch (callType) {
                        case CallLog.Calls.MISSED_TYPE:
                            Log.d(TAG, "📤 [SIM " + exactSubscriptionId + "] Sending CUT message for MISSED call");
                            sendCutMessage(phoneNumber, exactSubscriptionId);
                            break;
                        case CallLog.Calls.REJECTED_TYPE:
                            Log.d(TAG, "📤 [SIM " + exactSubscriptionId + "] Sending BUSY message for REJECTED call");
                            sendBusyMessage(phoneNumber, exactSubscriptionId);
                            break;
                        default:
                            Log.w(TAG, "⚠️ [SIM " + exactSubscriptionId + "] Unknown call type, sending CUT message");
                            sendCutMessage(phoneNumber, exactSubscriptionId);
                    }
                } else {
                    Log.e(TAG, "❌ [SIM " + exactSubscriptionId + "] Cannot send SMS, SIM not available");
                    int callLogSubId = getLastCallSubscriptionIdStatic(CallStateService.this, phoneNumber);
                    if (callLogSubId != -1 && MessageHandler.canSendSmsFromSim(CallStateService.this, callLogSubId)) {
                        Log.d(TAG, "🔄 [FALLBACK] Using SIM " + callLogSubId + " from call log");
                        switch (callType) {
                            case CallLog.Calls.MISSED_TYPE:
                                sendCutMessage(phoneNumber, callLogSubId);
                                break;
                            case CallLog.Calls.REJECTED_TYPE:
                                sendBusyMessage(phoneNumber, callLogSubId);
                                break;
                            default:
                                sendCutMessage(phoneNumber, callLogSubId);
                        }
                    }
                }
            }, 3000);
        }

        private void scheduleOutgoingMissedMessage(String phoneNumber, int exactSubscriptionId) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "🚀 [SIM " + exactSubscriptionId + "] Scheduling OUTGOING MISSED message to " + phoneNumber);

                if (MessageHandler.canSendSmsFromSim(CallStateService.this, exactSubscriptionId)) {
                    sendOutgoingMissedMessage(phoneNumber, exactSubscriptionId);
                } else {
                    Log.e(TAG, "❌ [SIM " + exactSubscriptionId + "] Cannot send SMS, SIM not available");
                    int callLogSubId = getLastCallSubscriptionIdStatic(CallStateService.this, phoneNumber);
                    if (callLogSubId != -1 && MessageHandler.canSendSmsFromSim(CallStateService.this, callLogSubId)) {
                        Log.d(TAG, "🔄 [FALLBACK] Using SIM " + callLogSubId + " from call log");
                        sendOutgoingMissedMessage(phoneNumber, callLogSubId);
                    }
                }
            }, 3000);
        }

        private void sendAfterCallMessage(String phoneNumber, int exactSubscriptionId) {
            try {
                if (!canSendMessage(phoneNumber, "after_call", exactSubscriptionId)) {
                    return;
                }

                Log.d(TAG, "📤 [SIM " + exactSubscriptionId + "] SENDING after-call message to " + phoneNumber +
                        " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, exactSubscriptionId));

                MessageHandler.sendAfterCallMessage(CallStateService.this, phoneNumber, exactSubscriptionId);
                logMessageSent(phoneNumber, "after_call", exactSubscriptionId);

            } catch (Exception e) {
                Log.e(TAG, "❌ Error sending after call message from SIM " + exactSubscriptionId + ": " + e.getMessage(), e);
                showPermissionNotification("Message Sending Failed", "Failed to send after-call message from SIM " + exactSubscriptionId);
            }
        }

        private void sendCutMessage(String phoneNumber, int exactSubscriptionId) {
            try {
                if (!canSendMessage(phoneNumber, "cut", exactSubscriptionId)) {
                    return;
                }

                Log.d(TAG, "📤 [SIM " + exactSubscriptionId + "] SENDING cut message to " + phoneNumber +
                        " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, exactSubscriptionId));

                MessageHandler.sendCutMessage(CallStateService.this, phoneNumber, exactSubscriptionId);
                logMessageSent(phoneNumber, "cut", exactSubscriptionId);

            } catch (Exception e) {
                Log.e(TAG, "❌ Error sending cut message from SIM " + exactSubscriptionId + ": " + e.getMessage(), e);
                showPermissionNotification("Message Sending Failed", "Failed to send cut message from SIM " + exactSubscriptionId);
            }
        }

        private void sendBusyMessage(String phoneNumber, int exactSubscriptionId) {
            try {
                if (!canSendMessage(phoneNumber, "busy", exactSubscriptionId)) {
                    return;
                }

                Log.d(TAG, "📤 [SIM " + exactSubscriptionId + "] SENDING busy message to " + phoneNumber +
                        " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, exactSubscriptionId));

                MessageHandler.sendBusyMessage(CallStateService.this, phoneNumber, exactSubscriptionId);
                logMessageSent(phoneNumber, "busy", exactSubscriptionId);

            } catch (Exception e) {
                Log.e(TAG, "❌ Error sending busy message from SIM " + exactSubscriptionId + ": " + e.getMessage(), e);
                showPermissionNotification("Message Sending Failed", "Failed to send busy message from SIM " + exactSubscriptionId);
            }
        }

        private void sendOutgoingMissedMessage(String phoneNumber, int exactSubscriptionId) {
            try {
                if (!canSendMessage(phoneNumber, "outgoing_missed", exactSubscriptionId)) {
                    return;
                }

                Log.d(TAG, "📤 [SIM " + exactSubscriptionId + "] SENDING outgoing missed message to " + phoneNumber +
                        " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, exactSubscriptionId));

                MessageHandler.sendOutgoingMissedMessage(CallStateService.this, phoneNumber, exactSubscriptionId);
                logMessageSent(phoneNumber, "outgoing_missed", exactSubscriptionId);

            } catch (Exception e) {
                Log.e(TAG, "❌ Error sending outgoing missed message from SIM " + exactSubscriptionId + ": " + e.getMessage(), e);
                showPermissionNotification("Message Sending Failed", "Failed to send outgoing missed message from SIM " + exactSubscriptionId);
            }
        }

        private boolean canSendMessage(String phoneNumber, String messageType, int exactSubscriptionId) {
            SimCallState simState = simCallStates.get(exactSubscriptionId);
            if (simState == null) {
                return false;
            }

            String callKey = messageType + "_" + phoneNumber + "_" + exactSubscriptionId;
            long currentTime = System.currentTimeMillis();

            if (callKey.equals(simState.lastProcessedCall) &&
                    (currentTime - simState.lastProcessedTime) < CALL_PROCESSING_COOLDOWN) {
                Log.d(TAG, "⏸️ [SIM " + exactSubscriptionId + "] Skipping duplicate " + messageType + " message for " + phoneNumber);
                return false;
            }

            simState.lastProcessedCall = callKey;
            simState.lastProcessedTime = currentTime;
            return true;
        }

        private int getCallTypeFromLog(String phoneNumber) {
            try {
                if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "READ_CALL_LOG permission not granted");
                    return CallLog.Calls.MISSED_TYPE;
                }

                String[] projection = {CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION};
                String selection = CallLog.Calls.NUMBER + "=?";
                String[] selectionArgs = {phoneNumber};
                String sortOrder = CallLog.Calls.DATE + " DESC LIMIT 1";

                Cursor cursor = getContentResolver().query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                );

                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int callTypeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);
                            int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);
                            int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);

                            if (callTypeIndex != -1) {
                                int callType = cursor.getInt(callTypeIndex);
                                int duration = (durationIndex != -1) ? cursor.getInt(durationIndex) : 0;
                                long callDate = (dateIndex != -1) ? cursor.getLong(dateIndex) : 0;

                                Log.d(TAG, "📋 [SIM " + subscriptionId + "] Call log - Type: " + getCallTypeName(callType) +
                                        ", Duration: " + duration + "s, Date: " + callDate + " for " + phoneNumber);

                                if (callType == CallLog.Calls.INCOMING_TYPE && duration > 0) {
                                    Log.d(TAG, "📋 [SIM " + subscriptionId + "] Confirmed ANSWERED incoming call");
                                    return CallLog.Calls.INCOMING_TYPE;
                                } else if (callType == CallLog.Calls.INCOMING_TYPE && duration == 0) {
                                    Log.d(TAG, "📋 [SIM " + subscriptionId + "] Incoming call with 0 duration, treating as MISSED");
                                    return CallLog.Calls.MISSED_TYPE;
                                }

                                return callType;
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
                Log.w(TAG, "⚠️ [SIM " + subscriptionId + "] No call log entry found for " + phoneNumber);
            } catch (Exception e) {
                Log.e(TAG, "❌ Error reading call log for call type from SIM " + subscriptionId + ": " + e.getMessage(), e);
            }
            return CallLog.Calls.MISSED_TYPE;
        }

        private String getCallStateName(int state) {
            switch (state) {
                case TelephonyManager.CALL_STATE_IDLE: return "IDLE";
                case TelephonyManager.CALL_STATE_RINGING: return "RINGING";
                case TelephonyManager.CALL_STATE_OFFHOOK: return "OFFHOOK";
                default: return "UNKNOWN(" + state + ")";
            }
        }

        private String getCallTypeName(int callType) {
            switch (callType) {
                case CallLog.Calls.INCOMING_TYPE: return "INCOMING";
                case CallLog.Calls.OUTGOING_TYPE: return "OUTGOING";
                case CallLog.Calls.MISSED_TYPE: return "MISSED";
                case CallLog.Calls.REJECTED_TYPE: return "REJECTED";
                default: return "UNKNOWN(" + callType + ")";
            }
        }
    }

    public static int getLastCallSubscriptionIdStatic(Context context, String phoneNumber) {
        int subId = -1;
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_CALL_LOG permission not granted");
                return subId;
            }

            String[] projection = {
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    "subscription_id",
                    "phone_account_id"
            };
            String selection = CallLog.Calls.NUMBER + "=?";
            String[] selectionArgs = {phoneNumber};
            String sortOrder = CallLog.Calls.DATE + " DESC LIMIT 1";

            Cursor cursor = context.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
            );

            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int subIdCol = cursor.getColumnIndex("subscription_id");
                        if (subIdCol != -1) {
                            subId = cursor.getInt(subIdCol);
                            Log.d(TAG, "📋 Found subscription_id: " + subId + " for number: " + phoneNumber);
                        } else {
                            int phoneAccountCol = cursor.getColumnIndex("phone_account_id");
                            if (phoneAccountCol != -1) {
                                String phoneAccountId = cursor.getString(phoneAccountCol);
                                subId = extractSubscriptionIdFromPhoneAccount(context, phoneAccountId);
                            }
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error getting last call subscriptionId: " + e.getMessage(), e);
        }
        return subId;
    }

    private static int extractSubscriptionIdFromPhoneAccount(Context context, String phoneAccountId) {
        try {
            if (phoneAccountId == null) return -1;
            if (phoneAccountId.contains("_")) {
                String[] parts = phoneAccountId.split("_");
                for (String part : parts) {
                    try {
                        int possibleSubId = Integer.parseInt(part);
                        if (isValidSubscriptionIdStatic(context, possibleSubId)) {
                            Log.d(TAG, "✅ Extracted subscription ID " + possibleSubId + " from phone account: " + phoneAccountId);
                            return possibleSubId;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Log.d(TAG, "⚠️ Could not extract subscription ID from phone account: " + phoneAccountId);
        } catch (Exception e) {
            Log.e(TAG, "❌ Error extracting subscription ID from phone account: " + e.getMessage(), e);
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    private static boolean isValidSubscriptionIdStatic(Context context, int subscriptionId) {
        try {
            if (subscriptionId == -1) return false;
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
                if (subscriptions != null) {
                    for (SubscriptionInfo info : subscriptions) {
                        if (info.getSubscriptionId() == subscriptionId) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error validating subscription ID: " + e.getMessage(), e);
            return false;
        }
    }

    private boolean checkWhitelist(String phoneNumber) {
        if (mDatabase == null || currentUserId == null) {
            Log.w(TAG, "Database or user ID null, allowing whitelist check by default");
            return true;
        }

        try {
            final boolean[] isWhitelisted = {true};
            final Object lock = new Object();

            mDatabase.child("users").child(currentUserId).child("whitelist")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                            synchronized (lock) {
                                if (dataSnapshot.exists()) {
                                    Boolean whitelistEnabledValue = dataSnapshot.child("enabled").getValue(Boolean.class);
                                    boolean whitelistEnabled = whitelistEnabledValue != null && whitelistEnabledValue;
                                    if (whitelistEnabled) {
                                        boolean numberFound = false;
                                        for (DataSnapshot entry : dataSnapshot.child("numbers").getChildren()) {
                                            String whitelistedNumber = entry.getValue(String.class);
                                            if (whitelistedNumber != null && whitelistedNumber.equals(phoneNumber)) {
                                                numberFound = true;
                                                break;
                                            }
                                        }
                                        isWhitelisted[0] = numberFound;
                                        Log.d(TAG, "✅ Whitelist check for " + phoneNumber + ": " + (numberFound ? "ALLOWED" : "BLOCKED"));
                                    }
                                    lock.notify();
                                } else {
                                    Log.d(TAG, "✅ No whitelist data found, allowing by default");
                                    lock.notify();
                                }
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError databaseError) {
                            synchronized (lock) {
                                Log.e(TAG, "❌ Error checking whitelist: " + databaseError.getMessage());
                                lock.notify();
                            }
                        }
                    });

            synchronized (lock) {
                try {
                    lock.wait(3000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "❌ Interrupted while checking whitelist", e);
                }
            }
            return isWhitelisted[0];
        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking whitelist: " + e.getMessage(), e);
            return true;
        }
    }

    private void logMessageSent(String phoneNumber, String messageType, int subscriptionId) {
        if (mDatabase == null || currentUserId == null) {
            Log.w(TAG, "Cannot log message: database or user ID null");
            return;
        }

        try {
            Map<String, Object> messageLog = new HashMap<>();
            messageLog.put("phoneNumber", phoneNumber);
            messageLog.put("messageType", messageType);
            messageLog.put("subscriptionId", subscriptionId);
            messageLog.put("timestamp", System.currentTimeMillis());
            messageLog.put("simInfo", MessageHandler.getSimDisplayName(this, subscriptionId));

            mDatabase.child("users").child(currentUserId).child("sentMessages").push()
                    .setValue(messageLog)
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Logged sent message: " + messageType + " to " + phoneNumber + " from SIM " + subscriptionId))
                    .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to log sent message: " + e.getMessage(), e));
        } catch (Exception e) {
            Log.e(TAG, "❌ Error logging sent message: " + e.getMessage(), e);
        }
    }

    private void registerPermissionRevokedReceiver() {
        try {
            IntentFilter filter = new IntentFilter("com.lsoysapp.callresponderuser.PERMISSION_REVOKED");
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.w(TAG, "⚠️ Permissions revoked, stopping service");
                    showPermissionNotification("Permissions Revoked", "Please grant all required permissions in app settings.");
                    stopSelf();
                    Intent broadcastIntent = new Intent("com.lsoysapp.callresponderuser.REQUEST_PERMISSIONS");
                    context.sendBroadcast(broadcastIntent);
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            Log.d(TAG, "✅ Registered permission revoked receiver");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error registering permission revoked receiver: " + e.getMessage(), e);
        }
    }

    public static void testOutgoingCallDetection(Context context) {
        Log.d(TAG, "=== 🧪 TESTING OUTGOING CALL DETECTION ===");

        MessageHandler.logAllActiveSubscriptions(context);
        MessageHandler.testMessageRetrieval(context);

        try {
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptions = null;
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            } else {
                Log.w(TAG, "READ_PHONE_STATE permission not granted, cannot get active subscriptions");
            }

            if (subscriptions != null) {
                for (SubscriptionInfo info : subscriptions) {
                    int subId = info.getSubscriptionId();
                    boolean canSend = MessageHandler.canSendSmsFromSim(context, subId);
                    Log.d(TAG, "🧪 SIM " + subId + " (" + MessageHandler.getSimDisplayName(context, subId) + ") can send SMS: " + canSend);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error testing SMS capability: " + e.getMessage(), e);
        }

        Log.d(TAG, "=== 🧪 END OUTGOING CALL TEST ===");
    }

    public static void testOutgoingMissedMessage(Context context, String phoneNumber) {
        Log.d(TAG, "🧪 TESTING: Manually sending outgoing missed message to " + phoneNumber);

        int defaultSmsSubId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (MessageHandler.canSendSmsFromSim(context, defaultSmsSubId)) {
            MessageHandler.sendOutgoingMissedMessage(context, phoneNumber, defaultSmsSubId);
            Log.d(TAG, "✅ Test message sent from SIM " + defaultSmsSubId);
        } else {
            Log.e(TAG, "❌ Cannot send test message - SIM not available");
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            for (Map.Entry<Integer, CallStateListener> entry : callStateListeners.entrySet()) {
                int subId = entry.getKey();
                CallStateListener listener = entry.getValue();
                TelephonyManager tm = telephonyManagers.get(subId);
                if (tm != null) {
                    tm.listen(listener, PhoneStateListener.LISTEN_NONE);
                }
            }

            callStateListeners.clear();
            telephonyManagers.clear();
            simCallStates.clear();

            synchronized (globalCallLock) {
                globalCallTracker.clear();
            }

            WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME);
            Log.d(TAG, "🔄 CallStateService destroyed");
            scheduleAlarmRestart();
        } catch (Exception e) {
            Log.e(TAG, "❌ Error in onDestroy: " + e.getMessage(), e);
        }
    }
}