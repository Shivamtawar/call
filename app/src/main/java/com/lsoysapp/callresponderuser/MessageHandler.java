package com.lsoysapp.callresponderuser;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MessageHandler {

    private static final String TAG = "MessageHandler";
    private static DatabaseReference mDatabase;
    private static final String PREFS_NAME = "CallResponderPrefs";
    private static final long FIREBASE_TIMEOUT_SECONDS = 15;

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

    private static SmsManager getSmsManagerForSubscription(Context context, int subscriptionId) {
        try {
            Log.d(TAG, "🔍 Getting SmsManager for subscriptionId: " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");

            if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ SEND_SMS permission not granted");
                throw new SecurityException("SEND_SMS permission not granted");
            }

            if (!isValidSubscriptionId(context, subscriptionId)) {
                Log.e(TAG, "❌ Invalid or inactive subscriptionId: " + subscriptionId);
                throw new IllegalArgumentException("Invalid or inactive subscriptionId: " + subscriptionId);
            }

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

    private static boolean isValidSubscriptionId(Context context, int subscriptionId) {
        try {
            if (subscriptionId == -1) {
                Log.w(TAG, "⚠️ Subscription ID -1 is not allowed - need exact SIM match");
                return false;
            }

            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "⚠️ READ_PHONE_STATE permission not granted, cannot validate subscriptionId");
                return false;
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
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isConnected()) {
            Log.w(TAG, "⚠️ No network connection, using local message for type: " + messageType);
            String localMessage = getLocalMessage(context, messageType);
            if (localMessage != null) {
                return localMessage;
            }
            String defaultMessage = getDefaultMessage(messageType);
            Log.w(TAG, "⚠️ No local message, using default for type: " + messageType + ": " + defaultMessage);
            return defaultMessage;
        }

        String userId = getCurrentUserId();
        if (userId == null) {
            Log.w(TAG, "⚠️ No authenticated user, attempting local storage for message type: " + messageType);
            String localMessage = getLocalMessage(context, messageType);
            if (localMessage != null) {
                return localMessage;
            }
            String defaultMessage = getDefaultMessage(messageType);
            Log.w(TAG, "⚠️ No local message, using default for type: " + messageType + ": " + defaultMessage);
            return defaultMessage;
        }

        if (mDatabase == null) {
            mDatabase = FirebaseDatabase.getInstance().getReference();
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] messageContent = {null};

        Log.d(TAG, "🔍 Fetching custom message for type: " + messageType + " for user: " + userId);

        mDatabase.child("users").child(userId).child("messages").child(messageType)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        try {
                            if (dataSnapshot.exists()) {
                                String message = dataSnapshot.getValue(String.class);
                                if (message != null) {
                                    messageContent[0] = message;
                                    Log.d(TAG, "✅ Retrieved custom message for type " + messageType + ": " + (message.isEmpty() ? "<empty>" : message));
                                    saveLocalMessage(context, messageType, message);
                                } else {
                                    Log.w(TAG, "⚠️ Null message in database for type " + messageType);
                                    messageContent[0] = getLocalMessage(context, messageType);
                                }
                            } else {
                                Log.w(TAG, "⚠️ No custom message found in database for type " + messageType);
                                messageContent[0] = getLocalMessage(context, messageType);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error processing message data for type " + messageType + ": " + e.getMessage(), e);
                            messageContent[0] = getLocalMessage(context, messageType);
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        Log.e(TAG, "❌ Database error fetching message for type " + messageType + ": " + databaseError.getMessage());
                        messageContent[0] = getLocalMessage(context, messageType);
                        latch.countDown();
                    }
                });

        try {
            boolean success = latch.await(FIREBASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!success) {
                Log.w(TAG, "⚠️ Timeout (" + FIREBASE_TIMEOUT_SECONDS + "s) waiting for Firebase response for message type: " + messageType);
                messageContent[0] = getLocalMessage(context, messageType);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "❌ Interrupted while fetching message for type " + messageType, e);
            Thread.currentThread().interrupt();
            messageContent[0] = getLocalMessage(context, messageType);
        }

        if (messageContent[0] == null) {
            messageContent[0] = getDefaultMessage(messageType);
            Log.w(TAG, "⚠️ No message found in database or local storage for type " + messageType + ", using default: " + messageContent[0]);
            showErrorNotification(context, "Message Retrieval Failed", "Failed to retrieve message for " + messageType + ". Using default message.");
        }

        Log.d(TAG, "📝 Final message content for " + messageType + ": " + (messageContent[0].isEmpty() ? "<empty>" : messageContent[0]));
        return messageContent[0];
    }

    private static void saveLocalMessage(Context context, String messageType, String message) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(messageType, message);
            editor.apply();
            Log.d(TAG, "💾 Saved message for type " + messageType + " to local storage: " + (message.isEmpty() ? "<empty>" : message));
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving message to local storage for type " + messageType + ": " + e.getMessage(), e);
        }
    }

    private static String getLocalMessage(Context context, String messageType) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String message = prefs.getString(messageType, null);
            if (message != null) {
                Log.d(TAG, "✅ Retrieved local message for type " + messageType + ": " + (message.isEmpty() ? "<empty>" : message));
                return message;
            } else {
                Log.w(TAG, "⚠️ No local message for type " + messageType);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error retrieving local message for type " + messageType + ": " + e.getMessage(), e);
            return null;
        }
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
            case "switched_off":
                return "Sorry, my phone is switched off.";
            default:
                return "This is an automated message.";
        }
    }

    private static String getCurrentUserId() {
        try {
            com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            String userId = user != null ? user.getUid() : null;
            Log.d(TAG, "🔍 Current user ID: " + (userId != null ? userId : "null"));
            if (userId == null) {
                Log.w(TAG, "⚠️ No authenticated user found");
            }
            return userId;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error getting current user ID: " + e.getMessage(), e);
            return null;
        }
    }

    private static void sendMessage(Context context, String phoneNumber, String message, int subscriptionId) {
        try {
            String simName = getSimDisplayName(context, subscriptionId);
            Log.d(TAG, "📤 Attempting STRICT message send to " + phoneNumber + " from SIM: " + simName + " (subscriptionId: " + subscriptionId + ")");
            Log.d(TAG, "📝 Message content: " + (message.isEmpty() ? "<empty>" : message));

            if (message == null || message.trim().isEmpty()) {
                Log.w(TAG, "⚠️ Empty or null message content, using default outgoing message");
                message = "This is an automated message.";
            }

            SmsManager smsManager = getSmsManagerForSubscription(context, subscriptionId);
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

    private static void showErrorNotification(Context context, String title, String message) {
        try {
            android.app.NotificationManager manager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
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

    public static void sendAfterCallMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 AFTER-CALL: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "after_call");
        Log.d(TAG, "📝 Retrieved after_call message: " + (message.isEmpty() ? "<empty>" : message));
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static void sendCutMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 CUT: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "cut");
        Log.d(TAG, "📝 Retrieved cut message: " + (message.isEmpty() ? "<empty>" : message));
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static void sendBusyMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 BUSY: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "busy");
        Log.d(TAG, "📝 Retrieved busy message: " + (message.isEmpty() ? "<empty>" : message));
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static void sendOutgoingMissedMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 OUTGOING MISSED: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "outgoing_missed");
        Log.d(TAG, "📝 Retrieved outgoing_missed message: " + (message.isEmpty() ? "<empty>" : message));

        if (message == null || message.trim().isEmpty()) {
            message = "Tried reaching you, please call me back when available.";
            Log.w(TAG, "⚠️ Using default outgoing missed message: " + message);
        }

        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static void sendSwitchedOffMessage(Context context, String phoneNumber, int subscriptionId) {
        Log.d(TAG, "📞 SWITCHED OFF: Sending message to " + phoneNumber + " from SIM " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
        String message = getMessageContent(context, "switched_off");
        Log.d(TAG, "📝 Retrieved switched_off message: " + (message.isEmpty() ? "<empty>" : message));
        sendMessage(context, phoneNumber, message, subscriptionId);
    }

    public static boolean canSendSmsFromSim(Context context, int subscriptionId) {
        try {
            if (!isValidSubscriptionId(context, subscriptionId)) {
                Log.w(TAG, "⚠️ Cannot send SMS - Invalid subscriptionId: " + subscriptionId);
                return false;
            }

            if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "⚠️ SEND_SMS permission not granted");
                return false;
            }

            SmsManager smsManager = getSmsManagerForSubscription(context, subscriptionId);
            if (smsManager == null) {
                Log.w(TAG, "⚠️ SmsManager is null for subscriptionId: " + subscriptionId);
                return false;
            }

            Log.d(TAG, "✅ Can send SMS from SIM subscriptionId: " + subscriptionId + " (" + getSimDisplayName(context, subscriptionId) + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking SMS capability for subscriptionId " + subscriptionId + ": " + e.getMessage(), e);
            return false;
        }
    }

    public static void logAllActiveSubscriptions(Context context) {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "⚠️ READ_PHONE_STATE permission not granted, cannot log subscriptions");
                return;
            }

            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions == null || subscriptions.isEmpty()) {
                Log.w(TAG, "⚠️ No active SIM subscriptions found");
                return;
            }

            Log.d(TAG, "📋 Logging all active subscriptions:");
            for (SubscriptionInfo info : subscriptions) {
                int subscriptionId = info.getSubscriptionId();
                String displayName = getSimDisplayName(context, subscriptionId);
                Log.d(TAG, "📱 SubscriptionId: " + subscriptionId + ", DisplayName: " + displayName + ", Can send SMS: " + canSendSmsFromSim(context, subscriptionId));
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error logging active subscriptions: " + e.getMessage(), e);
        }
    }

    public static void testMessageRetrieval(Context context) {
        String[] messageTypes = {"after_call", "cut", "busy", "outgoing_missed", "switched_off"};
        Log.d(TAG, "🧪 Testing message retrieval for all message types");
        for (String type : messageTypes) {
            String message = getMessageContent(context, type);
            Log.d(TAG, "🧪 Retrieved message for type " + type + ": " + (message.isEmpty() ? "<empty>" : message));
        }
    }
}