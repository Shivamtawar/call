package com.lsoysapp.callresponderuser;

public class SubscriptionPlan {
    private String subscriptionId;
    private String type;
    private int duration;
    private double price;
    private String description;
    private Long createdAt;

    public SubscriptionPlan() {
        // Default constructor required for Firebase
    }

    public SubscriptionPlan(String subscriptionId, String type, int duration, double price, String description, Long createdAt) {
        this.subscriptionId = subscriptionId;
        this.type = type;
        this.duration = duration;
        this.price = price;
        this.description = description;
        this.createdAt = createdAt;
    }

    // Getters and setters
    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public String getDurationText() {
        if (duration <= 30) {
            return duration + " days";
        } else if (duration <= 365) {
            int months = duration / 30;
            return months + " month" + (months > 1 ? "s" : "");
        } else {
            int years = duration / 365;
            return years + " year" + (years > 1 ? "s" : "");
        }
    }
}