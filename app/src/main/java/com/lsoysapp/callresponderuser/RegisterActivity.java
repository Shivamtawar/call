package com.lsoysapp.callresponderuser;

import android.content.Intent;
import android.os.Bundle;
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
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private GoogleSignInClient mGoogleSignInClient;
    private ActivityResultLauncher<Intent> googleSignInLauncher;

    private EditText editName, editEmail, editPassword, editConfirmPassword;
    private Button btnRegister, btnGoogleSignUp;
    private TextView txtLoginLink;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // Initialize Firebase
        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Initialize views
        initViews();

        // Configure Google Sign-In
        configureGoogleSignIn();

        // Add animation to logo
        ImageView logo = findViewById(R.id.imgLogo);
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        logo.startAnimation(fadeIn);

        // Set click listeners
        setupClickListeners();
    }

    private void initViews() {
        editName = findViewById(R.id.editName);
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        editConfirmPassword = findViewById(R.id.editConfirmPassword);
        btnRegister = findViewById(R.id.btnRegister);
        btnGoogleSignUp = findViewById(R.id.btnGoogleSignUp);
        txtLoginLink = findViewById(R.id.txtLoginLink);
        progressBar = findViewById(R.id.progressBar);
    }

    private void setupClickListeners() {
        btnRegister.setOnClickListener(v -> registerUser());

        btnGoogleSignUp.setOnClickListener(v -> signUpWithGoogle());

        txtLoginLink.setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    private void createOrUpdateUserInDatabase(FirebaseUser user) {
        String userId = user.getUid();
        String name = user.getDisplayName() != null ? user.getDisplayName() : "User";
        String email = user.getEmail() != null ? user.getEmail() : "";

        // Check if user exists
        mDatabase.child("users").child(userId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    // User doesn't exist, create new user
                    Map<String, Object> userData = new HashMap<>();
                    userData.put("name", name);
                    userData.put("email", email);
                    userData.put("subscription", createSubscriptionData());
                    userData.put("registrationDate", getCurrentDate());
                    userData.put("lastLoginDate", getCurrentDate());
                    userData.put("enabled", true);
                    userData.put("messages", createDefaultMessages());
                    userData.put("loginMethod", "google");

                    mDatabase.child("users").child(userId).setValue(userData)
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "User created successfully");
                                Toast.makeText(RegisterActivity.this, "Welcome " + name + "!", Toast.LENGTH_SHORT).show();
                                goToMainActivity();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to create user", e);
                                Toast.makeText(RegisterActivity.this, "Failed to create user profile", Toast.LENGTH_SHORT).show();
                            });
                } else {
                    // User exists, just update last login date and go to main activity
                    updateLastLoginDate(userId);
                    Toast.makeText(RegisterActivity.this, "Welcome back " + name + "!", Toast.LENGTH_SHORT).show();
                    goToMainActivity();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Database error: " + databaseError.getMessage());
                Toast.makeText(RegisterActivity.this, "Database error", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateLastLoginDate(String userId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("lastLoginDate", getCurrentDate());
        mDatabase.child("users").child(userId).updateChildren(updates);
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
        showProgress(true);

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
                        showProgress(false);
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
        userData.put("enabled", true);
        userData.put("messages", createDefaultMessages());
        userData.put("loginMethod", "email");

        // Save to Realtime Database
        mDatabase.child("users").child(userId).setValue(userData)
                .addOnCompleteListener(task -> {
                    showProgress(false);

                    if (task.isSuccessful()) {
                        Toast.makeText(RegisterActivity.this, "Account created successfully!", Toast.LENGTH_SHORT).show();
                        goToMainActivity();
                    } else {
                        Toast.makeText(RegisterActivity.this, "Failed to save user data", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to save user data", task.getException());
                    }
                });
    }

    private void showProgress(boolean show) {
        if (show) {
            progressBar.setVisibility(View.VISIBLE);
            btnRegister.setEnabled(false);
            btnGoogleSignUp.setEnabled(false);
        } else {
            progressBar.setVisibility(View.GONE);
            btnRegister.setEnabled(true);
            btnGoogleSignUp.setEnabled(true);
        }
    }

    private void goToMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private Map<String, Object> createSubscriptionData() {
        Map<String, Object> subscription = new HashMap<>();
        subscription.put("isSubscribed", false);
        subscription.put("subscriptionType", "free");
        subscription.put("subscriptionStartDate", "");
        subscription.put("subscriptionEndDate", "");
        subscription.put("monthsRemaining", 0);
        subscription.put("autoRenew", false);
        return subscription;
    }

    private void configureGoogleSignIn() {
        Log.d(TAG, "Configuring Google Sign-In");

        try {
            // Get the web client ID
            String webClientId = getString(R.string.default_web_client_id);
            Log.d(TAG, "Web Client ID configured: " + webClientId.substring(0, Math.min(30, webClientId.length())) + "...");

            // Configure Google Sign-In options
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .requestProfile()
                    .build();

            // Create Google Sign-In client
            mGoogleSignInClient = GoogleSignIn.getClient(this, gso);
            Log.d(TAG, "Google Sign-In client created successfully");

            // Setup activity result launcher
            googleSignInLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Log.d(TAG, "Google Sign-In result received");
                        Log.d(TAG, "Result code: " + result.getResultCode());

                        try {
                            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                                Log.d(TAG, "Processing successful Google Sign-In result");

                                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                                handleSignInResult(task);

                            } else if (result.getResultCode() == RESULT_CANCELED) {
                                Log.d(TAG, "Google Sign-In was cancelled by user");
                                showProgress(false);
                                Toast.makeText(this, "Sign-in cancelled", Toast.LENGTH_SHORT).show();
                            } else {
                                Log.e(TAG, "Unexpected result code: " + result.getResultCode());
                                showProgress(false);
                                Toast.makeText(this, "Sign-in failed", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing Google Sign-In result", e);
                            showProgress(false);
                            Toast.makeText(this, "Error during sign-in: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
            );

        } catch (Exception e) {
            Log.e(TAG, "Error configuring Google Sign-In", e);
            Toast.makeText(this, "Error configuring Google Sign-In", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);

            // Signed in successfully
            Log.d(TAG, "Google Sign-In successful");
            Log.d(TAG, "Account email: " + account.getEmail());
            Log.d(TAG, "Account name: " + account.getDisplayName());

            String idToken = account.getIdToken();
            if (idToken != null) {
                Log.d(TAG, "ID Token received, proceeding with Firebase authentication");
                firebaseAuthWithGoogle(idToken);
            } else {
                Log.e(TAG, "ID Token is null - check Web Client ID configuration");
                showProgress(false);
                Toast.makeText(this, "Sign-in failed: Invalid configuration", Toast.LENGTH_SHORT).show();
            }

        } catch (ApiException e) {
            // Handle sign-in failure
            Log.e(TAG, "Google Sign-In failed", e);
            showProgress(false);

            String errorMessage = "Sign-in failed";
            switch (e.getStatusCode()) {
                case 12501: // SIGN_IN_CANCELLED
                    errorMessage = "Sign-in cancelled";
                    break;
                case 12502: // SIGN_IN_FAILED
                    errorMessage = "Sign-in failed";
                    break;
                case 12500: // SIGN_IN_REQUIRED
                    errorMessage = "Sign-in required";
                    break;
                default:
                    errorMessage = "Sign-in failed: " + e.getMessage();
            }

            Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private void signUpWithGoogle() {
        Log.d(TAG, "Starting Google Sign-Up");
        showProgress(true);

        // Sign out any existing account first
        mGoogleSignInClient.signOut().addOnCompleteListener(this, task -> {
            Log.d(TAG, "Previous account signed out, launching Google Sign-In");
            launchGoogleSignIn();
        });
    }

    private void launchGoogleSignIn() {
        try {
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            Log.d(TAG, "Launching Google Sign-In intent");
            googleSignInLauncher.launch(signInIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching Google Sign-In", e);
            showProgress(false);
            Toast.makeText(this, "Error launching Google Sign-In: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void firebaseAuthWithGoogle(String idToken) {
        Log.d(TAG, "Starting Firebase authentication with Google");

        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);

        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    Log.d(TAG, "Firebase authentication completed");

                    if (task.isSuccessful()) {
                        Log.d(TAG, "Firebase authentication successful");
                        FirebaseUser user = mAuth.getCurrentUser();

                        if (user != null) {
                            Log.d(TAG, "Firebase user created: " + user.getEmail());
                            createOrUpdateUserInDatabase(user);
                        } else {
                            Log.e(TAG, "Firebase user is null after successful authentication");
                            showProgress(false);
                            Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.e(TAG, "Firebase authentication failed", task.getException());
                        showProgress(false);

                        String errorMessage = "Authentication failed";
                        if (task.getException() != null) {
                            errorMessage = task.getException().getMessage();
                        }
                        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Firebase authentication failed", e);
                    showProgress(false);
                    Toast.makeText(this, "Authentication failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
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