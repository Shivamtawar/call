package com.lsoysapp.callresponderuser;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.auth.api.identity.BeginSignInRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.SignInClient;
import com.google.android.gms.auth.api.identity.SignInCredential;
import com.google.android.gms.common.api.ApiException;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private SignInClient oneTapClient;
    private BeginSignInRequest signInRequest;
    private ActivityResultLauncher<IntentSenderRequest> googleLoginLauncher;

    // Email/Password UI elements
    private TextInputLayout layoutEmail, layoutPassword;
    private TextInputEditText editEmail, editPassword;
    private Button btnEmailLogin, btnGoogleLogin, btnForgotPassword;
    private ProgressBar progressBar;

    // Phone authentication elements
    private EditText editPhoneNumber, editOtp;
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private static final int PERMISSION_REQUEST_CODE = 1234;
    private TextView txtRegisterLink;

    private final String[] requiredPermissions = new String[]{
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.FOREGROUND_SERVICE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        ImageView logo = findViewById(R.id.imgLogo);
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        logo.startAnimation(fadeIn);

        // Initialize Firebase
        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Initialize UI elements
        initializeViews();
        checkAndRequestPermissions();
        setupGoogleSignIn();

        // Check if user is already logged in
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            updateLastLoginDate(user.getUid());
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setupClickListeners();
    }

    private void initializeViews() {
        // Email/Password elements
        layoutEmail = findViewById(R.id.layoutEmail);
        layoutPassword = findViewById(R.id.layoutPassword);
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        btnEmailLogin = findViewById(R.id.btnEmailLogin);
        btnGoogleLogin = findViewById(R.id.btnGoogleLogin);
        btnForgotPassword = findViewById(R.id.btnForgotPassword);
        progressBar = findViewById(R.id.progressBar);
        txtRegisterLink = findViewById(R.id.txtRegisterLink);

        // Phone elements (if needed later)
        editPhoneNumber = findViewById(R.id.editPhoneNumber);
        editOtp = findViewById(R.id.editOtp);
    }

    private void setupClickListeners() {
        // Email login button
        btnEmailLogin.setOnClickListener(v -> loginWithEmail());

        // Google login setup
        googleLoginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        try {
                            SignInCredential credential = oneTapClient.getSignInCredentialFromIntent(result.getData());
                            String idToken = credential.getGoogleIdToken();
                            if (idToken != null) {
                                firebaseAuthWithGoogle(idToken);
                            }
                        } catch (ApiException e) {
                            Log.e(TAG, "Google sign-in failed", e);
                            Toast.makeText(this, "Google sign-in failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        btnGoogleLogin.setOnClickListener(v -> {
            oneTapClient.beginSignIn(signInRequest)
                    .addOnSuccessListener(result -> {
                        IntentSenderRequest intentSenderRequest =
                                new IntentSenderRequest.Builder(result.getPendingIntent().getIntentSender()).build();
                        googleLoginLauncher.launch(intentSenderRequest);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Google Sign-in failed", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, e.getMessage(), e);
                    });
        });

        // Forgot password button
        btnForgotPassword.setOnClickListener(v -> resetPassword());

        // Register link click listener
        txtRegisterLink.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting RegisterActivity: " + e.getMessage(), e);
                Toast.makeText(LoginActivity.this, "Unable to open registration page", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loginWithEmail() {
        String email = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString().trim();

        // Validate input
        if (!validateEmailPassword(email, password)) {
            return;
        }

        // Show progress
        showProgress(true);

        // Authenticate with Firebase
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    showProgress(false);

                    if (task.isSuccessful()) {
                        // Login successful - No email verification check
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            updateLastLoginDate(user.getUid());
                            Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();
                            goToMainActivity();
                        }
                    } else {
                        // Login failed
                        String errorMessage = "Authentication failed";
                        if (task.getException() != null) {
                            errorMessage = task.getException().getMessage();
                        }
                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Email login failed", task.getException());
                    }
                });
    }

    private boolean validateEmailPassword(String email, String password) {
        boolean isValid = true;

        // Validate email
        if (TextUtils.isEmpty(email)) {
            layoutEmail.setError("Email is required");
            isValid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            layoutEmail.setError("Please enter a valid email");
            isValid = false;
        } else {
            layoutEmail.setError(null);
        }

        // Validate password
        if (TextUtils.isEmpty(password)) {
            layoutPassword.setError("Password is required");
            isValid = false;
        } else if (password.length() < 6) {
            layoutPassword.setError("Password must be at least 6 characters");
            isValid = false;
        } else {
            layoutPassword.setError(null);
        }

        return isValid;
    }

    private void resetPassword() {
        String email = editEmail.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            layoutEmail.setError("Enter your email to reset password");
            return;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            layoutEmail.setError("Please enter a valid email");
            return;
        }

        showProgress(true);

        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    showProgress(false);

                    if (task.isSuccessful()) {
                        Toast.makeText(this, "Password reset email sent. Check your inbox.", Toast.LENGTH_LONG).show();
                    } else {
                        String errorMessage = "Failed to send reset email";
                        if (task.getException() != null) {
                            errorMessage = task.getException().getMessage();
                        }
                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showProgress(boolean show) {
        if (show) {
            progressBar.setVisibility(View.VISIBLE);
            btnEmailLogin.setEnabled(false);
            btnGoogleLogin.setEnabled(false);
        } else {
            progressBar.setVisibility(View.GONE);
            btnEmailLogin.setEnabled(true);
            btnGoogleLogin.setEnabled(true);
        }
    }

    private void setupGoogleSignIn() {
        oneTapClient = Identity.getSignInClient(this);
        signInRequest = new BeginSignInRequest.Builder()
                .setGoogleIdTokenRequestOptions(BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                        .setSupported(true)
                        .setServerClientId(getString(R.string.default_web_client_id)) // from google-services.json
                        .setFilterByAuthorizedAccounts(false)
                        .build())
                .build();
    }

    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            // Check if user exists in database, if not create them
                            createOrUpdateUserInDatabase(user);
                            Toast.makeText(this, "Logged in as: " + user.getEmail(), Toast.LENGTH_SHORT).show();
                            goToMainActivity();
                        }
                    } else {
                        Toast.makeText(this, "Google login failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void createOrUpdateUserInDatabase(FirebaseUser user) {
        String userId = user.getUid();
        String name = user.getDisplayName() != null ? user.getDisplayName() : "User";
        String email = user.getEmail() != null ? user.getEmail() : "";

        // Check if user exists, if not create new user data
        mDatabase.child("users").child(userId).get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                if (!task.getResult().exists()) {
                    // User doesn't exist, create new user
                    Map<String, Object> userData = new HashMap<>();
                    userData.put("name", name);
                    userData.put("email", email);
                    userData.put("subscription", createSubscriptionData());
                    userData.put("registrationDate", getCurrentDate());
                    userData.put("lastLoginDate", getCurrentDate());
                    userData.put("loginMethod", user.getProviderId().contains("google") ? "google" : "email");

                    mDatabase.child("users").child(userId).setValue(userData);
                } else {
                    // User exists, just update last login date
                    updateLastLoginDate(userId);
                }
            }
        });
    }

    private void updateLastLoginDate(String userId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("lastLoginDate", getCurrentDate());
        mDatabase.child("users").child(userId).updateChildren(updates);
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

    private String getCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    private void startPhoneNumberVerification(String phoneNumber) {
        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(mAuth)
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    @Override
                    public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                        signInWithPhoneAuthCredential(credential);
                    }

                    @Override
                    public void onVerificationFailed(@NonNull FirebaseException e) {
                        Toast.makeText(LoginActivity.this, "Verification failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onCodeSent(@NonNull String verifId,
                                           @NonNull PhoneAuthProvider.ForceResendingToken token) {
                        verificationId = verifId;
                        resendToken = token;
                        Toast.makeText(LoginActivity.this, "OTP sent. Please check your phone.", Toast.LENGTH_SHORT).show();
                    }
                })
                .build();
        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private void signInWithPhoneAuthCredential(PhoneAuthCredential credential) {
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            createOrUpdateUserInDatabase(user);
                        }
                        Toast.makeText(this, "Phone login successful", Toast.LENGTH_SHORT).show();
                        goToMainActivity();
                    } else {
                        Toast.makeText(this, "Phone login failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void goToMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
        }
    }
}