package com.lsoysapp.callresponderuser;

import java.text.SimpleDateFormat;
import java.util.Calendar;
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
     * Check if subscription is still valid using string date
     */
    public static boolean isSubscriptionValid(String subscriptionEndDate) {
        if (subscriptionEndDate == null || subscriptionEndDate.isEmpty()) {
            return false;
        }

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date endDate = dateFormat.parse(subscriptionEndDate);
            return endDate != null && endDate.getTime() > System.currentTimeMillis();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Calculate subscription end time based on plan type and duration in months
     */
    public static long calculateSubscriptionEndTime(String planType, int durationInMonths) {
        Calendar calendar = Calendar.getInstance();

        if (planType.toLowerCase().contains("monthly")) {
            // Duration in months
            calendar.add(Calendar.MONTH, durationInMonths);
        } else if (planType.toLowerCase().contains("yearly")) {
            // Duration in years (multiply months by 12)
            calendar.add(Calendar.YEAR, durationInMonths / 12);
        } else {
            // Default to months
            calendar.add(Calendar.MONTH, durationInMonths);
        }

        return calendar.getTimeInMillis();
    }

    /**
     * Calculate subscription end date string based on plan type and duration in months
     */
    public static String calculateSubscriptionEndDate(String planType, int durationInMonths) {
        Calendar calendar = Calendar.getInstance();

        if (planType.toLowerCase().contains("monthly")) {
            calendar.add(Calendar.MONTH, durationInMonths);
        } else if (planType.toLowerCase().contains("yearly")) {
            calendar.add(Calendar.YEAR, durationInMonths / 12);
        } else {
            calendar.add(Calendar.MONTH, durationInMonths);
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return dateFormat.format(calendar.getTime());
    }

    /**
     * Format subscription end time to readable string
     */
    public static String formatSubscriptionEndTime(long endTime) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());
        return sdf.format(new Date(endTime));
    }

    /**
     * Format subscription end date string to readable format
     */
    public static String formatSubscriptionEndDate(String endDateString) {
        if (endDateString == null || endDateString.isEmpty()) {
            return "N/A";
        }

        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            SimpleDateFormat outputFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
            Date date = inputFormat.parse(endDateString);
            return date != null ? outputFormat.format(date) : "N/A";
        } catch (Exception e) {
            return "N/A";
        }
    }

    /**
     * Get remaining days in subscription using timestamp
     */
    public static int getRemainingDays(long subscriptionEndTime) {
        long currentTime = System.currentTimeMillis();
        if (subscriptionEndTime <= currentTime) {
            return 0;
        }

        long diff = subscriptionEndTime - currentTime;
        return (int) Math.ceil((double) diff / (24 * 60 * 60 * 1000));
    }

    /**
     * Get remaining days in subscription using date string
     */
    public static int getRemainingDays(String subscriptionEndDate) {
        if (subscriptionEndDate == null || subscriptionEndDate.isEmpty()) {
            return 0;
        }

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date endDate = dateFormat.parse(subscriptionEndDate);
            if (endDate == null) return 0;

            long currentTime = System.currentTimeMillis();
            long endTime = endDate.getTime();

            if (endTime <= currentTime) {
                return 0;
            }

            long diff = endTime - currentTime;
            return (int) Math.ceil((double) diff / (24 * 60 * 60 * 1000));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get remaining months in subscription
     */
    public static int getRemainingMonths(long subscriptionEndTime) {
        if (subscriptionEndTime <= System.currentTimeMillis()) {
            return 0;
        }

        try {
            Calendar currentCal = Calendar.getInstance();
            Calendar endCal = Calendar.getInstance();
            endCal.setTimeInMillis(subscriptionEndTime);

            int months = 0;
            while (currentCal.before(endCal)) {
                currentCal.add(Calendar.MONTH, 1);
                if (currentCal.before(endCal) || currentCal.equals(endCal)) {
                    months++;
                }
            }

            return months;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get remaining months in subscription using date string
     */
    public static int getRemainingMonths(String subscriptionEndDate) {
        if (subscriptionEndDate == null || subscriptionEndDate.isEmpty()) {
            return 0;
        }

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date endDate = dateFormat.parse(subscriptionEndDate);
            if (endDate == null) return 0;

            Calendar currentCal = Calendar.getInstance();
            Calendar endCal = Calendar.getInstance();
            endCal.setTime(endDate);

            if (endCal.before(currentCal)) {
                return 0;
            }

            int months = 0;
            while (currentCal.before(endCal)) {
                currentCal.add(Calendar.MONTH, 1);
                if (currentCal.before(endCal) || currentCal.equals(endCal)) {
                    months++;
                }
            }

            return months;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Check if subscription is about to expire (within 3 days)
     */
    public static boolean isSubscriptionExpiringSoon(long subscriptionEndTime) {
        return getRemainingDays(subscriptionEndTime) <= 3 && getRemainingDays(subscriptionEndTime) > 0;
    }

    /**
     * Check if subscription is about to expire using date string
     */
    public static boolean isSubscriptionExpiringSoon(String subscriptionEndDate) {
        return getRemainingDays(subscriptionEndDate) <= 3 && getRemainingDays(subscriptionEndDate) > 0;
    }

    /**
     * Get subscription status message
     */
    public static String getSubscriptionStatusMessage(String subscriptionEndDate) {
        if (subscriptionEndDate == null || subscriptionEndDate.isEmpty()) {
            return "No active subscription";
        }

        int remainingDays = getRemainingDays(subscriptionEndDate);
        int remainingMonths = getRemainingMonths(subscriptionEndDate);

        if (remainingDays <= 0) {
            return "Subscription expired";
        } else if (remainingMonths >= 1) {
            return remainingMonths + " month" + (remainingMonths > 1 ? "s" : "") + " remaining";
        } else if (remainingDays <= 3) {
            return "Expires in " + remainingDays + " day" + (remainingDays > 1 ? "s" : "");
        } else {
            return remainingDays + " days remaining";
        }
    }

    /**
     * Convert days to months for display
     */
    public static int convertDaysToMonths(int days) {
        return (int) Math.round((double) days / 30);
    }

    /**
     * Convert months to days for calculations
     */
    public static int convertMonthsToDays(int months) {
        return months * 30;
    }

    /**
     * Get subscription color based on remaining time
     */
    public static int getSubscriptionStatusColor(String subscriptionEndDate) {
        int remainingDays = getRemainingDays(subscriptionEndDate);

        if (remainingDays <= 0) {
            return android.R.color.holo_red_dark; // Expired
        } else if (remainingDays <= 3) {
            return android.R.color.holo_orange_dark; // Expiring soon
        } else {
            return android.R.color.holo_green_dark; // Active
        }
    }
}