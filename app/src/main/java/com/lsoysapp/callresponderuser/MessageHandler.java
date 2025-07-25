package com.lsoysapp.callresponderuser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MessageHandler {

    private static final String TAG = "MessageHandler";

    public static void sendAfterCallMessage(Context context, String number, int subscriptionId) {
        sendMessage(context, number, "after_call", subscriptionId);
    }

    public static void sendBusyMessage(Context context, String number, int subscriptionId) {
        sendMessage(context, number, "busy", subscriptionId);
    }

    public static void sendCutMessage(Context context, String number, int subscriptionId) {
        sendMessage(context, number, "cut", subscriptionId);
    }

    public static void sendSwitchedOffMessage(Context context, String number, int subscriptionId) {
        sendMessage(context, number, "switched_off", subscriptionId);
    }

    public static void sendOutgoingMissedMessage(Context context, String number, int subscriptionId) {
        sendMessage(context, number, "outgoing_missed", subscriptionId);
    }

    public static void sendAfterCallMessage(Context context, String number) {
        sendMessage(context, number, "after_call", -1);
    }

    public static void sendBusyMessage(Context context, String number) {
        sendMessage(context, number, "busy", -1);
    }

    public static void sendCutMessage(Context context, String number) {
        sendMessage(context, number, "cut", -1);
    }

    public static void sendSwitchedOffMessage(Context context, String number) {
        sendMessage(context, number, "switched_off", -1);
    }

    public static void sendOutgoingMissedMessage(Context context, String number) {
        sendMessage(context, number, "outgoing_missed", -1);
    }

    public static void sendSMS(Context context, String number, String message, String messageType) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            String simInfo = "Default SIM";

            if (message.length() > 160) {
                List<String> parts = smsManager.divideMessage(message);
                ArrayList<String> messageParts = new ArrayList<>(parts);
                smsManager.sendMultipartTextMessage(number, null, messageParts, null, null);
                Log.d(TAG, "Multi-part SMS (" + messageType + ") sent to " + number + " via " + simInfo);
            } else {
                smsManager.sendTextMessage(number, null, message, null, null);
                Log.d(TAG, "SMS (" + messageType + ") sent to " + number + " via " + simInfo);
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - SMS permission not granted: " + e.getMessage());
            Toast.makeText(context, "SMS permission not granted", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "SMS sending failed for " + messageType + ": " + e.getMessage());
            e.printStackTrace();
            Toast.makeText(context, "Failed to send SMS: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    static void sendMessage(Context context, String number, String key, int subscriptionId) {
        SharedPreferences prefs = context.getSharedPreferences("CallPrefs", Context.MODE_PRIVATE);

        String defaultMessage;
        switch (key) {
            case "after_call":
                defaultMessage = "Thanks for calling!";
                break;
            case "cut":
                defaultMessage = "Sorry, your call was cut. I'll call you back.";
                break;
            case "busy":
                defaultMessage = "I'm busy right now, will call you back soon.";
                break;
            case "switched_off":
                defaultMessage = "My phone was switched off. I'll call you back.";
                break;
            case "outgoing_missed":
                defaultMessage = "I was trying to call you";
                break;
            default:
                defaultMessage = "Hi, I'll get back to you soon!";
                break;
        }

        String msg = prefs.getString(key, defaultMessage);

        try {
            SmsManager smsManager;
            String simInfo = "";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && subscriptionId != -1) {
                smsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
                simInfo = getSimDisplayName(context, subscriptionId);
                Log.d(TAG, "Sending SMS (" + key + ") via SIM: " + simInfo + " (ID: " + subscriptionId + ")");
            } else {
                smsManager = SmsManager.getDefault();
                simInfo = "Default SIM";
                Log.d(TAG, "Sending SMS (" + key + ") via default SIM");
            }

            if (msg.length() > 160) {
                List<String> parts = smsManager.divideMessage(msg);
                ArrayList<String> messageParts = new ArrayList<>(parts);
                smsManager.sendMultipartTextMessage(number, null, messageParts, null, null);
                Log.d(TAG, "Multi-part SMS (" + key + ") sent to " + number + " via " + simInfo);
            } else {
                smsManager.sendTextMessage(number, null, msg, null, null);
                Log.d(TAG, "SMS (" + key + ") sent to " + number + " via " + simInfo);
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - SMS permission not granted: " + e.getMessage());
            Toast.makeText(context, "SMS permission not granted", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid subscription ID: " + e.getMessage());
            try {
                SmsManager defaultSms = SmsManager.getDefault();
                defaultSms.sendTextMessage(number, null, msg, null, null);
                Log.d(TAG, key.replace("_", " ") + " message sent to " + number + " via default SIM");
            } catch (Exception fallbackException) {
                Log.e(TAG, "Fallback SMS failed: " + fallbackException.getMessage());
                Toast.makeText(context, "Failed to send SMS: " + fallbackException.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS sending failed for " + key + ": " + e.getMessage());
            e.printStackTrace();
            Toast.makeText(context, "Failed to send SMS: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    static String getSimDisplayName(Context context, int subscriptionId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager subscriptionManager = SubscriptionManager.from(context);

                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return "SIM " + subscriptionId;
                }

                List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();

                if (subscriptions != null) {
                    for (SubscriptionInfo info : subscriptions) {
                        if (info.getSubscriptionId() == subscriptionId) {
                            String displayName = info.getDisplayName().toString();
                            String carrierName = info.getCarrierName().toString();
                            int simSlot = info.getSimSlotIndex() + 1;

                            if (displayName != null && !displayName.isEmpty()) {
                                return displayName + " (SIM " + simSlot + ")";
                            } else if (carrierName != null && !carrierName.isEmpty()) {
                                return carrierName + " (SIM " + simSlot + ")";
                            } else {
                                return "SIM " + simSlot;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting SIM display name: " + e.getMessage());
        }

        return "SIM " + subscriptionId;
    }

    public static void logDualSimInfo(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager subscriptionManager = SubscriptionManager.from(context);

                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "READ_PHONE_STATE permission not granted");
                    return;
                }

                List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();

                if (subscriptions != null) {
                    Log.d(TAG, "=== SIM Information ===");
                    Log.d(TAG, "Number of active SIMs: " + subscriptions.size());

                    for (int i = 0; i < subscriptions.size(); i++) {
                        SubscriptionInfo info = subscriptions.get(i);
                        Log.d(TAG, "SIM " + (i + 1) + ":");
                        Log.d(TAG, "  Subscription ID: " + info.getSubscriptionId());
                        Log.d(TAG, "  Display Name: " + info.getDisplayName());
                        Log.d(TAG, "  Carrier Name: " + info.getCarrierName());
                        Log.d(TAG, "  SIM Slot Index: " + info.getSimSlotIndex());
                        Log.d(TAG, "  Phone Number: " + info.getNumber());
                    }
                    Log.d(TAG, "=== End SIM Information ===");
                } else {
                    Log.d(TAG, "No active SIM subscriptions found");
                }
            } else {
                Log.d(TAG, "Android version < 5.1, dual SIM API not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error logging dual SIM info: " + e.getMessage());
        }
    }
}