package com.lsoysapp.callresponderuser;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int SMS_PERMISSION_CODE = 1001;

    private TextInputEditText editAfterCall, editCallCut, editBusy;
    private Button btnSave, btnSubscribe;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private TextView tvUserName, tvUserEmail;
    private TextView navPrivacy, navLogout;

    private boolean isSubscribed = false;
    private DatabaseReference mDatabase;
    private List<SubscriptionPlan> subscriptionPlans;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            // Initialize Firebase Database with error handling
            mDatabase = FirebaseDatabase.getInstance().getReference();
            subscriptionPlans = new ArrayList<>();

            // Initialize UI components with null checks
            initializeViews();

            // Setup toolbar
            setupToolbar();

            // Setup navigation drawer
            setupNavigationDrawer();

            // Setup Firebase auth and user data
            setupFirebaseAuth();

            // Setup notification channel
            setupNotificationChannel();

            // Load messages and setup listeners
            loadMessagesFromPrefs();
            setupClickListeners();

            // Load subscription plans
            loadSubscriptionPlans();

            // Request permissions
            requestPermissions();

            // Start service (with error handling)
            startCallStateService();

        } catch (Exception e) {
            // Log the error and show user-friendly message
            e.printStackTrace();
            Toast.makeText(this, "Error initializing app: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initializeViews() {
        // Initialize drawer layout and navigation view
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);

        // Initialize input fields
        editAfterCall = findViewById(R.id.editAfterCall);
        editCallCut = findViewById(R.id.editCallCut);
        editBusy = findViewById(R.id.editBusy);
        btnSave = findViewById(R.id.btnSave);
        btnSubscribe = findViewById(R.id.btnSubscribe);

        // Check for null views
        if (editAfterCall == null || editCallCut == null || editBusy == null ||
                btnSave == null || btnSubscribe == null) {
            Toast.makeText(this, "Error: Some views not found in layout", Toast.LENGTH_LONG).show();
            return;
        }
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
        }
    }

    private void setupNavigationDrawer() {
        if (drawerLayout == null) {
            Toast.makeText(this, "Navigation drawer not found", Toast.LENGTH_SHORT).show();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        if (toolbar != null) {
            ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                    this, drawerLayout, toolbar,
                    android.R.string.ok, // Using default string instead of custom
                    android.R.string.cancel // Using default string instead of custom
            );
            drawerLayout.addDrawerListener(toggle);
            toggle.syncState();
        }

        // Find navigation menu items directly in the layout
        navPrivacy = findViewById(R.id.nav_privacy);
        navLogout = findViewById(R.id.nav_logout);
        tvUserName = findViewById(R.id.tvUserName);
        tvUserEmail = findViewById(R.id.tvUserEmail);

        if (tvUserName == null || tvUserEmail == null) {
            Toast.makeText(this, "Navigation header views not found", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupFirebaseAuth() {
        try {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                String name = currentUser.getDisplayName();
                String email = currentUser.getEmail();

                if (tvUserName != null) {
                    tvUserName.setText(name != null ? name : "Unknown User");
                }
                if (tvUserEmail != null) {
                    tvUserEmail.setText(email != null ? email : "No Email");
                }

                // Check user status and load user data
                checkUserStatus(currentUser.getUid());
            } else {
                Toast.makeText(this, "No user authenticated", Toast.LENGTH_SHORT).show();
                // Comment out redirect to login for now to prevent crash
                // startActivity(new Intent(this, LoginActivity.class));
                // finish();
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
        // Navigation click listeners
        if (navLogout != null) {
            navLogout.setOnClickListener(v -> {
                try {
                    FirebaseAuth.getInstance().signOut();
                    Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
                    // Comment out redirect for now
                    // Intent intent = new Intent(this, LoginActivity.class);
                    // intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    // startActivity(intent);
                    // finish();
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

        // Button click listeners
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                if (!isSubscribed) {
                    Toast.makeText(this, "This feature is only available to subscribed users.", Toast.LENGTH_LONG).show();
                    return;
                }
                saveMessages();
            });
        }

        if (btnSubscribe != null) {
            btnSubscribe.setOnClickListener(v -> {
                if (isSubscribed) {
                    Toast.makeText(this, "You are already subscribed!", Toast.LENGTH_SHORT).show();
                } else {
                    loadSubscriptionPlans();
                }
            });
        }
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS},
                    SMS_PERMISSION_CODE);
        }
    }

    private void startCallStateService() {
        try {
            // Comment out service start for now to prevent crash
            // Intent serviceIntent = new Intent(this, CallStateService.class);
            // if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            //     startForegroundService(serviceIntent);
            // } else {
            //     startService(serviceIntent);
            // }
            Toast.makeText(this, "Call monitoring service disabled for testing", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Service start error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkUserStatus(String userId) {
        if (mDatabase == null) {
            Toast.makeText(this, "Database not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        mDatabase.child("users").child(userId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
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

                    DataSnapshot subscriptionData = dataSnapshot.child("subscription");
                    if (subscriptionData.exists()) {
                        Boolean subscribed = subscriptionData.child("isSubscribed").getValue(Boolean.class);
                        isSubscribed = subscribed != null ? subscribed : false;
                    }

                    updateSubscriptionUI();
                    loadUserMessages(userId);
                } else {
                    Toast.makeText(MainActivity.this, "User record not found.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Toast.makeText(MainActivity.this, "Failed to verify account status: " + databaseError.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateSubscriptionUI() {
        if (btnSubscribe != null) {
            if (isSubscribed) {
                btnSubscribe.setText("Subscribed ✓");
                btnSubscribe.setEnabled(false);
                btnSubscribe.setBackgroundColor(getResources().getColor(android.R.color.darker_gray));
            } else {
                btnSubscribe.setText("Get Subscription");
                btnSubscribe.setEnabled(true);
                btnSubscribe.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_bright));
            }
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
                subscriptionPlans.clear();

                for (DataSnapshot planSnapshot : dataSnapshot.getChildren()) {
                    String subscriptionId = planSnapshot.getKey();
                    String type = planSnapshot.child("type").getValue(String.class);
                    Long duration = planSnapshot.child("duration").getValue(Long.class);
                    Double price = planSnapshot.child("price").getValue(Double.class);
                    String description = planSnapshot.child("description").getValue(String.class);
                    Long createdAt = planSnapshot.child("createdAt").getValue(Long.class);

                    if (type != null && duration != null && price != null && description != null) {
                        // Comment out SubscriptionPlan creation for now to prevent crash
                        // SubscriptionPlan plan = new SubscriptionPlan(
                        //         subscriptionId,
                        //         type,
                        //         duration.intValue(),
                        //         price,
                        //         description,
                        //         createdAt
                        // );
                        // subscriptionPlans.add(plan);
                    }
                }

                if (!isSubscribed && !subscriptionPlans.isEmpty()) {
                    showSubscriptionDialog();
                } else {
                    Toast.makeText(MainActivity.this, "No subscription plans available", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Toast.makeText(MainActivity.this, "Failed to load subscription plans: " + databaseError.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSubscriptionDialog() {
        Toast.makeText(this, "Subscription dialog disabled for testing", Toast.LENGTH_SHORT).show();
        // Comment out dialog for now to prevent crash
        // if (subscriptionPlans.isEmpty()) {
        //     Toast.makeText(this, "No subscription plans available", Toast.LENGTH_SHORT).show();
        //     return;
        // }
        // ... rest of dialog code
    }

    private void loadUserMessages(String userId) {
        if (mDatabase == null) return;

        mDatabase.child("users").child(userId).child("messages")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        if (dataSnapshot.exists()) {
                            String afterCall = dataSnapshot.child("after_call").getValue(String.class);
                            String callCut = dataSnapshot.child("cut").getValue(String.class);
                            String busy = dataSnapshot.child("busy").getValue(String.class);

                            if (afterCall != null && editAfterCall != null) editAfterCall.setText(afterCall);
                            if (callCut != null && editCallCut != null) editCallCut.setText(callCut);
                            if (busy != null && editBusy != null) editBusy.setText(busy);

                            saveToSharedPreferences(
                                    afterCall != null ? afterCall : "",
                                    callCut != null ? callCut : "",
                                    busy != null ? busy : ""
                            );
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
            if (editAfterCall != null) editAfterCall.setText(prefs.getString("after_call", ""));
            if (editCallCut != null) editCallCut.setText(prefs.getString("cut", ""));
            if (editBusy != null) editBusy.setText(prefs.getString("busy", ""));
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load preferences: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveMessages() {
        String afterCallMsg = editAfterCall != null ? editAfterCall.getText().toString() : "";
        String callCutMsg = editCallCut != null ? editCallCut.getText().toString() : "";
        String busyMsg = editBusy != null ? editBusy.getText().toString() : "";

        saveToSharedPreferences(afterCallMsg, callCutMsg, busyMsg);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && mDatabase != null) {
            String userId = user.getUid();

            Map<String, Object> messagesMap = new HashMap<>();
            messagesMap.put("after_call", afterCallMsg);
            messagesMap.put("cut", callCutMsg);
            messagesMap.put("busy", busyMsg);

            mDatabase.child("users").child(userId).child("messages").setValue(messagesMap)
                    .addOnSuccessListener(aVoid ->
                            Toast.makeText(this, "Messages saved successfully", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Failed to save messages: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } else {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToSharedPreferences(String afterCall, String callCut, String busy) {
        try {
            SharedPreferences.Editor editor = getSharedPreferences("CallPrefs", MODE_PRIVATE).edit();
            editor.putString("after_call", afterCall);
            editor.putString("cut", callCut);
            editor.putString("busy", busy);
            editor.apply();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save preferences: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "SMS permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "SMS permission denied. Messages won't be sent.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Comment out menu inflation to prevent crash
        // getMenuInflater().inflate(R.menu.top_app_bar_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // Comment out menu handling to prevent crash
        // if (item.getItemId() == R.id.action_notifications) {
        //     showPermissionStatusDialog();
        //     return true;
        // }
        return super.onOptionsItemSelected(item);
    }

    private void showPermissionStatusDialog() {
        try {
            boolean smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    == PackageManager.PERMISSION_GRANTED;

            String message = smsGranted
                    ? "✅ SMS permission is granted. Auto-reply messages will work properly."
                    : "⚠️ SMS permission is NOT granted. Auto-reply messages will NOT be sent.";

            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Permission Status")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "Dialog error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}