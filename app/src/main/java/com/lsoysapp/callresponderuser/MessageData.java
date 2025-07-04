package com.lsoysapp.callresponderuser;

public class MessageData {
    private String after_call;
    private String cut;
    private String busy;

    public MessageData() {} // Required for Firebase Realtime Database

    public MessageData(String after_call, String cut, String busy) {
        this.after_call = after_call;
        this.cut = cut;
        this.busy = busy;
    }

    public String getAfter_call() {
        return after_call;
    }

    public void setAfter_call(String after_call) {
        this.after_call = after_call;
    }

    public String getCut() {
        return cut;
    }

    public void setCut(String cut) {
        this.cut = cut;
    }

    public String getBusy() {
        return busy;
    }

    public void setBusy(String busy) {
        this.busy = busy;
    }
}