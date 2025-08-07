package com.lsoysapp.callresponderuser;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.ServerValue;

import com.razorpay.Checkout;
import com.razorpay.PaymentResultListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements PaymentResultListener {

    private static final int SMS_PERMISSION_CODE = 1001;
    private static final String TAG = "MainActivity";
    private static final String RAZORPAY_KEY = "rzp_test_EjQc1EWnjKqegB";

    private TextView tvAfterCall, tvCallCut, tvBusy, tvSwitchedOff, tvOutgoingMissed;
    private ImageButton btnEditAfterCall, btnEditCallCut, btnEditBusy, btnEditSwitchedOff, btnEditOutgoingMissed;
    private Button btnSave, btnRenew;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private TextView tvUserName, tvUserEmail;
    private TextView navPrivacy, navLogout, navWhitelist;
    private TextView tvSubscriptionStatus, tvRemainingDays, tvExpiryDate;
    private View nonSubscribedBanner;
    private RecyclerView rvSubscriptionPlans;
    private SubscriptionPlanAdapter planAdapter;

    private int selectedPlanIndex = -1;
    private androidx.appcompat.app.AlertDialog subscriptionDialog;
    private boolean isSubscribed = false;
    private boolean isFreeTrialActive = false;
    private long freeTrialEndTime = 0;
    private DatabaseReference mDatabase;
    private List<SubscriptionPlan> subscriptionPlans;
    private SubscriptionPlan selectedPlan;
    private String currentUserId;
    private long subscriptionEndTime = 0;
    private String subscriptionType = "";
    private int monthsRemaining = 0;

    private boolean isSubscriptionDataLoaded = false;
    private boolean isUpdatingUI = false;
    private boolean isCallServiceStarted = false;

    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 1002;

    public static class SubscriptionPlan {
        public String id;
        public String type;
        public int duration;
        public double price;
        public String description;
        public long createdAt;

        public SubscriptionPlan(String id, String type, int duration, double price, String description, long createdAt) {
            this.id = id;
            this.type = type;
            this.duration = duration;
            this.price = price;
            this.description = description;
            this.createdAt = createdAt;
        }
    }

    private class SubscriptionPlanAdapter extends RecyclerView.Adapter<SubscriptionPlanAdapter.PlanViewHolder> {
        private List<SubscriptionPlan> plans;

        public SubscriptionPlanAdapter(List<SubscriptionPlan> plans) {
            this.plans = plans;
        }

        @NonNull
        @Override
        public PlanViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_subscription_plan, parent, false);
            return new PlanViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PlanViewHolder holder, int position) {
            SubscriptionPlan plan = plans.get(position);
            holder.tvPlanName.setText(plan.type);
            String durationText = plan.duration == 1 ? "1 month" : plan.duration + " months";
            holder.tvPlanDetails.setText(String.format("₹%.2f for %s\n%s", plan.price, durationText, plan.description));

            holder.cardView.setOnClickListener(v -> {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    SubscriptionPlan currentPlan = plans.get(currentPosition);
                    selectedPlanIndex = currentPosition;
                    selectedPlan = currentPlan;
                    showPaymentConfirmationDialog(currentPlan);
                }
            });
        }

        @Override
        public int getItemCount() {
            return plans.size();
        }

        class PlanViewHolder extends RecyclerView.ViewHolder {
            TextView tvPlanName, tvPlanDetails;
            MaterialCardView cardView;

            PlanViewHolder(View itemView) {
                super(itemView);
                tvPlanName = itemView.findViewById(R.id.tvPlanName);
                tvPlanDetails = itemView.findViewById(R.id.tvPlanDetails);
                cardView = itemView.findViewById(R.id.cardPlan);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            // Set initial UI state to prevent overlap
            View subscribedContent = findViewById(R.id.subscribedContent);
            View nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);
            if (subscribedContent != null && nonSubscribedBanner != null) {
                subscribedContent.setVisibility(View.GONE);
                nonSubscribedBanner.setVisibility(View.GONE);
            }

            Checkout.preload(getApplicationContext());
            Checkout.sdkCheckIntegration(this);

            mDatabase = FirebaseDatabase.getInstance().getReference();
            subscriptionPlans = new ArrayList<>();

            Checkout checkout = new Checkout();
            checkout.setKeyID(RAZORPAY_KEY);

            initializeViews();
            initializeSubscriptionViews();
            setupToolbar();
            setupNavigationDrawer();
            checkBatteryOptimization();
            checkAutoStartPermission();
            setupFirebaseAuth();
            setupNotificationChannel();
            loadMessagesFromPrefs();
            registerPermissionRevokedReceiver();
            setupClickListeners();
            loadSubscriptionPlans();
            requestPermissions();
            registerPermissionRequestReceiver();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing app", e);
            Toast.makeText(this, "Error initializing app: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initializeViews() {
        try {
            drawerLayout = findViewById(R.id.drawerLayout);
            navigationView = findViewById(R.id.navigationView);
            tvAfterCall = findViewById(R.id.tvAfterCall);
            tvCallCut = findViewById(R.id.tvCallCut);
            tvBusy = findViewById(R.id.tvBusy);
            tvSwitchedOff = findViewById(R.id.tvSwitchedOff);
            tvOutgoingMissed = findViewById(R.id.tvOutgoingMissed);
            btnEditAfterCall = findViewById(R.id.btnEditAfterCall);
            btnEditCallCut = findViewById(R.id.btnEditCallCut);
            btnEditBusy = findViewById(R.id.btnEditBusy);
            btnEditSwitchedOff = findViewById(R.id.btnEditSwitchedOff);
            btnEditOutgoingMissed = findViewById(R.id.btnEditOutgoingMissed);
            btnSave = findViewById(R.id.btnSave);
            nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);
            rvSubscriptionPlans = findViewById(R.id.rvSubscriptionPlans);

            if (tvAfterCall == null || tvCallCut == null || tvBusy == null || tvSwitchedOff == null ||
                    tvOutgoingMissed == null || btnEditAfterCall == null || btnEditCallCut == null ||
                    btnEditBusy == null || btnEditSwitchedOff == null || btnEditOutgoingMissed == null ||
                    btnSave == null || nonSubscribedBanner == null || rvSubscriptionPlans == null) {
                Toast.makeText(this, "Error: Some views not found in layout", Toast.LENGTH_LONG).show();
                return;
            }

            planAdapter = new SubscriptionPlanAdapter(subscriptionPlans);
            rvSubscriptionPlans.setLayoutManager(new LinearLayoutManager(this));
            rvSubscriptionPlans.setAdapter(planAdapter);
        } catch (Exception e) {
            Toast.makeText(this, "Error initializing views: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupToolbar() {
        try {
            MaterialToolbar toolbar = findViewById(R.id.topAppBar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                toolbar.getMenu().clear();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error setting up toolbar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeSubscriptionViews() {
        try {
            tvSubscriptionStatus = findViewById(R.id.tvSubscriptionStatus);
            tvRemainingDays = findViewById(R.id.tvRemainingDays);
            tvExpiryDate = findViewById(R.id.tvExpiryDate);
            btnRenew = findViewById(R.id.btnRenew);

            if (btnRenew != null) {
                btnRenew.setOnClickListener(v -> showRenewalDialog());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing subscription views: " + e.getMessage());
        }
    }

    private void setupNavigationDrawer() {
        try {
            if (drawerLayout == null) {
                Toast.makeText(this, "Navigation drawer not found", Toast.LENGTH_SHORT).show();
                return;
            }

            MaterialToolbar toolbar = findViewById(R.id.topAppBar);
            if (toolbar != null) {
                ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                        this, drawerLayout, toolbar,
                        android.R.string.ok,
                        android.R.string.cancel
                );
                drawerLayout.addDrawerListener(toggle);
                toggle.syncState();
            }

            navPrivacy = findViewById(R.id.nav_privacy);
            navLogout = findViewById(R.id.nav_logout);
            navWhitelist = findViewById(R.id.nav_whitelist);
            tvUserName = findViewById(R.id.tvUserName);
            tvUserEmail = findViewById(R.id.tvUserEmail);

            if (tvUserName == null || tvUserEmail == null || navWhitelist == null) {
                Toast.makeText(this, "Navigation header views not found", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error setting up navigation: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setupFirebaseAuth() {
        try {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                currentUserId = currentUser.getUid();
                String name = currentUser.getDisplayName();
                String email = currentUser.getEmail();

                if (tvUserName != null) {
                    tvUserName.setText(name != null ? name : "Unknown User");
                }
                if (tvUserEmail != null) {
                    tvUserEmail.setText(email != null ? email : "No Email");
                }

                checkUserStatus(currentUserId);
            } else {
                Toast.makeText(this, "No user authenticated", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Firebase auth error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        "call_channel",
                        "Call Monitoring",
                        NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Notification channel setup failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupClickListeners() {
        try {
            if (navLogout != null) {
                navLogout.setOnClickListener(v -> {
                    try {
                        // Stop the call monitoring service before logout
                        stopCallStateService();
                        FirebaseAuth.getInstance().signOut();
                        Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
                        if (drawerLayout != null) {
                            drawerLayout.closeDrawers();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Logout error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            if (navPrivacy != null) {
                navPrivacy.setOnClickListener(v -> {
                    try {
                        if (mDatabase != null) {
                            mDatabase.child("privacy_policy").child("privacy_link")
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override
                                        public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                                            if (dataSnapshot.exists()) {
                                                String url = dataSnapshot.getValue(String.class);
                                                if (url != null && !url.isEmpty()) {
                                                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                                    startActivity(browserIntent);
                                                } else {
                                                    Toast.makeText(MainActivity.this, "Privacy policy link not found", Toast.LENGTH_SHORT).show();
                                                }
                                            } else {
                                                Toast.makeText(MainActivity.this, "Privacy policy not available", Toast.LENGTH_SHORT).show();
                                            }
                                        }

                                        @Override
                                        public void onCancelled(@NonNull DatabaseError databaseError) {
                                            Toast.makeText(MainActivity.this, "Failed to load privacy policy", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        }
                        if (drawerLayout != null) {
                            drawerLayout.closeDrawers();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Privacy policy error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            if (navWhitelist != null) {
                navWhitelist.setOnClickListener(v -> {
                    try {
                        Intent intent = new Intent(MainActivity.this, WhitelistActivity.class);
                        startActivity(intent);
                        if (drawerLayout != null) {
                            drawerLayout.closeDrawers();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Error opening whitelist: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            if (btnSave != null) {
                btnSave.setOnClickListener(v -> {
                    if (!isSubscribed && !isFreeTrialActive) {
                        Toast.makeText(this, "This feature is only available to subscribed or trial users.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    saveMessages();
                });
            }

            if (btnEditAfterCall != null) {
                btnEditAfterCall.setOnClickListener(v -> {
                    if (!isSubscribed && !isFreeTrialActive) {
                        Toast.makeText(this, "This feature is only available to subscribed or trial users.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showEditDialog("after_call", tvAfterCall.getText().toString());
                });
            }
            if (btnEditCallCut != null) {
                btnEditCallCut.setOnClickListener(v -> {
                    if (!isSubscribed && !isFreeTrialActive) {
                        Toast.makeText(this, "This feature is only available to subscribed or trial users.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showEditDialog("cut", tvCallCut.getText().toString());
                });
            }
            if (btnEditBusy != null) {
                btnEditBusy.setOnClickListener(v -> {
                    if (!isSubscribed && !isFreeTrialActive) {
                        Toast.makeText(this, "This feature is only available to subscribed or trial users.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showEditDialog("busy", tvBusy.getText().toString());
                });
            }
            if (btnEditSwitchedOff != null) {
                btnEditSwitchedOff.setOnClickListener(v -> {
                    if (!isSubscribed && !isFreeTrialActive) {
                        Toast.makeText(this, "This feature is only available to subscribed or trial users.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showEditDialog("switched_off", tvSwitchedOff.getText().toString());
                });
            }
            if (btnEditOutgoingMissed != null) {
                btnEditOutgoingMissed.setOnClickListener(v -> {
                    if (!isSubscribed && !isFreeTrialActive) {
                        Toast.makeText(this, "This feature is only available to subscribed or trial users.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showEditDialog("outgoing_missed", tvOutgoingMissed.getText().toString());
                });
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error setting up click listeners: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showEditDialog(String messageKey, String currentMessage) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_message, null);
        TextInputLayout inputLayout = dialogView.findViewById(R.id.inputLayoutMessage);
        TextInputEditText editText = dialogView.findViewById(R.id.editMessage);

        String hintText;
        String dialogTitle;
        switch (messageKey) {
            case "after_call":
                hintText = "After Call Message";
                dialogTitle = "Edit After Call Message";
                break;
            case "cut":
                hintText = "Missed Call Message";
                dialogTitle = "Edit Missed Call Message";
                break;
            case "busy":
                hintText = "Busy Message";
                dialogTitle = "Edit Busy Message";
                break;
            case "switched_off":
                hintText = "Switched Off Message";
                dialogTitle = "Edit Switched Off Message";
                break;
            case "outgoing_missed":
                hintText = "Outgoing Missed Call Message";
                dialogTitle = "Edit Outgoing Missed Call Message";
                break;
            default:
                hintText = "Enter message";
                dialogTitle = "Edit Message";
                break;
        }

        editText.setText(currentMessage);
        inputLayout.setHint(hintText);
        inputLayout.setCounterEnabled(true);
        inputLayout.setCounterMaxLength(160);

        builder.setTitle(dialogTitle)
                .setView(dialogView)
                .setPositiveButton("OK", (dialog, which) -> {
                    String newMessage = editText.getText().toString();
                    if (!newMessage.isEmpty()) {
                        updateMessage(messageKey, currentMessage, newMessage);
                        hideKeyboard();
                    } else {
                        Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateMessage(String messageKey, String oldMessage, String newMessage) {
        try {
            switch (messageKey) {
                case "after_call":
                    tvAfterCall.setText(newMessage);
                    break;
                case "cut":
                    tvCallCut.setText(newMessage);
                    break;
                case "busy":
                    tvBusy.setText(newMessage);
                    break;
                case "switched_off":
                    tvSwitchedOff.setText(newMessage);
                    break;
                case "outgoing_missed":
                    tvOutgoingMissed.setText(newMessage);
                    break;
            }
            logMessageUpdate(messageKey, oldMessage, newMessage);
            saveMessages();
        } catch (Exception e) {
            Toast.makeText(this, "Error updating message: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void logMessageUpdate(String messageKey, String oldMessage, String newMessage) {
        if (mDatabase == null || currentUserId == null) return;

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timestamp = dateFormat.format(new Date());

        Map<String, Object> updateRecord = new HashMap<>();
        updateRecord.put("timestamp", timestamp);
        updateRecord.put("field", messageKey);
        updateRecord.put("oldValue", oldMessage);
        updateRecord.put("newValue", newMessage);

        String updateId = mDatabase.child("users").child(currentUserId).child("messageUpdateHistory").push().getKey();
        mDatabase.child("users").child(currentUserId).child("messageUpdateHistory").child(updateId)
                .setValue(updateRecord)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Message update logged successfully"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to log message update: " + e.getMessage()));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View view = getCurrentFocus();
        if (view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void requestPermissions() {
        try {
            String[] permissions = {
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.CALL_PHONE
            };

            List<String> permissionsToRequest = new ArrayList<>();
            for (String permission : permissions) {
                if (permission.equals(Manifest.permission.READ_PHONE_NUMBERS) && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    continue;
                }
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission);
                }
            }

            SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
            boolean permissionsDialogShown = prefs.getBoolean("permissionsDialogShown", false);

            if (permissionsToRequest.isEmpty()) {
                Log.d(TAG, "All required permissions are granted");
                // Check subscription before starting service
                checkSubscriptionAndStartService();
                if (!permissionsDialogShown) {
                    showPermissionStatusDialog();
                    prefs.edit().putBoolean("permissionsDialogShown", true).apply();
                    Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
                }
            } else {
                Log.d(TAG, "Requesting permissions: " + permissionsToRequest.toString());
                ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), SMS_PERMISSION_CODE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting permissions: " + e.getMessage(), e);
            Toast.makeText(this, "Error requesting permissions: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Check subscription status before starting the call monitoring service
    private void checkSubscriptionAndStartService() {
        Log.d(TAG, "Checking subscription status before starting service: isSubscribed=" + isSubscribed + ", isFreeTrialActive=" + isFreeTrialActive);

        if (isSubscribed || isFreeTrialActive) {
            startCallStateService();
        } else {
            Log.d(TAG, "Service not started - user not subscribed or in free trial");
            // Optionally notify user
            if (isSubscriptionDataLoaded) {
                Toast.makeText(this, "Subscribe or start free trial to enable call monitoring", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCallStateService() {
        try {
            // Prevent multiple service starts
            if (isCallServiceStarted) {
                Log.d(TAG, "Call service already started");
                return;
            }

            // Double check subscription status
            if (!isSubscribed && !isFreeTrialActive) {
                Log.d(TAG, "Cannot start service - no active subscription or trial");
                return;
            }

            MessageHandler.logDualSimInfo(this);
            Intent serviceIntent = new Intent(this, CallStateService.class);

            // Add subscription status to intent
            serviceIntent.putExtra("isSubscribed", isSubscribed);
            serviceIntent.putExtra("isFreeTrialActive", isFreeTrialActive);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            isCallServiceStarted = true;
            Toast.makeText(this, "Call monitoring service started", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Call monitoring service started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Service start error: " + e.getMessage());
            Toast.makeText(this, "Service start error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopCallStateService() {
        try {
            Intent serviceIntent = new Intent(this, CallStateService.class);
            stopService(serviceIntent);
            isCallServiceStarted = false;
            Log.d(TAG, "Call monitoring service stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping service: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBatteryOptimization();
        if (!hasAllPermissions()) {
            SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
            prefs.edit().putBoolean("permissionsDialogShown", false).apply();
            requestPermissions();
        } else if (isSubscriptionDataLoaded) {
            updateSubscriptionUI();
            // Check if service should be running
            checkSubscriptionAndStartService();
        } else {
            Log.d(TAG, "onResume: Subscription data not loaded yet, skipping UI update");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't stop the service in onPause - it should run in background
        Log.d(TAG, "onPause: Keeping service running in background");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Only stop service if the app is being completely destroyed
        // and not due to configuration change
        if (isFinishing()) {
            Log.d(TAG, "onDestroy: App finishing, stopping service");
            stopCallStateService();
        }
    }

    private void registerPermissionRequestReceiver() {
        IntentFilter filter = new IntentFilter("com.lsoysapp.callresponderuser.REQUEST_PERMISSIONS");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                requestPermissions();
            }
        }, filter);
    }

    private boolean hasAllPermissions() {
        String[] permissions = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE
        };
        for (String permission : permissions) {
            if (permission.equals(Manifest.permission.READ_PHONE_NUMBERS) && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                continue;
            }
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_CODE) {
            boolean allGranted = true;
            Map<String, Boolean> permissionStatus = new HashMap<>();

            for (int i = 0; i < permissions.length; i++) {
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                permissionStatus.put(permissions[i], granted);
                if (!granted) {
                    allGranted = false;
                }
                Log.d(TAG, "Permission: " + permissions[i] + ", Granted: " + granted);
            }

            SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
            boolean permissionsDialogShown = prefs.getBoolean("permissionsDialogShown", false);

            if (allGranted) {
                Log.d(TAG, "All permissions granted successfully");
                checkSubscriptionAndStartService(); // Check subscription before starting service
                if (!permissionsDialogShown) {
                    Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
                    showPermissionStatusDialog();
                    prefs.edit().putBoolean("permissionsDialogShown", true).apply();
                }
            } else {
                Log.w(TAG, "Some permissions were denied: " + permissionStatus.toString());
                Toast.makeText(this, "Required permissions denied. Auto-reply SMS will not work.", Toast.LENGTH_LONG).show();
                showPermissionRetryDialog();
                if (!permissionsDialogShown) {
                    showPermissionStatusDialog();
                    prefs.edit().putBoolean("permissionsDialogShown", true).apply();
                }
            }
        }
    }

    private void showPermissionRetryDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Permissions Required")
                .setMessage("This app requires SMS, phone state, call log, and contacts permissions to send auto-reply messages and manage whitelist. Please grant all permissions.")
                .setPositiveButton("Retry", (dialog, which) -> requestPermissions())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkUserStatus(String userId) {
        if (mDatabase == null || userId == null) {
            Toast.makeText(this, "Database or user not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        mDatabase.child("users").child(userId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                try {
                    if (dataSnapshot.exists()) {
                        Boolean enabled = dataSnapshot.child("enabled").getValue(Boolean.class);
                        if (enabled == null) {
                            mDatabase.child("users").child(userId).child("enabled").setValue(true);
                            enabled = true;
                        }

                        if (!enabled) {
                            Toast.makeText(MainActivity.this, "Your access has been disabled by the admin.", Toast.LENGTH_LONG).show();
                            FirebaseAuth.getInstance().signOut();
                            return;
                        }

                        // Check subscription data first
                        DataSnapshot subscriptionSnapshot = dataSnapshot.child("subscription");

                        // Check if user has subscription data
                        if (subscriptionSnapshot.exists()) {
                            Boolean subscribed = subscriptionSnapshot.child("isSubscribed").getValue(Boolean.class);
                            isSubscribed = subscribed != null ? subscribed : false;

                            // Check free trial status from subscription node (updated structure)
                            Boolean freeTrialActive = subscriptionSnapshot.child("freeTrialActive").getValue(Boolean.class);
                            isFreeTrialActive = freeTrialActive != null ? freeTrialActive : false;

                            String freeTrialEndDateStr = subscriptionSnapshot.child("freeTrialEndDate").getValue(String.class);
                            if (freeTrialEndDateStr != null && !freeTrialEndDateStr.isEmpty()) {
                                try {
                                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                    Date endDate = dateFormat.parse(freeTrialEndDateStr);
                                    if (endDate != null) {
                                        freeTrialEndTime = endDate.getTime();
                                        // Verify if trial is actually active
                                        if (isFreeTrialActive && freeTrialEndTime <= System.currentTimeMillis()) {
                                            // Trial expired, update database
                                            updateExpiredFreeTrial(userId);
                                            isFreeTrialActive = false;
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error parsing free trial end date: " + e.getMessage());
                                    freeTrialEndTime = 0;
                                    isFreeTrialActive = false;
                                }
                            }

                            Log.d(TAG, "Existing user - isSubscribed: " + isSubscribed + ", isFreeTrialActive: " + isFreeTrialActive);
                        } else {
                            // New user - no subscription data exists
                            isSubscribed = false;
                            isFreeTrialActive = false;
                            Log.d(TAG, "New user detected - no subscription data");
                        }

                        // Load remaining subscription data
                        loadUserSubscriptionData(userId);
                        loadUserMessages(userId);

                        // For completely new users (no subscription node), start free trial
                        if (!subscriptionSnapshot.exists()) {
                            Log.d(TAG, "Starting free trial for new user");
                            startFreeTrial(userId);
                        }

                    } else {
                        // Brand new user - create user data and start free trial
                        Log.d(TAG, "Brand new user - creating user data");
                        Map<String, Object> userData = new HashMap<>();
                        userData.put("email", FirebaseAuth.getInstance().getCurrentUser().getEmail());
                        userData.put("enabled", true);
                        userData.put("registrationDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

                        mDatabase.child("users").child(userId).setValue(userData)
                                .addOnSuccessListener(aVoid -> {
                                    Log.d(TAG, "User data created, starting free trial");
                                    startFreeTrial(userId);
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(MainActivity.this, "Failed to initialize user: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    isSubscriptionDataLoaded = true;
                                    updateSubscriptionUI();
                                });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing user data: " + e.getMessage());
                    isSubscriptionDataLoaded = true;
                    updateSubscriptionUI();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Failed to verify account status: " + databaseError.getMessage());
                isSubscriptionDataLoaded = true;
                updateSubscriptionUI();
            }
        });
    }

    private void startFreeTrial(String userId) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        Date startDate = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startDate);
        calendar.add(Calendar.DAY_OF_MONTH, 7);
        Date endDate = calendar.getTime();

        Map<String, Object> subscriptionData = new HashMap<>();
        subscriptionData.put("isSubscribed", false);
        subscriptionData.put("freeTrialActive", true);
        subscriptionData.put("freeTrialStartDate", dateFormat.format(startDate));
        subscriptionData.put("freeTrialEndDate", dateFormat.format(endDate));
        subscriptionData.put("lastUpdated", ServerValue.TIMESTAMP);

        mDatabase.child("users").child(userId).child("subscription").setValue(subscriptionData)
                .addOnSuccessListener(aVoid -> {
                    isFreeTrialActive = true;
                    isSubscribed = false;
                    freeTrialEndTime = endDate.getTime();
                    isSubscriptionDataLoaded = true;
                    updateSubscriptionUI();

                    // Start service for free trial users
                    if (hasAllPermissions()) {
                        checkSubscriptionAndStartService();
                    }

                    Toast.makeText(MainActivity.this, "7-day free trial activated!", Toast.LENGTH_LONG).show();
                    Log.d(TAG, "Free trial started successfully");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to start free trial: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Failed to start free trial: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    isSubscriptionDataLoaded = true;
                    updateSubscriptionUI();
                });
    }



    private void updateExpiredFreeTrial(String userId) {
        Map<String, Object> trialData = new HashMap<>();
        trialData.put("freeTrialActive", false);
        trialData.put("lastUpdated", ServerValue.TIMESTAMP);

        mDatabase.child("users").child(userId).child("subscription").updateChildren(trialData)
                .addOnSuccessListener(aVoid -> {
                    isFreeTrialActive = false;
                    updateSubscriptionUI();
                    // Stop service when trial expires
                    stopCallStateService();
                    Log.d(TAG, "Free trial expired and updated");
                })
                .addOnFailureListener(e -> Log.e(TAG, "Failed to update expired free trial: " + e.getMessage()));
    }

    private void updateSubscriptionUI() {
        // Prevent concurrent UI updates
        if (isUpdatingUI) {
            Log.d(TAG, "Skipping updateSubscriptionUI: UI update already in progress");
            return;
        }
        isUpdatingUI = true;

        try {
            // Skip UI update if subscription data isn't loaded yet
            if (!isSubscriptionDataLoaded) {
                Log.d(TAG, "Skipping updateSubscriptionUI: Subscription data not loaded yet");
                return;
            }

            View subscribedContent = findViewById(R.id.subscribedContent);
            View nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);

            if (subscribedContent == null || nonSubscribedBanner == null) {
                Log.e(TAG, "Subscription UI views not found");
                Toast.makeText(this, "Error: UI components missing", Toast.LENGTH_SHORT).show();
                return;
            }

            // Explicitly hide both views to prevent overlap
            subscribedContent.setVisibility(View.GONE);
            nonSubscribedBanner.setVisibility(View.GONE);

            Log.d(TAG, "Updating subscription UI - isSubscribed: " + isSubscribed + ", isFreeTrialActive: " + isFreeTrialActive + ", subscriptionEndTime: " + subscriptionEndTime + ", freeTrialEndTime: " + freeTrialEndTime);

            if (isSubscribed && subscriptionEndTime > System.currentTimeMillis()) {
                showActiveSubscriptionUI();
            } else if (isFreeTrialActive && freeTrialEndTime > System.currentTimeMillis()) {
                showActiveTrialUI();
            } else if (isSubscribed && subscriptionEndTime <= System.currentTimeMillis()) {
                showExpiredSubscriptionUI();
            } else if (isFreeTrialActive && freeTrialEndTime <= System.currentTimeMillis()) {
                showExpiredTrialUI();
            } else {
                showNoSubscriptionUI();
            }

            Log.d(TAG, "Subscription UI updated - subscribedContent: " + subscribedContent.getVisibility() + ", nonSubscribedBanner: " + nonSubscribedBanner.getVisibility());
        } catch (Exception e) {
            Log.e(TAG, "Error updating subscription UI: " + e.getMessage());
            Toast.makeText(this, "Error updating UI: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            isUpdatingUI = false; // Release the lock
        }
    }

    private void showActiveSubscriptionUI() {
        View subscribedContent = findViewById(R.id.subscribedContent);
        View nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);

        subscribedContent.setVisibility(View.VISIBLE);
        nonSubscribedBanner.setVisibility(View.GONE);

        if (tvSubscriptionStatus != null && tvRemainingDays != null && tvExpiryDate != null && btnRenew != null) {
            tvSubscriptionStatus.setVisibility(View.VISIBLE);
            tvRemainingDays.setVisibility(View.VISIBLE);
            tvExpiryDate.setVisibility(View.VISIBLE);
            btnRenew.setVisibility(View.VISIBLE);

            boolean isValid = SubscriptionUtils.isSubscriptionValid(subscriptionEndTime);
            int remainingDays = SubscriptionUtils.getRemainingDays(subscriptionEndTime);
            int remainingMonths = SubscriptionUtils.getRemainingMonths(subscriptionEndTime);

            tvSubscriptionStatus.setText("✅ Active Subscription: " + (subscriptionType != null ? subscriptionType : "Premium"));
            tvSubscriptionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));

            if (remainingMonths >= 1) {
                tvRemainingDays.setText(remainingMonths + " month" + (remainingMonths > 1 ? "s" : "") + " remaining");
                tvRemainingDays.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
                btnRenew.setText("Renew Subscription");
                btnRenew.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_blue_bright));
            } else if (remainingDays <= 3) {
                tvRemainingDays.setText(remainingDays + " days remaining ⚠️");
                tvRemainingDays.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                btnRenew.setText("Renew Now");
                btnRenew.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_orange_light));
            } else {
                tvRemainingDays.setText(remainingDays + " days remaining");
                tvRemainingDays.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
                btnRenew.setText("Renew Subscription");
                btnRenew.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_blue_bright));
            }

            String expiryDateStr = SubscriptionUtils.formatSubscriptionEndTime(subscriptionEndTime);
            tvExpiryDate.setText("Expires: " + expiryDateStr);
            tvExpiryDate.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        }

        Log.d(TAG, "Active subscription UI shown");
    }

    private void showActiveTrialUI() {
        View subscribedContent = findViewById(R.id.subscribedContent);
        View nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);

        subscribedContent.setVisibility(View.VISIBLE);
        nonSubscribedBanner.setVisibility(View.GONE);

        if (tvSubscriptionStatus != null && tvRemainingDays != null && tvExpiryDate != null && btnRenew != null) {
            tvSubscriptionStatus.setVisibility(View.VISIBLE);
            tvRemainingDays.setVisibility(View.VISIBLE);
            tvExpiryDate.setVisibility(View.VISIBLE);
            btnRenew.setVisibility(View.VISIBLE);

            int remainingDays = (int) ((freeTrialEndTime - System.currentTimeMillis()) / (1000 * 60 * 60 * 24));

            tvSubscriptionStatus.setText("🎁 Free Trial Active");
            tvSubscriptionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));

            tvRemainingDays.setText(remainingDays + " days remaining in trial");
            tvRemainingDays.setTextColor(remainingDays <= 2 ?
                    ContextCompat.getColor(this, android.R.color.holo_red_dark) :
                    ContextCompat.getColor(this, android.R.color.holo_blue_dark));

            tvExpiryDate.setText("Trial Expires: " + SubscriptionUtils.formatSubscriptionEndTime(freeTrialEndTime));
            tvExpiryDate.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));

            btnRenew.setText("Subscribe Now");
            btnRenew.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
        }

        Log.d(TAG, "Active trial UI shown");
    }

    private void showExpiredSubscriptionUI() {
        View subscribedContent = findViewById(R.id.subscribedContent);
        View nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);

        subscribedContent.setVisibility(View.GONE);
        nonSubscribedBanner.setVisibility(View.VISIBLE);

        if (tvSubscriptionStatus != null && tvRemainingDays != null) {
            tvSubscriptionStatus.setVisibility(View.VISIBLE);
            tvRemainingDays.setVisibility(View.VISIBLE);
            if (tvExpiryDate != null) tvExpiryDate.setVisibility(View.GONE);
            if (btnRenew != null) btnRenew.setVisibility(View.GONE);

            tvSubscriptionStatus.setText("⚠️ Subscription Expired");
            tvSubscriptionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            tvRemainingDays.setText("Please renew to continue using the service");
            tvRemainingDays.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        }

        Log.d(TAG, "Expired subscription UI shown");
    }

    private void showExpiredTrialUI() {
        View subscribedContent = findViewById(R.id.subscribedContent);
        View nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);

        subscribedContent.setVisibility(View.GONE);
        nonSubscribedBanner.setVisibility(View.VISIBLE);

        if (tvSubscriptionStatus != null && tvRemainingDays != null) {
            tvSubscriptionStatus.setVisibility(View.VISIBLE);
            tvRemainingDays.setVisibility(View.VISIBLE);
            if (tvExpiryDate != null) tvExpiryDate.setVisibility(View.GONE);
            if (btnRenew != null) btnRenew.setVisibility(View.GONE);

            tvSubscriptionStatus.setText("⏰ Free Trial Expired");
            tvSubscriptionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            tvRemainingDays.setText("Subscribe now to continue using the service");
            tvRemainingDays.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        }

        Log.d(TAG, "Expired trial UI shown");
    }

    private void showNoSubscriptionUI() {
        View subscribedContent = findViewById(R.id.subscribedContent);
        View nonSubscribedBanner = findViewById(R.id.nonSubscribedBanner);

        if (subscribedContent != null && nonSubscribedBanner != null) {
            subscribedContent.setVisibility(View.GONE);
            nonSubscribedBanner.setVisibility(View.VISIBLE);
            if (tvSubscriptionStatus != null && tvRemainingDays != null && tvExpiryDate != null && btnRenew != null) {
                tvSubscriptionStatus.setVisibility(View.GONE);
                tvRemainingDays.setVisibility(View.GONE);
                tvExpiryDate.setVisibility(View.GONE);
                btnRenew.setVisibility(View.GONE);
            }

            Log.d(TAG, "No subscription UI shown");
        } else {
            Log.e(TAG, "Failed to show no subscription UI: Views not initialized");
        }
    }

    private void loadUserSubscriptionData(String userId) {
        if (mDatabase == null || userId == null) {
            Log.e(TAG, "Database or userId is null, cannot load subscription data");
            isSubscriptionDataLoaded = true;
            updateSubscriptionUI();
            return;
        }

        mDatabase.child("users").child(userId).child("subscription")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        try {
                            if (dataSnapshot.exists()) {
                                Boolean subscribed = dataSnapshot.child("isSubscribed").getValue(Boolean.class);
                                isSubscribed = subscribed != null ? subscribed : false;

                                subscriptionType = dataSnapshot.child("subscriptionType").getValue(String.class);

                                Long months = dataSnapshot.child("monthsRemaining").getValue(Long.class);
                                monthsRemaining = months != null ? months.intValue() : 0;

                                String endDateStr = dataSnapshot.child("subscriptionEndDate").getValue(String.class);
                                if (endDateStr != null && !endDateStr.isEmpty()) {
                                    try {
                                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                        Date endDate = dateFormat.parse(endDateStr);
                                        if (endDate != null) {
                                            subscriptionEndTime = endDate.getTime();
                                        } else {
                                            subscriptionEndTime = 0;
                                        }
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error parsing subscription end date: " + e.getMessage());
                                        subscriptionEndTime = 0;
                                    }
                                } else {
                                    subscriptionEndTime = 0;
                                }

                                // Update free trial data from subscription node
                                Boolean freeTrialActive = dataSnapshot.child("freeTrialActive").getValue(Boolean.class);
                                isFreeTrialActive = freeTrialActive != null ? freeTrialActive : false;

                                String freeTrialEndDateStr = dataSnapshot.child("freeTrialEndDate").getValue(String.class);
                                if (freeTrialEndDateStr != null && !freeTrialEndDateStr.isEmpty()) {
                                    try {
                                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                        Date endDate = dateFormat.parse(freeTrialEndDateStr);
                                        if (endDate != null) {
                                            freeTrialEndTime = endDate.getTime();
                                        } else {
                                            freeTrialEndTime = 0;
                                        }
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error parsing free trial end date: " + e.getMessage());
                                        freeTrialEndTime = 0;
                                    }
                                } else {
                                    freeTrialEndTime = 0;
                                }

                                SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
                                prefs.edit().putBoolean("isSubscribed", isSubscribed).apply();
                                prefs.edit().putBoolean("isFreeTrialActive", isFreeTrialActive).apply();

                                if (isSubscribed && subscriptionEndTime > 0 && subscriptionEndTime <= System.currentTimeMillis()) {
                                    updateExpiredSubscription(userId);
                                }

                                if (isFreeTrialActive && freeTrialEndTime > 0 && freeTrialEndTime <= System.currentTimeMillis()) {
                                    updateExpiredFreeTrial(userId);
                                }
                            } else {
                                isSubscribed = false;
                                isFreeTrialActive = false;
                                subscriptionEndTime = 0;
                                freeTrialEndTime = 0;
                                subscriptionType = null;
                                monthsRemaining = 0;
                                SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
                                prefs.edit().putBoolean("isSubscribed", false).apply();
                                prefs.edit().putBoolean("isFreeTrialActive", false).apply();
                            }

                            isSubscriptionDataLoaded = true;
                            Log.d(TAG, "Subscription data loaded - isSubscribed: " + isSubscribed + ", isFreeTrialActive: " + isFreeTrialActive + ", subscriptionEndTime: " + subscriptionEndTime + ", freeTrialEndTime: " + freeTrialEndTime);
                            updateSubscriptionUI();

                            // Check if service should be started after loading subscription data
                            if (hasAllPermissions()) {
                                checkSubscriptionAndStartService();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error loading subscription data: " + e.getMessage());
                            isSubscriptionDataLoaded = true;
                            updateSubscriptionUI();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        Log.e(TAG, "Failed to load subscription data: " + databaseError.getMessage());
                        isSubscribed = false;
                        isFreeTrialActive = false;
                        subscriptionEndTime = 0;
                        freeTrialEndTime = 0;
                        isSubscriptionDataLoaded = true;
                        updateSubscriptionUI();
                    }
                });
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
        boolean hasPromptedBatteryOptimization = prefs.getBoolean("hasPromptedBatteryOptimization", false);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String packageName = getPackageName();

        if (!pm.isIgnoringBatteryOptimizations(packageName) && !hasPromptedBatteryOptimization) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Allow Background Service")
                    .setMessage("To ensure call auto-reply works reliably, please allow this app to run in the background without battery restrictions.")
                    .setPositiveButton("Allow", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + packageName));
                        startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
                        prefs.edit().putBoolean("hasPromptedBatteryOptimization", true).apply();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        prefs.edit().putBoolean("hasPromptedBatteryOptimization", true).apply();
                        Toast.makeText(this, "Background service may not work reliably.", Toast.LENGTH_LONG).show();
                    })
                    .setCancelable(false)
                    .show();
        }
    }

     private void checkAutoStartPermission() {
        SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
        boolean hasPromptedAutoStart = prefs.getBoolean("hasPromptedAutoStart", false);

        // Only show dialog once per app installation
        if (!hasPromptedAutoStart) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Enable Auto-Start")
                    .setMessage("To ensure this app works properly in the background, please enable auto-start permission in your device settings. This allows the app to automatically start when your device boots up and continue monitoring calls.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        openAutoStartSettings();
                        prefs.edit().putBoolean("hasPromptedAutoStart", true).apply();
                    })
                    .setNegativeButton("Later", (dialog, which) -> {
                        prefs.edit().putBoolean("hasPromptedAutoStart", true).apply();
                        Toast.makeText(this, "You can enable auto-start later from device settings for better app performance.", Toast.LENGTH_LONG).show();
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void openAutoStartSettings() {
        try {
            String manufacturer = Build.MANUFACTURER.toLowerCase();
            Intent intent = new Intent();

            switch (manufacturer) {
                case "xiaomi":
                case "redmi":
                    try {
                        intent.setComponent(new ComponentName("com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                        startActivity(intent);
                        return;
                    } catch (Exception e) {
                        // Fallback for MIUI
                        try {
                            intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
                            intent.setClassName("com.miui.securitycenter",
                                "com.miui.permcenter.permissions.PermissionsEditorActivity");
                            intent.putExtra("extra_pkgname", getPackageName());
                            startActivity(intent);
                            return;
                        } catch (Exception e2) {
                            Log.e(TAG, "Failed to open Xiaomi auto-start settings", e2);
                        }
                    }
                    break;

                case "oppo":
                    try {
                        intent.setComponent(new ComponentName("com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                        startActivity(intent);
                        return;
                    } catch (Exception e) {
                        try {
                            intent.setComponent(new ComponentName("com.oppo.safe",
                                "com.oppo.safe.permission.startup.StartupAppListActivity"));
                            startActivity(intent);
                            return;
                        } catch (Exception e2) {
                            Log.e(TAG, "Failed to open Oppo auto-start settings", e2);
                        }
                    }
                    break;

                case "vivo":
                    try {
                        intent.setComponent(new ComponentName("com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                        startActivity(intent);
                        return;
                    } catch (Exception e) {
                        try {
                            intent.setComponent(new ComponentName("com.iqoo.secure",
                                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"));
                            startActivity(intent);
                            return;
                        } catch (Exception e2) {
                            Log.e(TAG, "Failed to open Vivo auto-start settings", e2);
                        }
                    }
                    break;

                case "huawei":
                case "honor":
                    try {
                        intent.setComponent(new ComponentName("com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
                        startActivity(intent);
                        return;
                    } catch (Exception e) {
                        try {
                            intent.setComponent(new ComponentName("com.huawei.systemmanager",
                                "com.huawei.systemmanager.optimize.process.ProtectActivity"));
                            startActivity(intent);
                            return;
                        } catch (Exception e2) {
                            Log.e(TAG, "Failed to open Huawei auto-start settings", e2);
                        }
                    }
                    break;

                case "oneplus":
                    try {
                        intent.setComponent(new ComponentName("com.oneplus.security",
                            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
                        startActivity(intent);
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to open OnePlus auto-start settings", e);
                    }
                    break;

                case "samsung":
                    try {
                        intent = new Intent();
                        intent.setComponent(new ComponentName("com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity"));
                        startActivity(intent);
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to open Samsung battery settings", e);
                    }
                    break;
            }

            // Fallback - try generic auto-start settings
            try {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Please look for auto-start, startup manager, or background app settings", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to open any auto-start settings", e);
                Toast.makeText(this, "Unable to open auto-start settings. Please manually enable auto-start for this app in your device settings.", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error opening auto-start settings", e);
            Toast.makeText(this, "Error opening settings. Please manually enable auto-start permission for this app.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Battery optimization disabled successfully.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Battery optimization not disabled. App may not work reliably.", Toast.LENGTH_LONG).show();
                SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
                prefs.edit().putBoolean("hasPromptedBatteryOptimization", false).apply();
            }
        }
    }

    private void updateExpiredSubscription(String userId) {
        if (mDatabase == null) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("isSubscribed", false);
        updates.put("subscriptionStatus", "expired");
        updates.put("lastUpdated", ServerValue.TIMESTAMP);

        mDatabase.child("users").child(userId).child("subscription")
                .updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    isSubscribed = false;
                    updateSubscriptionUI();
                    // Stop service when subscription expires
                    stopCallStateService();
                })
                .addOnFailureListener(e -> Log.e(TAG, "Failed to update expired subscription", e));
    }

    private void showRenewalDialog() {
        try {
            String message = "";
            if (isSubscribed && subscriptionType != null && !subscriptionType.isEmpty()) {
                message = "Your current subscription (" + subscriptionType + ")";
                int remainingMonths = SubscriptionUtils.getRemainingMonths(subscriptionEndTime);
                if (remainingMonths >= 1) {
                    message += " has " + remainingMonths + " month" + (remainingMonths > 1 ? "s" : "") + " remaining.";
                } else {
                    int remainingDays = SubscriptionUtils.getRemainingDays(subscriptionEndTime);
                    if (remainingDays > 0) {
                        message += " has " + remainingDays + " days remaining.";
                    } else {
                        message += " has expired.";
                    }
                }
                message += "\n\nSelect a plan to renew and extend your subscription.";
            } else if (isFreeTrialActive) {
                message = "Your free trial";
                int remainingDays = (int) ((freeTrialEndTime - System.currentTimeMillis()) / (1000 * 60 * 60 * 24));
                if (remainingDays > 0) {
                    message += " has " + remainingDays + " days remaining.";
                } else {
                    message += " has expired.";
                }
                message += "\n\nSelect a plan to subscribe.";
            } else {
                message = "You have no active subscription or trial.\n\nSelect a plan to subscribe.";
            }

            new MaterialAlertDialogBuilder(this)
                    .setTitle(isFreeTrialActive ? "Subscribe Now" : "Renew Subscription")
                    .setMessage(message)
                    .setPositiveButton(isFreeTrialActive ? "Select Plan" : "Renew Now", (dialog, which) -> {
                        loadSubscriptionPlans();
                        nonSubscribedBanner.setVisibility(View.VISIBLE);
                        findViewById(R.id.subscribedContent).setVisibility(View.GONE);
                    })
                    .setNegativeButton("Later", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing renewal dialog: " + e.getMessage());
        }
    }

    private void loadSubscriptionPlans() {
        if (mDatabase == null) {
            Toast.makeText(this, "Database not available", Toast.LENGTH_SHORT).show();
            return;
        }

        mDatabase.child("subscriptions").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                try {
                    subscriptionPlans.clear();

                    for (DataSnapshot planSnapshot : dataSnapshot.getChildren()) {
                        String subscriptionId = planSnapshot.getKey();
                        String type = planSnapshot.child("type").getValue(String.class);
                        String description = planSnapshot.child("description").getValue(String.class);

                        int duration = 0;
                        Object durationObj = planSnapshot.child("duration").getValue();
                        if (durationObj instanceof Long) {
                            duration = ((Long) durationObj).intValue();
                        } else if (durationObj instanceof String) {
                            try {
                                duration = Integer.parseInt((String) durationObj);
                            } catch (NumberFormatException e) {
                                duration = 0;
                            }
                        }

                        double price = 0.0;
                        Object priceObj = planSnapshot.child("price").getValue();
                        if (priceObj instanceof Double) {
                            price = (Double) priceObj;
                        } else if (priceObj instanceof Long) {
                            price = ((Long) priceObj).doubleValue();
                        } else if (priceObj instanceof String) {
                            try {
                                price = Double.parseDouble((String) priceObj);
                            } catch (NumberFormatException e) {
                                price = 0.0;
                            }
                        }

                        long createdAt = 0;
                        Object createdAtObj = planSnapshot.child("createdAt").getValue();
                        if (createdAtObj instanceof Long) {
                            createdAt = (Long) createdAtObj;
                        } else if (createdAtObj instanceof String) {
                            try {
                                createdAt = Long.parseLong((String) createdAtObj);
                            } catch (NumberFormatException e) {
                                createdAt = System.currentTimeMillis();
                            }
                        }

                        if (type != null && !type.isEmpty() && description != null && !description.isEmpty()) {
                            SubscriptionPlan plan = new SubscriptionPlan(
                                    subscriptionId,
                                    type,
                                    duration,
                                    price,
                                    description,
                                    createdAt
                            );
                            subscriptionPlans.add(plan);
                        }
                    }

                    planAdapter.notifyDataSetChanged();
                    if (subscriptionPlans.isEmpty()) {
                        Toast.makeText(MainActivity.this, "No subscription plans available", Toast.LENGTH_SHORT).show();
                    } else {
                        nonSubscribedBanner.setVisibility(View.VISIBLE);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Error loading subscription plans: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Error loading subscription plans", e);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Toast.makeText(MainActivity.this, "Failed to load subscription plans: " + databaseError.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showPaymentConfirmationDialog(SubscriptionPlan plan) {
        try {
            String durationText = plan.duration == 1 ? "1 month" : plan.duration + " months";
            String message = "Plan: " + plan.type + "\n" +
                    "Duration: " + durationText + "\n" +
                    "Price: ₹" + String.format("%.2f", plan.price) + "\n\n" +
                    "Proceed with payment?";

            new MaterialAlertDialogBuilder(this)
                    .setTitle("Confirm Payment")
                    .setMessage(message)
                    .setPositiveButton("Pay Now", (dialog, which) -> {
                        if (validateRazorpaySetup()) {
                            initiatePayment(plan);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing payment confirmation", e);
            Toast.makeText(this, "Error showing payment confirmation", Toast.LENGTH_SHORT).show();
        }
    }

    private void debugRazorpaySetup() {
        Log.d(TAG, "=== Razorpay Debug Info ===");
        Log.d(TAG, "Razorpay Key: " + RAZORPAY_KEY);
        Log.d(TAG, "Current User ID: " + currentUserId);
        Log.d(TAG, "Selected Plan: " + (selectedPlan != null ? selectedPlan.type : "null"));

        try {
            Checkout checkout = new Checkout();
            checkout.setKeyID(RAZORPAY_KEY);
            Log.d(TAG, "Razorpay checkout instance created successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error creating Razorpay checkout instance", e);
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            Log.d(TAG, "Firebase user: " + user.getEmail());
        } else {
            Log.e(TAG, "No Firebase user found");
        }

        Log.d(TAG, "=== End Debug Info ===");
    }

    private void initiatePayment(SubscriptionPlan plan) {
        try {
            if (plan == null || plan.price <= 0 || currentUserId == null || currentUserId.isEmpty()) {
                Toast.makeText(this, "Invalid payment data", Toast.LENGTH_SHORT).show();
                return;
            }

            Checkout checkout = new Checkout();
            checkout.setKeyID(RAZORPAY_KEY);
            checkout.setImage(R.drawable.call_logo);

            JSONObject options = new JSONObject();
            options.put("name", "Call Responder App");
            options.put("description", isSubscribed ? "Renewal: " + plan.description : plan.description);
            options.put("currency", "INR");
            int amountInPaise = (int) Math.round(plan.price * 100);
            options.put("amount", amountInPaise);

            Log.d(TAG, "Amount: ₹" + plan.price + " = " + amountInPaise + " paise");

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                JSONObject prefill = new JSONObject();
                if (user.getEmail() != null) {
                    prefill.put("email", user.getEmail());
                }
                if (user.getDisplayName() != null) {
                    prefill.put("name", user.getDisplayName());
                }
                options.put("prefill", prefill);
            }

            JSONObject theme = new JSONObject();
            theme.put("color", "#3399cc");
            options.put("theme", theme);

            JSONObject notes = new JSONObject();
            notes.put("plan_id", plan.id);
            notes.put("user_id", currentUserId);
            notes.put("is_renewal", isSubscribed);
            options.put("notes", notes);

            Log.d(TAG, "Opening Razorpay with options: " + options.toString());
            checkout.open(this, options);
        } catch (Exception e) {
            Log.e(TAG, "Payment initiation failed", e);
            Toast.makeText(this, "Payment failed to start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onPaymentSuccess(String paymentId) {
        try {
            Log.d(TAG, "Payment successful with ID: " + paymentId);

            runOnUiThread(() -> {
                Toast.makeText(this, "Payment Successful! ID: " + paymentId, Toast.LENGTH_LONG).show();

                androidx.appcompat.app.AlertDialog loadingDialog = new MaterialAlertDialogBuilder(this)
                        .setTitle("Processing...")
                        .setMessage("Processing your subscription...")
                        .setCancelable(false)
                        .create();
                loadingDialog.show();

                updateSubscriptionStatus(paymentId, loadingDialog);
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing payment success", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "Payment successful but error occurred: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    @Override
    public void onPaymentError(int code, String response) {
        Log.e(TAG, "Payment Error - Code: " + code + ", Response: " + response);

        runOnUiThread(() -> {
            String errorMessage = "Payment failed";

            switch (code) {
                case Checkout.NETWORK_ERROR:
                    errorMessage = "Network error. Please check your internet connection and try again.";
                    break;
                case Checkout.INVALID_OPTIONS:
                    errorMessage = "Payment configuration error. Please try again.";
                    break;
                case Checkout.PAYMENT_CANCELED:
                    errorMessage = "Payment was cancelled by user.";
                    break;
                case Checkout.TLS_ERROR:
                    errorMessage = "Security error. Please update your app and try again.";
                    break;
                default:
                    try {
                        if (response != null && !response.isEmpty()) {
                            JSONObject errorJson = new JSONObject(response);
                            if (errorJson.has("error")) {
                                JSONObject error = errorJson.getJSONObject("error");
                                errorMessage = error.optString("description", "Payment failed");
                            }
                        }
                    } catch (JSONException e) {
                        errorMessage = "Payment failed. Please try again.";
                    }
                    break;
            }

            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();

            if (code != Checkout.PAYMENT_CANCELED) {
                showPaymentRetryDialog();
            }
        });
    }

    private boolean validateRazorpaySetup() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
                Toast.makeText(this, "No internet connection. Please check your network.", Toast.LENGTH_LONG).show();
                return false;
            }

            if (RAZORPAY_KEY == null || RAZORPAY_KEY.isEmpty() || !RAZORPAY_KEY.startsWith("rzp_")) {
                Toast.makeText(this, "Invalid Razorpay configuration", Toast.LENGTH_SHORT).show();
                return false;
            }

            Checkout checkout = new Checkout();
            checkout.setKeyID(RAZORPAY_KEY);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Razorpay validation failed", e);
            Toast.makeText(this, "Payment system error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void showPaymentRetryDialog() {
        try {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Payment Failed")
                    .setMessage("Would you like to retry the payment?")
                    .setPositiveButton("Retry", (dialog, which) -> {
                        if (selectedPlan != null) {
                            initiatePayment(selectedPlan);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing retry dialog", e);
        }
    }

    private boolean validatePaymentData(SubscriptionPlan plan) {
        if (plan == null) {
            Toast.makeText(this, "Invalid subscription plan", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (plan.price <= 0) {
            Toast.makeText(this, "Invalid subscription price", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (currentUserId == null || currentUserId.isEmpty()) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            return false;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "User session expired", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private void updateSubscriptionStatus(String paymentId, androidx.appcompat.app.AlertDialog loadingDialog) {
        if (mDatabase == null || currentUserId == null || selectedPlan == null) {
            if (loadingDialog != null) loadingDialog.dismiss();
            Toast.makeText(this, "Error: Missing required data", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            final Date startDate = new Date();
            final Calendar calendar = Calendar.getInstance();
            calendar.setTime(startDate);

            int newDuration = selectedPlan.duration;
            if (isSubscribed && subscriptionEndTime > System.currentTimeMillis()) {
                calendar.setTime(new Date(subscriptionEndTime));
                newDuration += monthsRemaining;
                Log.d(TAG, "Renewal: Adding " + selectedPlan.duration + " months to existing " + monthsRemaining + " months");
            } else {
                calendar.setTime(startDate);
                Log.d(TAG, "New subscription: Setting duration to " + selectedPlan.duration + " months");
            }
            calendar.add(Calendar.MONTH, selectedPlan.duration);
            final Date endDate = calendar.getTime();

            final Map<String, Object> subscriptionUpdate = new HashMap<>();
            subscriptionUpdate.put("isSubscribed", true);
            subscriptionUpdate.put("subscriptionType", selectedPlan.type);
            subscriptionUpdate.put("subscriptionStartDate", dateFormat.format(startDate));
            subscriptionUpdate.put("subscriptionEndDate", dateFormat.format(endDate));
            subscriptionUpdate.put("monthsRemaining", newDuration);
            subscriptionUpdate.put("autoRenew", false);
            subscriptionUpdate.put("paymentId", paymentId);
            subscriptionUpdate.put("planId", selectedPlan.id);
            subscriptionUpdate.put("amountPaid", selectedPlan.price);
            subscriptionUpdate.put("paymentDate", dateFormat.format(startDate));
            subscriptionUpdate.put("lastUpdated", ServerValue.TIMESTAMP);

            // Disable free trial when subscription is active
            subscriptionUpdate.put("freeTrialActive", false);
            subscriptionUpdate.put("freeTrialEndDate", "");

            int finalNewDuration = newDuration;
            mDatabase.child("users").child(currentUserId).child("subscription")
                    .updateChildren(subscriptionUpdate)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Subscription updated successfully");
                        // Update local variables
                        isSubscribed = true;
                        isFreeTrialActive = false;
                        subscriptionEndTime = endDate.getTime();
                        subscriptionType = selectedPlan.type;
                        monthsRemaining = finalNewDuration;

                        runOnUiThread(() -> {
                            if (loadingDialog != null) loadingDialog.dismiss();
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Success!")
                                    .setMessage("Your subscription has been " + (monthsRemaining > selectedPlan.duration ? "renewed" : "activated") + " successfully! New duration: " + finalNewDuration + " months.")
                                    .setPositiveButton("OK", (dialog, which) -> {
                                        updateSubscriptionUI();
                                        // Start service for newly subscribed users
                                        if (hasAllPermissions()) {
                                            checkSubscriptionAndStartService();
                                        }
                                    })
                                    .show();
                            createPaymentRecord(paymentId);
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to update subscription", e);
                        runOnUiThread(() -> {
                            if (loadingDialog != null) loadingDialog.dismiss();
                            Toast.makeText(this, "Error updating subscription: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error updating subscription status", e);
            runOnUiThread(() -> {
                if (loadingDialog != null) loadingDialog.dismiss();
                Toast.makeText(this, "Error updating subscription: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    private void createPaymentRecord(String paymentId) {
        if (mDatabase == null || currentUserId == null || selectedPlan == null) {
            return;
        }

        try {
            Map<String, Object> paymentRecord = new HashMap<>();
            paymentRecord.put("userId", currentUserId);
            paymentRecord.put("paymentId", paymentId);
            paymentRecord.put("planId", selectedPlan.id);
            paymentRecord.put("planType", selectedPlan.type);
            paymentRecord.put("amount", selectedPlan.price);
            paymentRecord.put("duration", selectedPlan.duration);
            paymentRecord.put("durationUnit", "months");
            paymentRecord.put("status", "completed");
            paymentRecord.put("timestamp", ServerValue.TIMESTAMP);
            paymentRecord.put("isRenewal", isSubscribed);

            mDatabase.child("payments").push().setValue(paymentRecord)
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Payment record created successfully"))
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to create payment record", e));
        } catch (Exception e) {
            Log.e(TAG, "Error creating payment record", e);
        }
    }

    private void loadUserMessages(String userId) {
        if (mDatabase == null) return;

        mDatabase.child("users").child(userId).child("messages")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        try {
                            if (dataSnapshot.exists()) {
                                String afterCall = dataSnapshot.child("after_call").getValue(String.class);
                                String callCut = dataSnapshot.child("cut").getValue(String.class);
                                String busy = dataSnapshot.child("busy").getValue(String.class);
                                String switchedOff = dataSnapshot.child("switched_off").getValue(String.class);
                                String outgoingMissed = dataSnapshot.child("outgoing_missed").getValue(String.class);

                                if (afterCall != null && tvAfterCall != null) tvAfterCall.setText(afterCall);
                                if (callCut != null && tvCallCut != null) tvCallCut.setText(callCut);
                                if (busy != null && tvBusy != null) tvBusy.setText(busy);
                                if (switchedOff != null && tvSwitchedOff != null) tvSwitchedOff.setText(switchedOff);
                                if (outgoingMissed != null && tvOutgoingMissed != null) tvOutgoingMissed.setText(outgoingMissed);

                                saveToSharedPreferences(
                                        afterCall != null ? afterCall : "",
                                        callCut != null ? callCut : "",
                                        busy != null ? busy : "",
                                        switchedOff != null ? switchedOff : "",
                                        outgoingMissed != null ? outgoingMissed : "I was trying to call you"
                                );
                            }
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Error loading user messages: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        Toast.makeText(MainActivity.this, "Failed to load messages: " + databaseError.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void loadMessagesFromPrefs() {
        try {
            SharedPreferences prefs = getSharedPreferences("CallPrefs", MODE_PRIVATE);
            if (tvAfterCall != null) tvAfterCall.setText(prefs.getString("after_call", "Thanks for calling!"));
            if (tvCallCut != null) tvCallCut.setText(prefs.getString("cut", "Sorry, I can't answer right now."));
            if (tvBusy != null) tvBusy.setText(prefs.getString("busy", "I'm busy at the moment, will call you back."));
            if (tvSwitchedOff != null) tvSwitchedOff.setText(prefs.getString("switched_off", "My phone is switched off, please try later."));
            if (tvOutgoingMissed != null) tvOutgoingMissed.setText(prefs.getString("outgoing_missed", "I was trying to call you"));
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load preferences: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveMessages() {
        try {
            String afterCallMsg = tvAfterCall != null ? tvAfterCall.getText().toString() : "";
            String callCutMsg = tvCallCut != null ? tvCallCut.getText().toString() : "";
            String busyMsg = tvBusy != null ? tvBusy.getText().toString() : "";
            String switchedOffMsg = tvSwitchedOff != null ? tvSwitchedOff.getText().toString() : "";
            String outgoingMissedMsg = tvOutgoingMissed != null ? tvOutgoingMissed.getText().toString() : "";

            saveToSharedPreferences(afterCallMsg, callCutMsg, busyMsg, switchedOffMsg, outgoingMissedMsg);

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && mDatabase != null) {
                String userId = user.getUid();

                Map<String, Object> messagesMap = new HashMap<>();
                messagesMap.put("after_call", afterCallMsg);
                messagesMap.put("cut", callCutMsg);
                messagesMap.put("busy", busyMsg);
                messagesMap.put("switched_off", switchedOffMsg);
                messagesMap.put("outgoing_missed", outgoingMissedMsg);

                mDatabase.child("users").child(userId).child("messages").setValue(messagesMap)
                        .addOnSuccessListener(aVoid ->
                                Toast.makeText(this, "Messages saved successfully", Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e ->
                                Toast.makeText(this, "Failed to save messages: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } else {
                Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error saving messages: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToSharedPreferences(String afterCall, String callCut, String busy, String switchedOff, String outgoingMissed) {
        try {
            SharedPreferences.Editor editor = getSharedPreferences("CallPrefs", MODE_PRIVATE).edit();
            editor.putString("after_call", afterCall);
            editor.putString("cut", callCut);
            editor.putString("busy", busy);
            editor.putString("switched_off", switchedOff);
            editor.putString("outgoing_missed", outgoingMissed);
            editor.apply();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save preferences: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    private void registerPermissionRevokedReceiver() {
        IntentFilter filter = new IntentFilter("com.lsoysapp.callresponderuser.PERMISSION_REVOKED");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Toast.makeText(MainActivity.this, "Permissions revoked. Please grant permissions again.", Toast.LENGTH_LONG).show();
                requestPermissions();
            }
        }, filter);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        return super.onOptionsItemSelected(item);
    }

    private void showPermissionStatusDialog() {
        try {
            boolean smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    == PackageManager.PERMISSION_GRANTED;
            boolean phoneStateGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED;
            boolean phoneNumbersGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
                            == PackageManager.PERMISSION_GRANTED;
            boolean contactsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
            boolean callLogGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                    == PackageManager.PERMISSION_GRANTED;

            StringBuilder message = new StringBuilder();
            message.append(smsGranted
                    ? "✅ SMS permission is granted. Auto-reply messages will work properly.\n"
                    : "⚠️ SMS permission is NOT granted. Auto-reply messages will NOT be sent.\n");
            message.append(phoneStateGranted
                    ? "✅ Phone state permission is granted. Call monitoring will work properly.\n"
                    : "⚠️ Phone state permission is NOT granted. Call monitoring will NOT work.\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                message.append(phoneNumbersGranted
                        ? "✅ Phone numbers permission is granted.\n"
                        : "⚠️ Phone numbers permission is NOT granted. Some features may not work.\n");
            }
            message.append(contactsGranted
                    ? "✅ Contacts permission is granted. Whitelist feature will work properly.\n"
                    : "⚠️ Contacts permission is NOT granted. Whitelist feature will NOT work.\n");
            message.append(callLogGranted
                    ? "✅ Call log permission is granted. Outgoing call detection will work properly."
                    : "⚠️ Call log permission is NOT granted. Outgoing call detection may not work.");

            Log.d(TAG, "Permission status dialog message: " + message.toString());

            new MaterialAlertDialogBuilder(this)
                    .setTitle("Permission Status")
                    .setMessage(message.toString())
                    .setPositiveButton("OK", (dialog, which) -> {
                        if (!smsGranted || !phoneStateGranted || !phoneNumbersGranted || !contactsGranted || !callLogGranted) {
                            showPermissionRetryDialog();
                        }
                    })
                    .setNegativeButton("Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Dialog error: " + e.getMessage(), e);
            Toast.makeText(this, "Dialog error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}

