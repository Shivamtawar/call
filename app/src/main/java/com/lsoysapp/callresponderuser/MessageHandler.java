package com.lsoysapp.callresponderuser;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.widget.Toast;

public class MessageHandler {

    public static void sendAfterCallMessage(Context context, String number) {
        sendMessage(context, number, "after_call");
    }

    public static void sendBusyMessage(Context context, String number) {
        sendMessage(context, number, "busy");
    }

    private static void sendMessage(Context context, String number, String key) {
        SharedPreferences prefs = context.getSharedPreferences("CallPrefs", Context.MODE_PRIVATE);
        String msg = prefs.getString(key, "Hi, I’ll get back to you soon!");

        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(number, null, msg, null, null);
            Toast.makeText(context, "Message sent to " + number, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}
