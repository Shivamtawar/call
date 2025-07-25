package com.lsoysapp.callresponderuser;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import androidx.work.ExistingPeriodicWorkPolicy;
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
    private static final String WORK_NAME = "CallStateServiceCheck";
    private TelephonyManager telephonyManager;
    private DatabaseReference mDatabase;
    private String currentUserId;
    private boolean isSubscribed = false;
    private boolean isFreeTrialActive = false;
    private final HashMap<Integer, CallStateListener> callStateListeners = new HashMap<>();
    private final HashMap<Integer, String> ongoingOutgoingCalls = new HashMap<>();
    private final HashMap<Integer, Long> callStartTimes = new HashMap<>();
    private static final long MIN_CALL_DURATION = 5000; // 5 seconds in milliseconds
    private static String lastProcessedCall = "";
    private static long lastProcessedTime = 0;
    private static final long CALL_PROCESSING_COOLDOWN = 3000; // 3 seconds
    private static int incomingCallDetectedBySim = -1;
    private static String incomingCallNumber = "";
    private static long incomingCallTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            setupForegroundService();
            initializeFirebase();
            setupTelephonyManager();
            registerCallStateListeners();
            registerPermissionRevokedReceiver();
            scheduleServiceCheck();
            checkBatteryOptimization();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage());
            Toast.makeText(this, "Service initialization error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Ensure service is restarted if killed by the system
        return START_STICKY;
    }

    private void setupForegroundService() {
        try {
            // Create notification channel for Android O+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "call_channel",
                        "Call Monitoring",
                        android.app.NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Monitors calls for auto-replies");
                android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }

            Notification notification = new NotificationCompat.Builder(this, "call_channel")
                    .setContentTitle("Call Responder")
                    .setContentText("Monitoring calls for auto-replies")
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up foreground service: " + e.getMessage());
        }
    }

    private void initializeFirebase() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                currentUserId = user.getUid();
                mDatabase = FirebaseDatabase.getInstance().getReference();
                checkUserStatus();
            } else {
                Log.w(TAG, "No authenticated user found");
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Firebase: " + e.getMessage());
        }
    }

    private void setupTelephonyManager() {
        try {
            telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                Log.e(TAG, "TelephonyManager is null");
                Toast.makeText(this, "Telephony service unavailable", Toast.LENGTH_SHORT).show();
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up TelephonyManager: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void registerCallStateListeners() {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted");
                stopSelf();
                return;
            }

            SubscriptionManager subscriptionManager = SubscriptionManager.from(this);
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions != null && !subscriptions.isEmpty()) {
                for (SubscriptionInfo info : subscriptions) {
                    int subscriptionId = info.getSubscriptionId();
                    CallStateListener listener = new CallStateListener(subscriptionId);
                    callStateListeners.put(subscriptionId, listener);
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                    Log.d(TAG, "Registered call state listener for subscription ID: " + subscriptionId);
                }
            } else {
                Log.w(TAG, "No active SIM subscriptions found");
                CallStateListener listener = new CallStateListener(-1);
                callStateListeners.put(-1, listener);
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                Log.d(TAG, "Registered call state listener for default SIM");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering call state listeners: " + e.getMessage());
            stopSelf();
        }
    }

    private void checkUserStatus() {
        if (mDatabase == null || currentUserId == null) {
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
                            stopSelf();
                            return;
                        }

                        String freeTrialStatus = dataSnapshot.child("freeTrialStatus").getValue(String.class);
                        isFreeTrialActive = "active".equals(freeTrialStatus);

                        Boolean subscribed = dataSnapshot.child("subscription").child("isSubscribed").getValue(Boolean.class);
                        isSubscribed = subscribed != null && subscribed;

                        if (!isSubscribed && !isFreeTrialActive) {
                            Log.w(TAG, "User is neither subscribed nor in free trial");
                            stopSelf();
                        }
                    } else {
                        Log.w(TAG, "User data not found");
                        stopSelf();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error checking user status: " + e.getMessage());
                    stopSelf();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Failed to check user status: " + databaseError.getMessage());
                stopSelf();
            }
        });
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
                } catch (Exception e) {
                    Log.e(TAG, "Error starting battery optimization intent: " + e.getMessage());
                }
            }
        }
    }

    private void scheduleServiceCheck() {
        PeriodicWorkRequest serviceCheckRequest =
                new PeriodicWorkRequest.Builder(ServiceCheckWorker.class, 15, TimeUnit.MINUTES)
                        .addTag(WORK_NAME)
                        .build();

        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, serviceCheckRequest);
        Log.d(TAG, "Scheduled periodic service check");
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
                } else {
                    Log.d(TAG, "CallStateService is running");
                }
                return Result.success();
            } catch (Exception e) {
                Log.e(TAG, "Error in ServiceCheckWorker: " + e.getMessage());
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
        private String incomingNumber = null;
        private boolean wasRinging = false;
        private long callStartTime = 0;

        CallStateListener(int subscriptionId) {
            this.subscriptionId = subscriptionId;
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
                Log.d(TAG, "Call state changed: state=" + state + ", phoneNumber=" + phoneNumber + ", SIM: " + simInfo + " (SubID: " + subscriptionId + ")");

                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        incomingNumber = phoneNumber;
                        wasRinging = true;
                        callStartTime = System.currentTimeMillis();
                        incomingCallDetectedBySim = subscriptionId;
                        incomingCallNumber = incomingNumber;
                        incomingCallTime = callStartTime;
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        if (wasRinging) {
                            if (incomingNumber != null && CallStateService.this.checkWhitelist(incomingNumber)) {
                                long callDuration = System.currentTimeMillis() - callStartTime;
                                if (callDuration >= MIN_CALL_DURATION) {
                                    new Handler(Looper.getMainLooper()).postDelayed(() ->
                                            sendAfterCallMessageIfCorrectSIM(incomingNumber), 4000);
                                }
                            }
                        } else {
                            ongoingOutgoingCalls.put(subscriptionId, phoneNumber);
                            callStartTimes.put(subscriptionId, System.currentTimeMillis());
                        }
                        incomingNumber = null;
                        wasRinging = false;
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        if (wasRinging && incomingNumber != null) {
                            if (CallStateService.this.checkWhitelist(incomingNumber)) {
                                String finalIncomingNumber = incomingNumber;
                                new Handler(Looper.getMainLooper()).postDelayed(() ->
                                        sendMissedCallMessageIfCorrectSIM(finalIncomingNumber), 3000);
                            }
                            wasRinging = false;
                            incomingNumber = null;
                        } else if (ongoingOutgoingCalls.containsKey(subscriptionId)) {
                            String outgoingNumber = ongoingOutgoingCalls.get(subscriptionId);
                            Long startTime = callStartTimes.get(subscriptionId);
                            if (outgoingNumber != null && startTime != null) {
                                long callDuration = System.currentTimeMillis() - startTime;
                                if (callDuration < MIN_CALL_DURATION && CallStateService.this.checkWhitelist(outgoingNumber)) {
                                    new Handler(Looper.getMainLooper()).postDelayed(() ->
                                            sendOutgoingMissedMessageIfCorrectSIM(outgoingNumber), 3000);
                                }
                            }
                            ongoingOutgoingCalls.remove(subscriptionId);
                            callStartTimes.remove(subscriptionId);
                        }
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in call state listener: " + e.getMessage());
            }
        }

        private void sendAfterCallMessageIfCorrectSIM(String phoneNumber) {
            try {
                String callKey = "after_call_" + phoneNumber;
                long currentTime = System.currentTimeMillis();

                if (callKey.equals(lastProcessedCall) && (currentTime - lastProcessedTime) < CALL_PROCESSING_COOLDOWN) {
                    Log.d(TAG, "Skipping duplicate after call message processing for " + phoneNumber);
                    return;
                }

                Log.d(TAG, "After call - Tracked incoming SIM: " + incomingCallDetectedBySim + ", Listener SubID: " + subscriptionId + ", Call number: " + incomingCallNumber);

                if (incomingCallDetectedBySim == subscriptionId && phoneNumber.equals(incomingCallNumber)) {
                    lastProcessedCall = callKey;
                    lastProcessedTime = currentTime;

                    MessageHandler.sendAfterCallMessage(CallStateService.this, phoneNumber, subscriptionId);
                    CallStateService.this.logMessageSent(phoneNumber, "after_call", subscriptionId);
                    Log.d(TAG, "Sent after call message from SIM " + subscriptionId + " (tracked SIM)");
                } else {
                    Log.d(TAG, "Not sending after call message: SIM mismatch (tracked SIM: " + incomingCallDetectedBySim + ", listener SIM: " + subscriptionId + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending after call message: " + e.getMessage());
            }
        }

        private void sendMissedCallMessageIfCorrectSIM(String phoneNumber) {
            try {
                String callKey = "missed_call_" + phoneNumber;
                long currentTime = System.currentTimeMillis();

                if (callKey.equals(lastProcessedCall) && (currentTime - lastProcessedTime) < CALL_PROCESSING_COOLDOWN) {
                    Log.d(TAG, "Skipping duplicate missed call message processing for " + phoneNumber);
                    return;
                }

                Log.d(TAG, "Missed call - Tracked incoming SIM: " + incomingCallDetectedBySim + ", Listener SubID: " + subscriptionId + ", Call number: " + incomingCallNumber);

                if (incomingCallDetectedBySim == subscriptionId && phoneNumber.equals(incomingCallNumber)) {
                    lastProcessedCall = callKey;
                    lastProcessedTime = currentTime;

                    int callType = CallStateService.this.getCallType(phoneNumber);
                    switch (callType) {
                        case CallLog.Calls.MISSED_TYPE:
                            MessageHandler.sendCutMessage(CallStateService.this, phoneNumber, subscriptionId);
                            CallStateService.this.logMessageSent(phoneNumber, "cut", subscriptionId);
                            Log.d(TAG, "Sent cut message from SIM " + subscriptionId + " (tracked SIM)");
                            break;
                        case CallLog.Calls.REJECTED_TYPE:
                            MessageHandler.sendBusyMessage(CallStateService.this, phoneNumber, subscriptionId);
                            CallStateService.this.logMessageSent(phoneNumber, "busy", subscriptionId);
                            Log.d(TAG, "Sent busy message from SIM " + subscriptionId + " (tracked SIM)");
                            break;
                        default:
                            Log.d(TAG, "Unknown call type for incoming call: " + callType);
                    }
                } else {
                    Log.d(TAG, "Not sending missed call message: SIM mismatch (tracked SIM: " + incomingCallDetectedBySim + ", listener SIM: " + subscriptionId + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending missed call message: " + e.getMessage());
            }
        }

        private void sendOutgoingMissedMessageIfCorrectSIM(String phoneNumber) {
            try {
                String callKey = "outgoing_missed_" + phoneNumber;
                long currentTime = System.currentTimeMillis();

                if (callKey.equals(lastProcessedCall) && (currentTime - lastProcessedTime) < CALL_PROCESSING_COOLDOWN) {
                    Log.d(TAG, "Skipping duplicate outgoing missed message processing for " + phoneNumber);
                    return;
                }

                int callType = CallStateService.this.getCallType(phoneNumber);
                int callLogSubId = getLastCallSubscriptionIdStatic(CallStateService.this, phoneNumber);
                Log.d(TAG, "Outgoing missed - CallLog SubID: " + callLogSubId + ", Listener SubID: " + subscriptionId + ", CallType: " + callType);

                if (callType == CallLog.Calls.MISSED_TYPE && (callLogSubId == subscriptionId || (callLogSubId == -1 && isFirstActiveSubscription()))) {
                    lastProcessedCall = callKey;
                    lastProcessedTime = currentTime;

                    MessageHandler.sendOutgoingMissedMessage(CallStateService.this, phoneNumber, subscriptionId);
                    CallStateService.this.logMessageSent(phoneNumber, "outgoing_missed", subscriptionId);
                    Log.d(TAG, "Sent outgoing missed message from SIM " + subscriptionId);
                } else {
                    Log.d(TAG, "Not sending outgoing missed message: SIM mismatch or wrong call type (expected " + subscriptionId + ", got " + callLogSubId + ", callType: " + callType + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending outgoing missed message: " + e.getMessage());
            }
        }

        @SuppressLint("MissingPermission")
        private boolean isFirstActiveSubscription() {
            try {
                SubscriptionManager subscriptionManager = SubscriptionManager.from(CallStateService.this);
                if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
                    if (subscriptions != null && !subscriptions.isEmpty()) {
                        return subscriptions.get(0).getSubscriptionId() == this.subscriptionId;
                    }
                }
                return this.subscriptionId == -1;
            } catch (Exception e) {
                Log.e(TAG, "Error checking if first active subscription: " + e.getMessage());
                return this.subscriptionId == -1;
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
                        StringBuilder debugInfo = new StringBuilder();
                        debugInfo.append("Call log entry for ").append(phoneNumber).append(": ");
                        for (int i = 0; i < cursor.getColumnCount(); i++) {
                            String columnName = cursor.getColumnName(i);
                            String value = cursor.getString(i);
                            debugInfo.append(columnName).append("=").append(value).append(", ");
                        }
                        Log.d(TAG, debugInfo.toString());

                        int subIdCol = cursor.getColumnIndex("subscription_id");
                        if (subIdCol != -1) {
                            subId = cursor.getInt(subIdCol);
                            Log.d(TAG, "Found subscription_id: " + subId + " for number: " + phoneNumber);
                        } else {
                            Log.w(TAG, "subscription_id column not found in call log");
                        }

                        if (subId == -1) {
                            int phoneAccountCol = cursor.getColumnIndex("phone_account_id");
                            if (phoneAccountCol != -1) {
                                String phoneAccountId = cursor.getString(phoneAccountCol);
                                Log.d(TAG, "Found phone_account_id: " + phoneAccountId + " for number: " + phoneNumber);
                                subId = extractSubscriptionIdFromPhoneAccount(context, phoneAccountId);
                            }
                        }
                    } else {
                        Log.w(TAG, "No call log entry found for number: " + phoneNumber);
                    }
                } finally {
                    cursor.close();
                }
            } else {
                Log.w(TAG, "Call log query returned null cursor for number: " + phoneNumber);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException getting last call subscriptionId: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error getting last call subscriptionId: " + e.getMessage());
        }
        Log.d(TAG, "Final subscription ID for " + phoneNumber + ": " + subId);
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
                        if (isValidSubscriptionId(context, possibleSubId)) {
                            Log.d(TAG, "Extracted subscription ID " + possibleSubId + " from phone account: " + phoneAccountId);
                            return possibleSubId;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Log.d(TAG, "Could not extract subscription ID from phone account: " + phoneAccountId);
        } catch (Exception e) {
            Log.e(TAG, "Error extracting subscription ID from phone account: " + e.getMessage());
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    private static boolean isValidSubscriptionId(Context context, int subscriptionId) {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "Error validating subscription ID: " + e.getMessage());
        }
        return false;
    }

    private int getCallType(String phoneNumber) {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_CALL_LOG permission not granted");
                return -1;
            }

            String[] projection = {
                    CallLog.Calls.TYPE,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE
            };
            String selection = CallLog.Calls.NUMBER + "=?";
            String[] selectionArgs = {phoneNumber};
            String sortOrder = CallLog.Calls.DATE + " DESC";

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
                        int typeColumn = cursor.getColumnIndex(CallLog.Calls.TYPE);
                        return cursor.getInt(typeColumn);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading call log: " + e.getMessage());
        }
        return -1;
    }

    private boolean checkWhitelist(String phoneNumber) {
        if (mDatabase == null || currentUserId == null) return true;

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
                                    }
                                    lock.notify();
                                } else {
                                    lock.notify();
                                }
                            }
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError databaseError) {
                            synchronized (lock) {
                                Log.e(TAG, "Error checking whitelist: " + databaseError.getMessage());
                                lock.notify();
                            }
                        }
                    });

            synchronized (lock) {
                try {
                    lock.wait(5000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while checking whitelist", e);
                }
            }
            return isWhitelisted[0];
        } catch (Exception e) {
            Log.e(TAG, "Error checking whitelist: " + e.getMessage());
            return true;
        }
    }

    private void logMessageSent(String phoneNumber, String messageType, int subscriptionId) {
        if (mDatabase == null || currentUserId == null) return;

        try {
            Map<String, Object> messageLog = new HashMap<>();
            messageLog.put("phoneNumber", phoneNumber);
            messageLog.put("messageType", messageType);
            messageLog.put("subscriptionId", subscriptionId);
            messageLog.put("timestamp", System.currentTimeMillis());
            messageLog.put("simInfo", MessageHandler.getSimDisplayName(this, subscriptionId));

            mDatabase.child("users").child(currentUserId).child("sentMessages").push()
                    .setValue(messageLog)
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Logged sent message: " + messageType + " to " + phoneNumber))
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to log sent message: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "Error logging sent message: " + e.getMessage());
        }
    }

    private void registerPermissionRevokedReceiver() {
        IntentFilter filter = new IntentFilter("com.lsoysapp.callresponderuser.PERMISSION_REVOKED");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.w(TAG, "Permissions revoked, stopping service");
                stopSelf();
                Intent broadcastIntent = new Intent("com.lsoysapp.callresponderuser.REQUEST_PERMISSIONS");
                context.sendBroadcast(broadcastIntent);
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
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
            WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME);
            Log.d(TAG, "CallStateService destroyed");
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage());
        }
    }
}