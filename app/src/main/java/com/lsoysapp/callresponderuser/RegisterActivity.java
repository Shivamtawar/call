package com.lsoysapp.callresponderuser;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    private EditText editName, editEmail, editPassword, editConfirmPassword;
    private Button btnRegister;
    private TextView txtLoginLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // Initialize Firebase
        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Initialize views
        initViews();

        // Add animation to logo
        ImageView logo = findViewById(R.id.imgLogo);
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        logo.startAnimation(fadeIn);

        // Set click listeners
        btnRegister.setOnClickListener(v -> registerUser());
        txtLoginLink.setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    private void initViews() {
        editName = findViewById(R.id.editName);
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        editConfirmPassword = findViewById(R.id.editConfirmPassword);
        btnRegister = findViewById(R.id.btnRegister);
        txtLoginLink = findViewById(R.id.txtLoginLink);
    }

    private void registerUser() {
        String name = editName.getText().toString().trim();
        String email = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString().trim();
        String confirmPassword = editConfirmPassword.getText().toString().trim();

        // Validate inputs
        if (!validateInputs(name, email, password, confirmPassword)) {
            return;
        }

        // Show loading state
        btnRegister.setEnabled(false);
        btnRegister.setText("Creating Account...");

        // Create user with Firebase Auth
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Registration success
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            createUserInDatabase(user.getUid(), name, email);
                        }
                    } else {
                        // Registration failed
                        btnRegister.setEnabled(true);
                        btnRegister.setText("Register");
                        String errorMessage = "Registration failed";
                        if (task.getException() != null) {
                            errorMessage = task.getException().getMessage();
                        }
                        Toast.makeText(RegisterActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Registration failed", task.getException());
                    }
                });
    }

    private boolean validateInputs(String name, String email, String password, String confirmPassword) {
        if (name.isEmpty()) {
            editName.setError("Name is required");
            editName.requestFocus();
            return false;
        }

        if (name.length() < 2) {
            editName.setError("Name must be at least 2 characters");
            editName.requestFocus();
            return false;
        }

        if (email.isEmpty()) {
            editEmail.setError("Email is required");
            editEmail.requestFocus();
            return false;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            editEmail.setError("Please enter a valid email");
            editEmail.requestFocus();
            return false;
        }

        if (password.isEmpty()) {
            editPassword.setError("Password is required");
            editPassword.requestFocus();
            return false;
        }

        if (password.length() < 6) {
            editPassword.setError("Password must be at least 6 characters");
            editPassword.requestFocus();
            return false;
        }

        if (!password.equals(confirmPassword)) {
            editConfirmPassword.setError("Passwords do not match");
            editConfirmPassword.requestFocus();
            return false;
        }

        return true;
    }

    private void createUserInDatabase(String userId, String name, String email) {
        // Create user data
        Map<String, Object> userData = new HashMap<>();
        userData.put("name", name);
        userData.put("email", email);
        userData.put("subscription", createSubscriptionData());
        userData.put("registrationDate", getCurrentDate());
        userData.put("lastLoginDate", getCurrentDate());
        userData.put("enabled", true); // Add enabled field with default value true
        userData.put("messages", createDefaultMessages()); // Add messages field with default values

        // Save to Realtime Database
        mDatabase.child("users").child(userId).setValue(userData)
                .addOnCompleteListener(task -> {
                    btnRegister.setEnabled(true);
                    btnRegister.setText("Register");

                    if (task.isSuccessful()) {
                        Toast.makeText(RegisterActivity.this, "Account created successfully!", Toast.LENGTH_SHORT).show();
                        // Redirect to main activity
                        startActivity(new Intent(RegisterActivity.this, MainActivity.class));
                        finish();
                    } else {
                        Toast.makeText(RegisterActivity.this, "Failed to save user data", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to save user data", task.getException());
                    }
                });
    }

    private Map<String, Object> createSubscriptionData() {
        Map<String, Object> subscription = new HashMap<>();
        subscription.put("isSubscribed", false);
        subscription.put("subscriptionType", "free"); // free, monthly, yearly
        subscription.put("subscriptionStartDate", "");
        subscription.put("subscriptionEndDate", "");
        subscription.put("monthsRemaining", 0);
        subscription.put("autoRenew", false);
        return subscription;
    }

    private Map<String, Object> createDefaultMessages() {
        Map<String, Object> messages = new HashMap<>();
        messages.put("after_call", "Hi, I'll get back to you soon!");
        messages.put("cut", "Sorry, I had to cut the call. I'll call you back.");
        messages.put("busy", "I'm currently busy. I'll call you back later.");
        return messages;
    }

    private String getCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}