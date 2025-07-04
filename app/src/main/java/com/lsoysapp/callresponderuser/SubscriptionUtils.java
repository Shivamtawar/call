package com.lsoysapp.callresponderuser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SubscriptionUtils {

    /**
     * Check if subscription is still valid
     */
    public static boolean isSubscriptionValid(Long subscriptionEndTime) {
        if (subscriptionEndTime == null) {
            return false;
        }
        return System.currentTimeMillis() < subscriptionEndTime;
    }

    /**
     * Calculate subscription end time based on plan type and duration
     */
    public static long calculateSubscriptionEndTime(String planType, int duration) {
        long currentTime = System.currentTimeMillis();

        if (planType.toLowerCase().contains("monthly")) {
            // Duration in months
            return currentTime + (duration * 30L * 24L * 60L * 60L * 1000L);
        } else if (planType.toLowerCase().contains("yearly")) {
            // Duration in years
            return currentTime + (duration * 365L * 24L * 60L * 60L * 1000L);
        } else {
            // Custom duration in days
            return currentTime + (duration * 24L * 60L * 60L * 1000L);
        }
    }

    /**
     * Format subscription end time to readable string
     */
    public static String formatSubscriptionEndTime(long endTime) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
        return sdf.format(new Date(endTime));
    }

    /**
     * Get remaining days in subscription
     */
    public static int getRemainingDays(long subscriptionEndTime) {
        long currentTime = System.currentTimeMillis();
        if (subscriptionEndTime <= currentTime) {
            return 0;
        }

        long diff = subscriptionEndTime - currentTime;
        return (int) (diff / (24 * 60 * 60 * 1000));
    }

    /**
     * Check if subscription is about to expire (within 3 days)
     */
    public static boolean isSubscriptionExpiringSoon(long subscriptionEndTime) {
        return getRemainingDays(subscriptionEndTime) <= 3 && getRemainingDays(subscriptionEndTime) > 0;
    }
}