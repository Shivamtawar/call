package com.lsoysapp.callresponderuser;

import android.app.Notification;
import android.content.Context;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;

public class MessageHandler {

    private static final String TAG = "MessageHandler";
    private static DatabaseReference mDatabase;

    public static void logDualSimInfo(Context context) {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted, cannot log SIM info");
                return;
            }

            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions == null || subscriptions.isEmpty()) {
                Log.w(TAG, "No active SIM subscriptions found");
                return;
            }

            Log.d(TAG, "🔍 Active SIMs detected: " + subscriptions.size());
            for (SubscriptionInfo info : subscriptions) {
                int subscriptionId = info.getSubscriptionId();
                int simSlotIndex = info.getSimSlotIndex();
                String displayName = info.getDisplayName() != null ? info.getDisplayName().toString() : "SIM " + (simSlotIndex + 1);
                String carrierName = info.getCarrierName() != null ? info.getCarrierName().toString() : "Unknown Carrier";
                Log.d(TAG, "📱 SIM Slot: " + simSlotIndex + ", SubscriptionId: " + subscriptionId + ", DisplayName: " + displayName + ", Carrier: " + carrierName);
            }

            // Log default SMS subscription
            int defaultSmsSubId = SubscriptionManager.getDefaultSmsSubscriptionId();
            Log.d(TAG, "📲 Default SMS Subscription ID: " + defaultSmsSubId + ", DisplayName: " + getSimDisplayName(context, defaultSmsSubId));
        } catch (Exception e) {
            Log.e(TAG, "❌ Error logging dual SIM info: " + e.getMessage(), e);
        }
    }

    public static String getSimDisplayName(Context context, int subscriptionId) {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted");
                return "Unknown SIM";
            }

            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions != null) {
                for (SubscriptionInfo info : subscriptions) {
                    if (info.getSubscriptionId() == subscriptionId) {
                        String displayName = info.getDisplayName() != null ? info.getDisplayName().toString() : "SIM " + (info.getSimSlotIndex() + 1);
                        Log.d(TAG, "📱 SIM display name for subscriptionId " + subscriptionId + ": " + displayName);
                        return displayName;
                    }
                }
            }
            Log.w(TAG, "⚠️ No SIM found for subscriptionId: " + subscriptionId);
            return "Default SIM";
        } catch (Exception e) {
            Log.e(TAG, "❌ Error getting SIM display name: " + e.getMessage(), e);
            return "Unknown SIM";
        }
    }

    // FIXED: Strict SIM-specific SMS sending - no fallback to other SIMs
    private static SmsManager getSmsManagerForSubscription(Context context, int subscriptionId) {
        try {
            Log.d(TAG, "🔍 Getting SmsManager for subscriptionId: " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");

            // Check SMS permission first
            if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ SEND_SMS permission not granted");
                throw new SecurityException("SEND_SMS permission not granted");
            }

            // STRICT: Only allow valid subscription IDs, no fallback
            if (!isValidSubscriptionId(context, subscriptionId)) {
                Log.e(TAG, "❌ Invalid or inactive subscriptionId: " + subscriptionId);
                throw new IllegalArgumentException("Invalid or inactive subscriptionId: " + subscriptionId);
            }

            // Get SmsManager for the EXACT subscription
            SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId);

            if (smsManager == null) {
                Log.e(TAG, "❌ SmsManager is null for subscriptionId: " + subscriptionId);
                throw new IllegalStateException("SmsManager is null for subscriptionId: " + subscriptionId);
            }

            Log.d(TAG, "✅ Successfully obtained SmsManager for subscriptionId: " + subscriptionId);
            return smsManager;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error getting SmsManager for subscriptionId " + subscriptionId + ": " + e.getMessage(), e);
            throw e;
        }
    }

    // FIXED: Enhanced subscription ID validation - more strict
    private static boolean isValidSubscriptionId(Context context, int subscriptionId) {
        try {
            // STRICT: -1 is NOT considered valid anymore - we want exact SIM matching
            if (subscriptionId == -1) {
                Log.w(TAG, "⚠️ Subscription ID -1 is not allowed - need exact SIM match");
                return false;
            }

            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "⚠️ READ_PHONE_STATE permission not granted, cannot validate subscriptionId");
                return false; // STRICT: Don't assume valid if we can't check
            }

            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();

            if (subscriptions != null) {
                for (SubscriptionInfo info : subscriptions) {
                    if (info.getSubscriptionId() == subscriptionId) {
                        Log.d(TAG, "✅ Validated subscriptionId: " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
                        return true;
                    }
                }
            }

            Log.w(TAG, "❌ Invalid subscriptionId: " + subscriptionId + " (not found in active subscriptions)");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error validating subscriptionId: " + e.getMessage(), e);
            return false;
        }
    }

    private static String getMessageContent(Context context, String messageType) {
        String userId = getCurrentUserId();
        if (userId == null) {
            Log.w(TAG, "⚠️ No authenticated user, returning default message");
            return getDefaultMessage(messageType);
        }

        if (mDatabase == null) {
            mDatabase = FirebaseDatabase.getInstance().getReference();
        }

        final String[] messageContent = {getDefaultMessage(messageType)};
        final Object lock = new Object();

        mDatabase.child("users").child(userId).child("messages").child(messageType)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        synchronized (lock) {
                            if (dataSnapshot.exists()) {
                                String message = dataSnapshot.getValue(String.class);
                                if (message != null && !message.trim().isEmpty()) {
                                    messageContent[0] = message;
                                    Log.d(TAG, "✅ Retrieved custom message for type " + messageType + ": " + message);
                                } else {
                                    Log.w(TAG, "⚠️ Empty or null message for type " + messageType);
                                }
                            } else {
                                Log.w(TAG, "⚠️ No custom message found for type " + messageType + ", using default");
                            }
                            lock.notify();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        synchronized (lock) {
                            Log.e(TAG, "❌ Error fetching message for type " + messageType + ": " + databaseError.getMessage());
                            lock.notify();
                        }
                    }
                });

        synchronized (lock) {
            try {
                lock.wait(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "❌ Interrupted while fetching message", e);
            }
        }
        return messageContent[0];
    }

    private static String getDefaultMessage(String messageType) {
        switch (messageType) {
            case "after_call":
                return "Thank you for calling. I'll get back to you soon!";
            case "cut":
                return "Sorry, I missed your call. Please try again later.";
            case "busy":
                return "I'm currently busy. Will call you back soon.";
            case "outgoing_missed":
                return "Tried reaching you, please call me back when available.";
            default:
                return "This is an automated message.";
        }
    }

    private static String getCurrentUserId() {
        try {
            com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            return user != null ? user.getUid() : null;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error getting current user ID: " + e.getMessage(), e);
            return null;
        }
    }

    // FIXED: STRICT message sending - no fallback, exact SIM only
    private static void sendMessage(Context context, String phoneNumber, String message, int subscriptionId) {
        try {
            String simName = getSimDisplayName(context, subscriptionId);
            Log.d(TAG, "📤 Attempting STRICT message send to " + phoneNumber + " from SIM: " + simName + " (subscriptionId: " + subscriptionId + ")");
            Log.d(TAG, "📝 Message content: " + message);

            // STRICT: Get SmsManager for the EXACT subscription only
            SmsManager smsManager = getSmsManagerForSubscription(context, subscriptionId);

            // Send the message from the specific SIM
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            Log.d(TAG, "✅ SUCCESS: Message sent to " + phoneNumber + " from SIM: " + simName + " (subscriptionId: " + subscriptionId + ")");

        } catch (SecurityException e) {
            Log.e(TAG, "❌ SECURITY ERROR: SMS permission denied for " + phoneNumber + " from SIM " + subscriptionId + ": " + e.getMessage(), e);
            showErrorNotification(context, "Permission Error", "SMS permission denied. Please grant SMS permission to send messages.");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "❌ INVALID SIM ERROR: Cannot send from SIM " + subscriptionId + " to " + phoneNumber + ": " + e.getMessage(), e);
            showErrorNotification(context, "SIM Error", "Selected SIM (ID: " + subscriptionId + ") is not available or inactive. Message not sent.");
        } catch (IllegalStateException e) {
            Log.e(TAG, "❌ SMS SERVICE ERROR: SmsManager state error for SIM " + subscriptionId + ": " + e.getMessage(), e);
            showErrorNotification(context, "SMS Service Error", "SMS service unavailable for SIM " + getSimDisplayName(context, subscriptionId) + ".");
        } catch (Exception e) {
            Log.e(TAG, "❌ GENERAL ERROR: Failed to send message to " + phoneNumber + " from SIM " + subscriptionId + ": " + e.getMessage(), e);
            showErrorNotification(context, "Message Failed", "Failed to send message from " + getSimDisplayName(context, subscriptionId) + ". Error: " + e.getMessage());
        }
    }

    // REMOVED: No more fallback SIM finding - we want exact SIM matching only

    // Enhanced error notification method
    private static void showErrorNotification(Context context, String title, String message) {
        try {
            android.app.NotificationManager manager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Create notification channel if needed
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.app.NotificationChannel channel = new android.app.NotificationChannel(
                            "error_channel",
                            "Error Notifications",
                            android.app.NotificationManager.IMPORTANCE_HIGH
                    );
                    channel.setDescription("Notifications for SMS sending errors");
                    manager.createNotificationChannel(channel);
                }

                Notification notification = new NotificationCompat.Builder(context, "error_channel")
                        .setContentTitle(title)
                        .setContentText(message)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ERROR)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .build();

                manager.notify((int) System.currentTimeMillis(), notification);
                Log.d(TAG, "📲 Showed error notification: " + title + " - " + message);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing notification: " + e.getMessage(), e);
        }
    }

    // FIXED: Public message sending methods with strict SIM enforcement
    public static void sendAfterCallMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 AFTER-CALL: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "after_call");
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static void sendCutMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 CUT: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "cut");
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static void sendBusyMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 BUSY: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "busy");
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static void sendOutgoingMissedMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 OUTGOING MISSED: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "outgoing_missed");
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    // Utility method to get all active subscription IDs for debugging
    public static void logAllActiveSubscriptions(Context context) {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "⚠️ Cannot log subscriptions: READ_PHONE_STATE permission not granted");
                return;
            }

            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();

            if (subscriptions == null || subscriptions.isEmpty()) {
                Log.w(TAG, "⚠️ No active subscriptions found");
                return;
            }

            Log.d(TAG, "=== 📱 ACTIVE SUBSCRIPTIONS DEBUG INFO ===");
            for (SubscriptionInfo info : subscriptions) {
                Log.d(TAG, String.format(
                        "📋 SubID: %d, Slot: %d, DisplayName: %s, Carrier: %s, Country: %s, MCC: %d, MNC: %d",
                        info.getSubscriptionId(),
                        info.getSimSlotIndex(),
                        info.getDisplayName(),
                        info.getCarrierName(),
                        info.getCountryIso(),
                        info.getMcc(),
                        info.getMnc()
                ));
            }

            int defaultSmsSubId = SubscriptionManager.getDefaultSmsSubscriptionId();
            int defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
            int defaultVoiceSubId = SubscriptionManager.getDefaultVoiceSubscriptionId();

            Log.d(TAG, String.format(
                    "📲 Default SubIDs - SMS: %d, Data: %d, Voice: %d",
                    defaultSmsSubId, defaultDataSubId, defaultVoiceSubId
            ));
            Log.d(TAG, "=== 📱 END DEBUG INFO ===");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error logging active subscriptions: " + e.getMessage(), e);
        }
    }

    // ADDED: Method to verify if a specific SIM can send SMS
    public static boolean canSendSmsFromSim(Context context, int subscriptionId) {
        try {
            if (!isValidSubscriptionId(context, subscriptionId)) {
                Log.w(TAG, "❌ Cannot send SMS: Invalid subscription ID " + subscriptionId);
                return false;
            }

            if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "❌ Cannot send SMS: SEND_SMS permission not granted");
                return false;
            }

            SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
            if (smsManager == null) {
                Log.w(TAG, "❌ Cannot send SMS: SmsManager is null for subscription " + subscriptionId);
                return false;
            }

            Log.d(TAG, "✅ SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ") can send SMS");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking SMS capability for SIM " + subscriptionId + ": " + e.getMessage(), e);
            return false;
        }
    }

    // ADDED: Method to get the subscription ID that received a call (for verification)
    public static int getCallSubscriptionId(Context context, String phoneNumber) {
        // This would be called from CallStateService to verify which SIM received the call
        // Implementation would check call log for the most recent call from this number
        return CallStateService.getLastCallSubscriptionIdStatic(context, phoneNumber);
    }
}