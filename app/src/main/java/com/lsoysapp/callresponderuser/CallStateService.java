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
    private TelephonyManager telephonyManager;
    private DatabaseReference mDatabase;
    private String currentUserId;
    private boolean isSubscribed = false;
    private boolean isFreeTrialActive = false;
    private boolean isUserStatusCached = false;
    private final HashMap<Integer, CallStateListener> callStateListeners = new HashMap<>();
    private static final long MIN_CALL_DURATION = 5000; // 5 seconds

    // FIXED: Simplified per-SIM tracking - each SIM manages its own calls independently
    private static class SimCallState {
        String incomingNumber = null;
        String outgoingNumber = null;
        long callStartTime = 0;
        boolean wasRinging = false;
        int callState = TelephonyManager.CALL_STATE_IDLE;
        long lastProcessedTime = 0;
        String lastProcessedCall = "";
    }

    private final HashMap<Integer, SimCallState> simCallStates = new HashMap<>();
    private static final long CALL_PROCESSING_COOLDOWN = 3000; // 3 seconds

    // ADDED: Global call ownership tracking to prevent duplicate processing
    private static final HashMap<String, Integer> activeCallOwnership = new HashMap<>(); // phoneNumber -> subscriptionId
    private static final Object callOwnershipLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate called");
        try {
            loadCachedUserStatus();
            setupForegroundService();
            initializeFirebase();
            setupTelephonyManager();
            checkPermissions();
            registerCallStateListeners();
            registerPermissionRevokedReceiver();
            scheduleServiceCheck();
            scheduleAlarmRestart();
            checkBatteryOptimization();
            showOemSettingsNotification();
            // Log SIM info for debugging
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

    private void setupTelephonyManager() {
        try {
            telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                Log.e(TAG, "TelephonyManager is null");
                showPermissionNotification("Telephony Error", "Telephony service unavailable. Please restart the app.");
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up TelephonyManager: " + e.getMessage(), e);
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
                for (SubscriptionInfo info : subscriptions) {
                    int subscriptionId = info.getSubscriptionId();
                    // Initialize call state for this SIM
                    simCallStates.put(subscriptionId, new SimCallState());

                    CallStateListener listener = new CallStateListener(subscriptionId);
                    callStateListeners.put(subscriptionId, listener);
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                    Log.d(TAG, "✅ Registered call state listener for subscription ID: " + subscriptionId + " (" + MessageHandler.getSimDisplayName(this, subscriptionId) + ")");
                }
            } else {
                Log.w(TAG, "No active SIM subscriptions found");
                int defaultSubId = -1;
                simCallStates.put(defaultSubId, new SimCallState());
                CallStateListener listener = new CallStateListener(defaultSubId);
                callStateListeners.put(defaultSubId, listener);
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                Log.d(TAG, "✅ Registered call state listener for default SIM");
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

    // FIXED: Completely redesigned CallStateListener with proper SIM isolation
    private class CallStateListener extends PhoneStateListener {
        private final int subscriptionId;

        CallStateListener(int subscriptionId) {
            this.subscriptionId = subscriptionId;
            Log.d(TAG, "✅ Created CallStateListener for subscriptionId: " + subscriptionId + " (" + MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId) + ")");
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
                Log.d(TAG, "🔔 [SIM " + subscriptionId + "] Call state: " + getCallStateName(state) +
                        ", phone: " + phoneNumber + ", SIM: " + simInfo);

                // Get or create call state for this SIM
                SimCallState simState = simCallStates.get(subscriptionId);
                if (simState == null) {
                    simState = new SimCallState();
                    simCallStates.put(subscriptionId, simState);
                }

                // Update call state
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
            synchronized (callOwnershipLock) {
                // Check if another SIM already owns this call
                Integer existingOwner = activeCallOwnership.get(phoneNumber);
                if (existingOwner != null && existingOwner != subscriptionId) {
                    Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Call from " + phoneNumber +
                            " already owned by SIM " + existingOwner + ", ignoring");
                    return;
                }

                // This SIM takes ownership of the call
                activeCallOwnership.put(phoneNumber, subscriptionId);
                Log.d(TAG, "📞 [SIM " + subscriptionId + "] TAKING OWNERSHIP - INCOMING RINGING: " + phoneNumber);

                simState.incomingNumber = phoneNumber;
                simState.callStartTime = System.currentTimeMillis();
                simState.wasRinging = true;
                simState.outgoingNumber = null; // Clear any outgoing state
            }
        }

        private void handleOffhookState(String phoneNumber, SimCallState simState, int previousState) {
            synchronized (callOwnershipLock) {
                if (simState.wasRinging && simState.incomingNumber != null &&
                        simState.incomingNumber.equals(phoneNumber)) {
                    // Verify this SIM owns the incoming call
                    Integer callOwner = activeCallOwnership.get(phoneNumber);
                    if (callOwner != null && callOwner != subscriptionId) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Incoming call owned by SIM " + callOwner + ", ignoring");
                        return;
                    }

                    // Incoming call answered on this SIM
                    Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING ANSWERED: " + phoneNumber);

                    if (checkWhitelist(phoneNumber)) {
                        // Schedule after-call message from THIS specific SIM
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            SimCallState currentState = simCallStates.get(subscriptionId);
                            if (currentState != null && currentState.callStartTime > 0) {
                                long callDuration = System.currentTimeMillis() - currentState.callStartTime;
                                if (callDuration >= MIN_CALL_DURATION) {
                                    Log.d(TAG, "📤 [SIM " + subscriptionId + "] Scheduling after-call message for: " + phoneNumber);
                                    sendAfterCallMessage(phoneNumber);
                                }
                            }
                        }, 4000);
                    }
                } else {
                    // Check if another SIM already owns this outgoing call
                    Integer existingOwner = activeCallOwnership.get(phoneNumber);
                    if (existingOwner != null && existingOwner != subscriptionId) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Outgoing call to " + phoneNumber +
                                " already owned by SIM " + existingOwner + ", ignoring");
                        return;
                    }

                    // This SIM takes ownership of the outgoing call
                    activeCallOwnership.put(phoneNumber, subscriptionId);
                    Log.d(TAG, "📞 [SIM " + subscriptionId + "] TAKING OWNERSHIP - OUTGOING STARTED: " + phoneNumber);

                    simState.outgoingNumber = phoneNumber;
                    simState.callStartTime = System.currentTimeMillis();
                    simState.incomingNumber = null; // Clear any incoming state
                    simState.wasRinging = false;
                }
            }
        }

        private void handleIdleState(SimCallState simState) {
            synchronized (callOwnershipLock) {
                try {
                    if (simState.wasRinging && simState.incomingNumber != null) {
                        // Verify this SIM owns the incoming call
                        String phoneNumber = simState.incomingNumber;
                        Integer callOwner = activeCallOwnership.get(phoneNumber);
                        if (callOwner != null && callOwner != subscriptionId) {
                            Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Incoming call from " + phoneNumber +
                                    " owned by SIM " + callOwner + ", ignoring idle state");
                            // Reset only local state, don't send message
                            resetSimState(simState);
                            return;
                        }

                        // Incoming call ended without being answered - this SIM owns it
                        Log.d(TAG, "📞 [SIM " + subscriptionId + "] INCOMING MISSED/REJECTED: " + phoneNumber);

                        if (checkWhitelist(phoneNumber)) {
                            // Send missed/rejected message from THIS specific SIM
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                Log.d(TAG, "📤 [SIM " + subscriptionId + "] Scheduling missed call message for: " + phoneNumber);
                                sendMissedCallMessage(phoneNumber);
                            }, 3000);
                        }

                        // Release ownership after processing
                        activeCallOwnership.remove(phoneNumber);

                    } else if (simState.outgoingNumber != null && simState.callStartTime > 0) {
                        // Verify this SIM owns the outgoing call
                        String phoneNumber = simState.outgoingNumber;
                        Integer callOwner = activeCallOwnership.get(phoneNumber);
                        if (callOwner != null && callOwner != subscriptionId) {
                            Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Outgoing call to " + phoneNumber +
                                    " owned by SIM " + callOwner + ", ignoring idle state");
                            // Reset only local state, don't send message
                            resetSimState(simState);
                            return;
                        }

                        // Outgoing call ended - this SIM owns it
                        long callDuration = System.currentTimeMillis() - simState.callStartTime;
                        Log.d(TAG, "📞 [SIM " + subscriptionId + "] OUTGOING ENDED: " + phoneNumber +
                                ", duration: " + callDuration + "ms");

                        if (callDuration < MIN_CALL_DURATION && checkWhitelist(phoneNumber)) {
                            // Short outgoing call - likely missed, send from THIS specific SIM
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                Log.d(TAG, "📤 [SIM " + subscriptionId + "] Scheduling outgoing missed message for: " + phoneNumber);
                                sendOutgoingMissedMessage(phoneNumber);
                            }, 3000);
                        }

                        // Release ownership after processing
                        activeCallOwnership.remove(phoneNumber);
                    }

                    // Reset call state for this SIM
                    resetSimState(simState);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error handling idle state for SIM " + subscriptionId + ": " + e.getMessage(), e);
                }
            }
        }

        private void resetSimState(SimCallState simState) {
            simState.incomingNumber = null;
            simState.outgoingNumber = null;
            simState.callStartTime = 0;
            simState.wasRinging = false;
        }

        private String getCallStateName(int state) {
            switch (state) {
                case TelephonyManager.CALL_STATE_IDLE: return "IDLE";
                case TelephonyManager.CALL_STATE_RINGING: return "RINGING";
                case TelephonyManager.CALL_STATE_OFFHOOK: return "OFFHOOK";
                default: return "UNKNOWN(" + state + ")";
            }
        }

        // FIXED: All message sending methods now use the correct SIM (subscriptionId of this listener)
        private void sendAfterCallMessage(String phoneNumber) {
            synchronized (callOwnershipLock) {
                try {
                    // Verify this SIM still owns the call
                    Integer callOwner = activeCallOwnership.get(phoneNumber);
                    if (callOwner != null && callOwner != subscriptionId) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] After-call message blocked - owned by SIM " + callOwner);
                        return;
                    }

                    SimCallState simState = simCallStates.get(subscriptionId);
                    if (simState == null) return;

                    String callKey = "after_call_" + phoneNumber + "_" + subscriptionId;
                    long currentTime = System.currentTimeMillis();

                    // Prevent duplicate messages within cooldown period for this specific SIM
                    if (callKey.equals(simState.lastProcessedCall) &&
                            (currentTime - simState.lastProcessedTime) < CALL_PROCESSING_COOLDOWN) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Skipping duplicate after-call message for " + phoneNumber);
                        return;
                    }

                    simState.lastProcessedCall = callKey;
                    simState.lastProcessedTime = currentTime;

                    Log.d(TAG, "📤 [SIM " + subscriptionId + "] SENDING after-call message to " + phoneNumber +
                            " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId));
                    MessageHandler.sendAfterCallMessage(CallStateService.this, phoneNumber, subscriptionId);
                    logMessageSent(phoneNumber, "after_call", subscriptionId);

                    // Release ownership after sending message
                    activeCallOwnership.remove(phoneNumber);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error sending after call message from SIM " + subscriptionId + ": " + e.getMessage(), e);
                    showPermissionNotification("Message Sending Failed", "Failed to send after-call message from SIM " + subscriptionId);
                }
            }
        }

        private void sendMissedCallMessage(String phoneNumber) {
            synchronized (callOwnershipLock) {
                try {
                    // Verify this SIM still owns the call
                    Integer callOwner = activeCallOwnership.get(phoneNumber);
                    if (callOwner != null && callOwner != subscriptionId) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Missed call message blocked - owned by SIM " + callOwner);
                        return;
                    }

                    SimCallState simState = simCallStates.get(subscriptionId);
                    if (simState == null) return;

                    String callKey = "missed_call_" + phoneNumber + "_" + subscriptionId;
                    long currentTime = System.currentTimeMillis();

                    // Prevent duplicate messages within cooldown period for this specific SIM
                    if (callKey.equals(simState.lastProcessedCall) &&
                            (currentTime - simState.lastProcessedTime) < CALL_PROCESSING_COOLDOWN) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Skipping duplicate missed call message for " + phoneNumber);
                        return;
                    }

                    simState.lastProcessedCall = callKey;
                    simState.lastProcessedTime = currentTime;

                    // Check call log to determine if it was missed or rejected
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        int callType = getCallTypeFromLog(phoneNumber);
                        switch (callType) {
                            case CallLog.Calls.MISSED_TYPE:
                                Log.d(TAG, "📤 [SIM " + subscriptionId + "] SENDING cut message to " + phoneNumber +
                                        " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId));
                                MessageHandler.sendCutMessage(CallStateService.this, phoneNumber, subscriptionId);
                                logMessageSent(phoneNumber, "cut", subscriptionId);
                                break;
                            case CallLog.Calls.REJECTED_TYPE:
                                Log.d(TAG, "📤 [SIM " + subscriptionId + "] SENDING busy message to " + phoneNumber +
                                        " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId));
                                MessageHandler.sendBusyMessage(CallStateService.this, phoneNumber, subscriptionId);
                                logMessageSent(phoneNumber, "busy", subscriptionId);
                                break;
                            default:
                                Log.d(TAG, "📤 [SIM " + subscriptionId + "] SENDING cut message (default) to " + phoneNumber +
                                        " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId));
                                MessageHandler.sendCutMessage(CallStateService.this, phoneNumber, subscriptionId);
                                logMessageSent(phoneNumber, "cut", subscriptionId);
                        }
                    }, 1000); // Small delay to ensure call log is updated
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error sending missed call message from SIM " + subscriptionId + ": " + e.getMessage(), e);
                    showPermissionNotification("Message Sending Failed", "Failed to send missed call message from SIM " + subscriptionId);
                }
            }
        }

        private void sendOutgoingMissedMessage(String phoneNumber) {
            synchronized (callOwnershipLock) {
                try {
                    // Verify this SIM still owns the call
                    Integer callOwner = activeCallOwnership.get(phoneNumber);
                    if (callOwner != null && callOwner != subscriptionId) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Outgoing missed message blocked - owned by SIM " + callOwner);
                        return;
                    }

                    SimCallState simState = simCallStates.get(subscriptionId);
                    if (simState == null) return;

                    String callKey = "outgoing_missed_" + phoneNumber + "_" + subscriptionId;
                    long currentTime = System.currentTimeMillis();

                    // Prevent duplicate messages within cooldown period for this specific SIM
                    if (callKey.equals(simState.lastProcessedCall) &&
                            (currentTime - simState.lastProcessedTime) < CALL_PROCESSING_COOLDOWN) {
                        Log.d(TAG, "⏸️ [SIM " + subscriptionId + "] Skipping duplicate outgoing missed message for " + phoneNumber);
                        return;
                    }

                    // Verify it was actually a missed outgoing call from this SIM
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        int callType = getCallTypeFromLog(phoneNumber);
                        int callLogSubId = getLastCallSubscriptionId(phoneNumber);

                        Log.d(TAG, "🔍 [SIM " + subscriptionId + "] Outgoing call verification - CallType: " + callType +
                                ", Expected SIM: " + subscriptionId + ", CallLog SIM: " + callLogSubId);

                        // Only send if it was indeed a missed call from this specific SIM or if we can't determine the SIM
                        if (callType == CallLog.Calls.MISSED_TYPE && (callLogSubId == subscriptionId || callLogSubId == -1)) {
                            simState.lastProcessedCall = callKey;
                            simState.lastProcessedTime = currentTime;

                            Log.d(TAG, "📤 [SIM " + subscriptionId + "] SENDING outgoing missed message to " + phoneNumber +
                                    " from SIM: " + MessageHandler.getSimDisplayName(CallStateService.this, subscriptionId));
                            MessageHandler.sendOutgoingMissedMessage(CallStateService.this, phoneNumber, subscriptionId);
                            logMessageSent(phoneNumber, "outgoing_missed", subscriptionId);
                        } else {
                            Log.w(TAG, "⚠️ [SIM " + subscriptionId + "] Not sending outgoing missed message: verification failed");
                        }
                    }, 1000); // Small delay to ensure call log is updated
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error sending outgoing missed message from SIM " + subscriptionId + ": " + e.getMessage(), e);
                    showPermissionNotification("Message Sending Failed", "Failed to send outgoing missed message from SIM " + subscriptionId);
                }
            }
        }

        private int getLastCallSubscriptionId(String phoneNumber) {
            return getLastCallSubscriptionIdFromLog(phoneNumber);
        }

        private int getCallTypeFromLog(String phoneNumber) {
            try {
                if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "READ_CALL_LOG permission not granted");
                    return CallLog.Calls.MISSED_TYPE; // Default assumption
                }

                String[] projection = {CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.DATE};
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
                            int callType = cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE));
                            Log.d(TAG, "📋 [SIM " + subscriptionId + "] Found call type " + getCallTypeName(callType) + " for " + phoneNumber);
                            return callType;
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error reading call log for call type from SIM " + subscriptionId + ": " + e.getMessage(), e);
            }
            return CallLog.Calls.MISSED_TYPE; // Default assumption
        }

        private int getLastCallSubscriptionIdFromLog(String phoneNumber) {
            try {
                if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "READ_CALL_LOG permission not granted");
                    return -1;
                }

                String[] projection = {
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DATE,
                        "subscription_id",
                        "phone_account_id"
                };
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
                            int subIdCol = cursor.getColumnIndex("subscription_id");
                            if (subIdCol != -1) {
                                int subId = cursor.getInt(subIdCol);
                                Log.d(TAG, "📋 [SIM " + subscriptionId + "] Found subscription_id: " + subId + " for number: " + phoneNumber);
                                return subId;
                            } else {
                                // Try to extract from phone_account_id
                                int phoneAccountCol = cursor.getColumnIndex("phone_account_id");
                                if (phoneAccountCol != -1) {
                                    String phoneAccountId = cursor.getString(phoneAccountCol);
                                    int subId = extractSubscriptionIdFromPhoneAccount(CallStateService.this, phoneAccountId);
                                    Log.d(TAG, "📋 [SIM " + subscriptionId + "] Extracted subscription_id: " + subId + " from phone_account_id for number: " + phoneNumber);
                                    return subId;
                                }
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error getting subscription ID from call log for SIM " + subscriptionId + ": " + e.getMessage(), e);
            }
            return -1;
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

// Utility methods for call log processing
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

@Nullable
@Override
public IBinder onBind(Intent intent) {
    return null;
}

@Override
public void onDestroy() {
    super.onDestroy();
    try {
        for (CallStateListener listener : callStateListeners.values()) {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
        }
        callStateListeners.clear();
        simCallStates.clear();

        // Clear global call ownership tracking
        synchronized (callOwnershipLock) {
            activeCallOwnership.clear();
        }

        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME);
        Log.d(TAG, "🔄 CallStateService destroyed");
        scheduleAlarmRestart();
    } catch (Exception e) {
        Log.e(TAG, "❌ Error in onDestroy: " + e.getMessage(), e);
    }
}
}