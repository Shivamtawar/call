package com.lsoysapp.callresponderuser;

import android.Manifest;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WhitelistActivity extends AppCompatActivity {

    private static final int CONTACTS_PERMISSION_CODE = 1002;
    private static final String TAG = "WhitelistActivity";

    private RecyclerView rvWhitelistedContacts;
    private MaterialButton btnAddContacts, btnAddCustomNumber;
    private TextView tvWhitelistCount, tvEmptyWhitelist;

    private WhitelistAdapter whitelistAdapter;
    private ContactSelectionAdapter contactSelectionAdapter;
    private List<Contact> allContacts;
    private List<Contact> whitelistedContacts;
    private List<Contact> filteredContacts;

    private DatabaseReference mDatabase;
    private String currentUserId;
    private Dialog contactSelectionDialog;

    public static class Contact {
        public String id;
        public String name;
        public String phoneNumber;
        public boolean isWhitelisted;
        public boolean isSelected;
        public boolean isCustom;
        public MessageBlockSettings blockSettings;

        public Contact(String id, String name, String phoneNumber, boolean isWhitelisted, boolean isCustom) {
            this.id = id;
            this.name = name;
            this.phoneNumber = phoneNumber;
            this.isWhitelisted = isWhitelisted;
            this.isSelected = false;
            this.isCustom = isCustom;
            this.blockSettings = new MessageBlockSettings();
        }
    }

    public static class MessageBlockSettings {
        public boolean blockMissedCall = false;
        public boolean blockAfterCall = false;
        public boolean blockBusy = false;
        public boolean blockOutgoingMissed = false;

        public MessageBlockSettings() {}

        public MessageBlockSettings(Map<String, Object> data) {
            if (data != null) {
                blockMissedCall = Boolean.TRUE.equals(data.get("blockMissedCall"));
                blockAfterCall = Boolean.TRUE.equals(data.get("blockAfterCall"));
                blockBusy = Boolean.TRUE.equals(data.get("blockBusy"));
                blockOutgoingMissed = Boolean.TRUE.equals(data.get("blockOutgoingMissed"));
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("blockMissedCall", blockMissedCall);
            map.put("blockAfterCall", blockAfterCall);
            map.put("blockBusy", blockBusy);
            map.put("blockOutgoingMissed", blockOutgoingMissed);
            return map;
        }

        public boolean hasAnyBlocked() {
            return blockMissedCall || blockAfterCall || blockBusy || blockOutgoingMissed;
        }

        public String getBlockedMessagesSummary() {
            List<String> blocked = new ArrayList<>();
            if (blockMissedCall) blocked.add("Missed Call");
            if (blockAfterCall) blocked.add("After Call");
            if (blockBusy) blocked.add("Busy");
            if (blockOutgoingMissed) blocked.add("Outgoing Missed");

            if (blocked.isEmpty()) return "No messages blocked";
            return "Blocked: " + String.join(", ", blocked);
        }
    }

    private class WhitelistAdapter extends RecyclerView.Adapter<WhitelistAdapter.WhitelistViewHolder> {
        private List<Contact> contacts;

        public WhitelistAdapter(List<Contact> contacts) {
            this.contacts = contacts;
        }

        @NonNull
        @Override
        public WhitelistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_whitelisted_contact, parent, false);
            return new WhitelistViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull WhitelistViewHolder holder, int position) {
            Contact contact = contacts.get(position);
            holder.tvName.setText(contact.name);
            holder.tvPhone.setText(contact.phoneNumber);

            if (contact.isCustom) {
                holder.tvName.append(" (Custom)");
            }

            // Show blocked messages summary
            if (contact.blockSettings.hasAnyBlocked()) {
                holder.tvBlockedMessages.setVisibility(View.VISIBLE);
                holder.tvBlockedMessages.setText(contact.blockSettings.getBlockedMessagesSummary());
                holder.tvBlockedMessages.setTextColor(ContextCompat.getColor(WhitelistActivity.this, R.color.error_color));
            } else {
                holder.tvBlockedMessages.setVisibility(View.VISIBLE);
                holder.tvBlockedMessages.setText("All messages allowed");
                holder.tvBlockedMessages.setTextColor(ContextCompat.getColor(WhitelistActivity.this, R.color.success_color));
            }

            holder.ivEdit.setOnClickListener(v -> {
                showMessageBlockSettingsDialog(contact);
            });

            holder.ivRemove.setOnClickListener(v -> {
                confirmRemoveFromWhitelist(contact);
            });
        }

        @Override
        public int getItemCount() {
            return contacts.size();
        }

        class WhitelistViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvPhone, tvBlockedMessages;
            ImageView ivEdit, ivRemove;

            WhitelistViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvContactName);
                tvPhone = itemView.findViewById(R.id.tvContactPhone);
                tvBlockedMessages = itemView.findViewById(R.id.tvBlockedMessages);
                ivEdit = itemView.findViewById(R.id.ivEditContact);
                ivRemove = itemView.findViewById(R.id.ivRemoveContact);
            }
        }
    }

    private class ContactSelectionAdapter extends RecyclerView.Adapter<ContactSelectionAdapter.ContactSelectionViewHolder> {
        private List<Contact> contacts;

        public ContactSelectionAdapter(List<Contact> contacts) {
            this.contacts = contacts;
        }

        @NonNull
        @Override
        public ContactSelectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact_selection, parent, false);
            return new ContactSelectionViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ContactSelectionViewHolder holder, int position) {
            Contact contact = contacts.get(position);
            holder.tvName.setText(contact.name);
            holder.tvPhone.setText(contact.phoneNumber);
            holder.cbSelect.setChecked(contact.isSelected);

            holder.cbSelect.setEnabled(!contact.isWhitelisted);
            if (contact.isWhitelisted) {
                holder.tvName.setTextColor(ContextCompat.getColor(WhitelistActivity.this, R.color.disabled_text));
                holder.tvPhone.setTextColor(ContextCompat.getColor(WhitelistActivity.this, R.color.disabled_text));
                holder.llMessageSettings.setVisibility(View.GONE);
            } else {
                holder.tvName.setTextColor(ContextCompat.getColor(WhitelistActivity.this, R.color.primary_text));
                holder.tvPhone.setTextColor(ContextCompat.getColor(WhitelistActivity.this, R.color.secondary_text));
                holder.llMessageSettings.setVisibility(contact.isSelected ? View.VISIBLE : View.GONE);
            }

            // Setup message block checkboxes
            holder.cbBlockMissedCall.setChecked(contact.blockSettings.blockMissedCall);
            holder.cbBlockAfterCall.setChecked(contact.blockSettings.blockAfterCall);
            holder.cbBlockBusy.setChecked(contact.blockSettings.blockBusy);
            holder.cbBlockOutgoingMissed.setChecked(contact.blockSettings.blockOutgoingMissed);

            holder.itemView.setOnClickListener(v -> {
                if (!contact.isWhitelisted) {
                    contact.isSelected = !contact.isSelected;
                    holder.cbSelect.setChecked(contact.isSelected);
                    holder.llMessageSettings.setVisibility(contact.isSelected ? View.VISIBLE : View.GONE);
                }
            });

            holder.cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!contact.isWhitelisted) {
                    contact.isSelected = isChecked;
                    holder.llMessageSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });

            // Message block checkbox listeners
            holder.cbBlockMissedCall.setOnCheckedChangeListener((buttonView, isChecked) -> {
                contact.blockSettings.blockMissedCall = isChecked;
            });

            holder.cbBlockAfterCall.setOnCheckedChangeListener((buttonView, isChecked) -> {
                contact.blockSettings.blockAfterCall = isChecked;
            });

            holder.cbBlockBusy.setOnCheckedChangeListener((buttonView, isChecked) -> {
                contact.blockSettings.blockBusy = isChecked;
            });

            holder.cbBlockOutgoingMissed.setOnCheckedChangeListener((buttonView, isChecked) -> {
                contact.blockSettings.blockOutgoingMissed = isChecked;
            });
        }

        @Override
        public int getItemCount() {
            return contacts.size();
        }

        public void updateContacts(List<Contact> newContacts) {
            this.contacts = newContacts;
            notifyDataSetChanged();
        }

        class ContactSelectionViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvPhone;
            CheckBox cbSelect;
            LinearLayout llMessageSettings;
            CheckBox cbBlockMissedCall, cbBlockAfterCall, cbBlockBusy, cbBlockOutgoingMissed;

            ContactSelectionViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvContactName);
                tvPhone = itemView.findViewById(R.id.tvContactPhone);
                cbSelect = itemView.findViewById(R.id.cbSelectContact);
                llMessageSettings = itemView.findViewById(R.id.llMessageSettings);
                cbBlockMissedCall = itemView.findViewById(R.id.cbBlockMissedCall);
                cbBlockAfterCall = itemView.findViewById(R.id.cbBlockAfterCall);
                cbBlockBusy = itemView.findViewById(R.id.cbBlockBusy);
                cbBlockOutgoingMissed = itemView.findViewById(R.id.cbBlockOutgoingMissed);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);

        mDatabase = FirebaseDatabase.getInstance().getReference();
        allContacts = new ArrayList<>();
        whitelistedContacts = new ArrayList<>();
        filteredContacts = new ArrayList<>();
        currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        initializeViews();
        setupToolbar();
        setupClickListeners();
        checkContactsPermission();
    }

    private void initializeViews() {
        rvWhitelistedContacts = findViewById(R.id.rvWhitelistedContacts);
        btnAddContacts = findViewById(R.id.btnAddContacts);
        btnAddCustomNumber = findViewById(R.id.btnAddCustomNumber);
        tvWhitelistCount = findViewById(R.id.tvWhitelistCount);
        tvEmptyWhitelist = findViewById(R.id.tvEmptyWhitelist);

        whitelistAdapter = new WhitelistAdapter(whitelistedContacts);
        rvWhitelistedContacts.setLayoutManager(new LinearLayoutManager(this));
        rvWhitelistedContacts.setAdapter(whitelistAdapter);

        contactSelectionAdapter = new ContactSelectionAdapter(filteredContacts);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> finish());
        }
    }

    private void setupClickListeners() {
        btnAddContacts.setOnClickListener(v -> showContactSelectionDialog());
        btnAddCustomNumber.setOnClickListener(v -> showCustomNumberDialog());
    }

    private void checkContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_PERMISSION_CODE);
        } else {
            loadContacts();
            loadWhitelistedContacts();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CONTACTS_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadContacts();
            loadWhitelistedContacts();
        } else {
            Toast.makeText(this, "Contacts permission denied. Cannot load contacts.", Toast.LENGTH_LONG).show();
        }
    }

    private void loadContacts() {
        allContacts.clear();
        Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        );

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    if (name != null && phoneNumber != null) {
                        allContacts.add(new Contact(id, name, phoneNumber, false, false));
                    }
                }
            } finally {
                cursor.close();
            }
        }
    }

    private void loadWhitelistedContacts() {
        mDatabase.child("users").child(currentUserId).child("whitelist")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        whitelistedContacts.clear();
                        for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                            String contactId = snapshot.getKey();
                            DataSnapshot contactData = snapshot;

                            String phoneNumber = contactData.child("phoneNumber").getValue(String.class);
                            if (phoneNumber == null) {
                                // Legacy format - direct phone number value
                                phoneNumber = contactData.getValue(String.class);
                            }

                            boolean isCustom = contactId.startsWith("custom_");
                            String name = isCustom ? "Custom Number" : null;

                            // Find contact name from all contacts
                            for (Contact contact : allContacts) {
                                if (contact.id.equals(contactId)) {
                                    name = contact.name;
                                    contact.isWhitelisted = true;
                                    break;
                                }
                            }

                            Contact whitelistedContact = new Contact(contactId, name != null ? name : "Custom Number", phoneNumber, true, isCustom);

                            // Load message block settings
                            if (contactData.hasChild("blockSettings")) {
                                Map<String, Object> blockSettingsData = (Map<String, Object>) contactData.child("blockSettings").getValue();
                                whitelistedContact.blockSettings = new MessageBlockSettings(blockSettingsData);
                            }

                            whitelistedContacts.add(whitelistedContact);
                        }
                        updateWhitelistUI();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        Toast.makeText(WhitelistActivity.this, "Failed to load whitelist: " + databaseError.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void updateWhitelistUI() {
        tvWhitelistCount.setText(String.valueOf(whitelistedContacts.size()));
        if (whitelistedContacts.isEmpty()) {
            rvWhitelistedContacts.setVisibility(View.GONE);
            tvEmptyWhitelist.setVisibility(View.VISIBLE);
        } else {
            rvWhitelistedContacts.setVisibility(View.VISIBLE);
            tvEmptyWhitelist.setVisibility(View.GONE);
        }
        whitelistAdapter.notifyDataSetChanged();
    }

    private void showContactSelectionDialog() {
        contactSelectionDialog = new Dialog(this);
        contactSelectionDialog.setContentView(R.layout.dialog_contact_selection);
        Window window = contactSelectionDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        RecyclerView rvAllContacts = contactSelectionDialog.findViewById(R.id.rvAllContacts);
        TextInputEditText etSearch = contactSelectionDialog.findViewById(R.id.etSearch);
        TextInputLayout tilCustomNumber = contactSelectionDialog.findViewById(R.id.tilCustomNumber);
        TextInputEditText etCustomNumber = contactSelectionDialog.findViewById(R.id.etCustomNumber);
        MaterialButton btnCancel = contactSelectionDialog.findViewById(R.id.btnCancel);
        MaterialButton btnSave = contactSelectionDialog.findViewById(R.id.btnSave);
        ImageView ivClose = contactSelectionDialog.findViewById(R.id.ivCloseDialog);

        // Custom number message settings
        LinearLayout llCustomMessageSettings = contactSelectionDialog.findViewById(R.id.llCustomMessageSettings);
        CheckBox cbCustomBlockMissedCall = contactSelectionDialog.findViewById(R.id.cbCustomBlockMissedCall);
        CheckBox cbCustomBlockAfterCall = contactSelectionDialog.findViewById(R.id.cbCustomBlockAfterCall);
        CheckBox cbCustomBlockBusy = contactSelectionDialog.findViewById(R.id.cbCustomBlockBusy);
        CheckBox cbCustomBlockOutgoingMissed = contactSelectionDialog.findViewById(R.id.cbCustomBlockOutgoingMissed);

        rvAllContacts.setLayoutManager(new LinearLayoutManager(this));
        filteredContacts.clear();
        filteredContacts.addAll(allContacts);
        contactSelectionAdapter.updateContacts(filteredContacts);
        rvAllContacts.setAdapter(contactSelectionAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterContacts(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Show/hide custom message settings based on custom number input
        etCustomNumber.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                llCustomMessageSettings.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnCancel.setOnClickListener(v -> {
            resetSelections();
            contactSelectionDialog.dismiss();
            Toast.makeText(this, "Selection cancelled", Toast.LENGTH_SHORT).show();
        });

        ivClose.setOnClickListener(v -> {
            resetSelections();
            contactSelectionDialog.dismiss();
            Toast.makeText(this, "Selection cancelled", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            String customNumber = etCustomNumber.getText() != null ? etCustomNumber.getText().toString().trim() : "";
            if (!customNumber.isEmpty() && !isValidPhoneNumber(customNumber)) {
                tilCustomNumber.setError("Invalid phone number");
                return;
            }

            MessageBlockSettings customSettings = new MessageBlockSettings();
            if (!customNumber.isEmpty()) {
                customSettings.blockMissedCall = cbCustomBlockMissedCall.isChecked();
                customSettings.blockAfterCall = cbCustomBlockAfterCall.isChecked();
                customSettings.blockBusy = cbCustomBlockBusy.isChecked();
                customSettings.blockOutgoingMissed = cbCustomBlockOutgoingMissed.isChecked();
            }

            saveSelectedContacts(customNumber, customSettings);
        });

        contactSelectionDialog.show();
    }

    private void showCustomNumberDialog() {
        Dialog customNumberDialog = new Dialog(this);
        customNumberDialog.setContentView(R.layout.dialog_custom_number);
        Window window = customNumberDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextInputLayout tilCustomNumber = customNumberDialog.findViewById(R.id.tilCustomNumber);
        TextInputEditText etCustomNumber = customNumberDialog.findViewById(R.id.etCustomNumber);
        LinearLayout llMessageSettings = customNumberDialog.findViewById(R.id.llMessageSettings);
        CheckBox cbBlockMissedCall = customNumberDialog.findViewById(R.id.cbBlockMissedCall);
        CheckBox cbBlockAfterCall = customNumberDialog.findViewById(R.id.cbBlockAfterCall);
        CheckBox cbBlockBusy = customNumberDialog.findViewById(R.id.cbBlockBusy);
        CheckBox cbBlockOutgoingMissed = customNumberDialog.findViewById(R.id.cbBlockOutgoingMissed);
        MaterialButton btnCancel = customNumberDialog.findViewById(R.id.btnCancel);
        MaterialButton btnAdd = customNumberDialog.findViewById(R.id.btnAdd);

        // Show message settings when number is entered
        etCustomNumber.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                llMessageSettings.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnCancel.setOnClickListener(v -> {
            etCustomNumber.setText("");
            customNumberDialog.dismiss();
            Toast.makeText(this, "Custom number entry cancelled", Toast.LENGTH_SHORT).show();
        });

        btnAdd.setOnClickListener(v -> {
            String phoneNumber = etCustomNumber.getText() != null ? etCustomNumber.getText().toString().trim() : "";
            if (phoneNumber.isEmpty()) {
                tilCustomNumber.setError("Please enter a phone number");
                return;
            }
            if (!isValidPhoneNumber(phoneNumber)) {
                tilCustomNumber.setError("Invalid phone number");
                return;
            }

            MessageBlockSettings blockSettings = new MessageBlockSettings();
            blockSettings.blockMissedCall = cbBlockMissedCall.isChecked();
            blockSettings.blockAfterCall = cbBlockAfterCall.isChecked();
            blockSettings.blockBusy = cbBlockBusy.isChecked();
            blockSettings.blockOutgoingMissed = cbBlockOutgoingMissed.isChecked();

            addCustomNumber(phoneNumber, blockSettings);
            customNumberDialog.dismiss();
        });

        customNumberDialog.show();
    }

    private void showMessageBlockSettingsDialog(Contact contact) {
        Dialog settingsDialog = new Dialog(this);
        settingsDialog.setContentView(R.layout.dialog_message_block_settings);
        Window window = settingsDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvTitle = settingsDialog.findViewById(R.id.tvTitle);
        CheckBox cbBlockMissedCall = settingsDialog.findViewById(R.id.cbBlockMissedCall);
        CheckBox cbBlockAfterCall = settingsDialog.findViewById(R.id.cbBlockAfterCall);
        CheckBox cbBlockBusy = settingsDialog.findViewById(R.id.cbBlockBusy);
        CheckBox cbBlockOutgoingMissed = settingsDialog.findViewById(R.id.cbBlockOutgoingMissed);
        MaterialButton btnCancel = settingsDialog.findViewById(R.id.btnCancel);
        MaterialButton btnSave = settingsDialog.findViewById(R.id.btnSave);

        tvTitle.setText("Message Settings for " + contact.name);

        // Set current values
        cbBlockMissedCall.setChecked(contact.blockSettings.blockMissedCall);
        cbBlockAfterCall.setChecked(contact.blockSettings.blockAfterCall);
        cbBlockBusy.setChecked(contact.blockSettings.blockBusy);
        cbBlockOutgoingMissed.setChecked(contact.blockSettings.blockOutgoingMissed);

        btnCancel.setOnClickListener(v -> {
            settingsDialog.dismiss();
            Toast.makeText(this, "Changes cancelled", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            contact.blockSettings.blockMissedCall = cbBlockMissedCall.isChecked();
            contact.blockSettings.blockAfterCall = cbBlockAfterCall.isChecked();
            contact.blockSettings.blockBusy = cbBlockBusy.isChecked();
            contact.blockSettings.blockOutgoingMissed = cbBlockOutgoingMissed.isChecked();

            updateContactBlockSettings(contact);
            settingsDialog.dismiss();
        });

        settingsDialog.show();
    }

    private void updateContactBlockSettings(Contact contact) {
        Map<String, Object> contactUpdate = new HashMap<>();
        contactUpdate.put("phoneNumber", contact.phoneNumber);
        contactUpdate.put("blockSettings", contact.blockSettings.toMap());

        mDatabase.child("users").child(currentUserId).child("whitelist").child(contact.id)
                .setValue(contactUpdate)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Message settings updated for " + contact.name, Toast.LENGTH_SHORT).show();
                    whitelistAdapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to update settings: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void resetSelections() {
        for (Contact contact : allContacts) {
            contact.isSelected = false;
            contact.blockSettings = new MessageBlockSettings();
        }
        contactSelectionAdapter.notifyDataSetChanged();
    }

    private boolean isValidPhoneNumber(String phoneNumber) {
        return phoneNumber.matches("(\\+\\d{1,3}[- ]?)?\\d{10}");
    }

    private void addCustomNumber(String phoneNumber, MessageBlockSettings blockSettings) {
        String customId = "custom_" + UUID.randomUUID().toString();
        String normalizedNumber = normalizePhoneNumber(phoneNumber);

        Map<String, Object> whitelistEntry = new HashMap<>();
        whitelistEntry.put("phoneNumber", normalizedNumber);
        whitelistEntry.put("blockSettings", blockSettings.toMap());

        mDatabase.child("users").child(currentUserId).child("whitelist").child(customId)
                .setValue(whitelistEntry)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Custom number added to whitelist", Toast.LENGTH_SHORT).show();
                    loadWhitelistedContacts();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to add custom number: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void filterContacts(String query) {
        filteredContacts.clear();
        if (query.isEmpty()) {
            filteredContacts.addAll(allContacts);
        } else {
            for (Contact contact : allContacts) {
                if (contact.name.toLowerCase().contains(query.toLowerCase()) ||
                        contact.phoneNumber.contains(query)) {
                    filteredContacts.add(contact);
                }
            }
        }
        contactSelectionAdapter.updateContacts(filteredContacts);
    }

    private void saveSelectedContacts(String customNumber, MessageBlockSettings customSettings) {
        List<Contact> selectedContacts = new ArrayList<>();
        for (Contact contact : allContacts) {
            if (contact.isSelected && !contact.isWhitelisted) {
                selectedContacts.add(contact);
            }
        }

        Map<String, Object> whitelistUpdate = new HashMap<>();
        for (Contact contact : selectedContacts) {
            String normalizedNumber = normalizePhoneNumber(contact.phoneNumber);
            Map<String, Object> contactData = new HashMap<>();
            contactData.put("phoneNumber", normalizedNumber);
            contactData.put("blockSettings", contact.blockSettings.toMap());
            whitelistUpdate.put(contact.id, contactData);
        }

        if (!customNumber.isEmpty()) {
            String customId = "custom_" + UUID.randomUUID().toString();
            Map<String, Object> customData = new HashMap<>();
            customData.put("phoneNumber", normalizePhoneNumber(customNumber));
            customData.put("blockSettings", customSettings.toMap());
            whitelistUpdate.put(customId, customData);
        }

        if (selectedContacts.isEmpty() && customNumber.isEmpty()) {
            Toast.makeText(this, "No contacts or number selected", Toast.LENGTH_SHORT).show();
            return;
        }

        mDatabase.child("users").child(currentUserId).child("whitelist")
                .updateChildren(whitelistUpdate)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Whitelist updated", Toast.LENGTH_SHORT).show();
                    contactSelectionDialog.dismiss();
                    loadWhitelistedContacts();
                    resetSelections();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to update whitelist: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void confirmRemoveFromWhitelist(Contact contact) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Remove from Whitelist")
                .setMessage("Are you sure you want to remove " + (contact.isCustom ? "this custom number" : contact.name) + " from the whitelist?")
                .setPositiveButton("Remove", (dialog, which) -> removeFromWhitelist(contact))
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Toast.makeText(this, "Removal cancelled", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void removeFromWhitelist(Contact contact) {
        mDatabase.child("users").child(currentUserId).child("whitelist").child(contact.id)
                .removeValue()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, (contact.isCustom ? "Custom number" : contact.name) + " removed from whitelist", Toast.LENGTH_SHORT).show();
                    contact.isWhitelisted = false;
                    whitelistedContacts.remove(contact);
                    for (Contact allContact : allContacts) {
                        if (allContact.id.equals(contact.id)) {
                            allContact.isWhitelisted = false;
                            break;
                        }
                    }
                    updateWhitelistUI();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to remove contact: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return "";
        String cleaned = phoneNumber.replaceAll("[^0-9+]", "");
        if (cleaned.startsWith("0")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }
}