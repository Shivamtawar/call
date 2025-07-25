
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

        public Contact(String id, String name, String phoneNumber, boolean isWhitelisted, boolean isCustom) {
            this.id = id;
            this.name = name;
            this.phoneNumber = phoneNumber;
            this.isWhitelisted = isWhitelisted;
            this.isSelected = false;
            this.isCustom = isCustom;
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

            holder.ivRemove.setOnClickListener(v -> {
                confirmRemoveFromWhitelist(contact);
            });
        }

        @Override
        public int getItemCount() {
            return contacts.size();
        }

        class WhitelistViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvPhone;
            ImageView ivRemove;

            WhitelistViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvContactName);
                tvPhone = itemView.findViewById(R.id.tvContactPhone);
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
            }

            holder.itemView.setOnClickListener(v -> {
                if (!contact.isWhitelisted) {
                    contact.isSelected = !contact.isSelected;
                    holder.cbSelect.setChecked(contact.isSelected);
                }
            });

            holder.cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!contact.isWhitelisted) {
                    contact.isSelected = isChecked;
                }
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

            ContactSelectionViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvContactName);
                tvPhone = itemView.findViewById(R.id.tvContactPhone);
                cbSelect = itemView.findViewById(R.id.cbSelectContact);
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
                            String phoneNumber = snapshot.getValue(String.class);
                            boolean isCustom = contactId.startsWith("custom_");
                            String name = isCustom ? "Custom Number" : null;
                            for (Contact contact : allContacts) {
                                if (contact.id.equals(contactId)) {
                                    name = contact.name;
                                    contact.isWhitelisted = true;
                                    break;
                                }
                            }
                            whitelistedContacts.add(new Contact(contactId, name != null ? name : "Custom Number", phoneNumber, true, isCustom));
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

        btnCancel.setOnClickListener(v -> {
            // Reset all selections and clear custom number input
            for (Contact contact : allContacts) {
                contact.isSelected = false;
            }
            if (etCustomNumber != null) {
                etCustomNumber.setText("");
            }
            contactSelectionAdapter.notifyDataSetChanged();
            contactSelectionDialog.dismiss();
            Toast.makeText(this, "Selection cancelled", Toast.LENGTH_SHORT).show();
        });

        ivClose.setOnClickListener(v -> {
            // Same behavior as cancel button
            for (Contact contact : allContacts) {
                contact.isSelected = false;
            }
            if (etCustomNumber != null) {
                etCustomNumber.setText("");
            }
            contactSelectionAdapter.notifyDataSetChanged();
            contactSelectionDialog.dismiss();
            Toast.makeText(this, "Selection cancelled", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            String customNumber = etCustomNumber.getText() != null ? etCustomNumber.getText().toString().trim() : "";
            if (!customNumber.isEmpty() && !isValidPhoneNumber(customNumber)) {
                tilCustomNumber.setError("Invalid phone number");
                return;
            }
            saveSelectedContacts(customNumber);
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
        MaterialButton btnCancel = customNumberDialog.findViewById(R.id.btnCancel);
        MaterialButton btnAdd = customNumberDialog.findViewById(R.id.btnAdd);

        btnCancel.setOnClickListener(v -> {
            // Clear input and dismiss dialog
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
            addCustomNumber(phoneNumber);
            customNumberDialog.dismiss();
        });

        customNumberDialog.show();
    }

    private boolean isValidPhoneNumber(String phoneNumber) {
        return phoneNumber.matches("(\\+\\d{1,3}[- ]?)?\\d{10}");
    }

    private void addCustomNumber(String phoneNumber) {
        String customId = "custom_" + UUID.randomUUID().toString();
        String normalizedNumber = normalizePhoneNumber(phoneNumber);
        Map<String, Object> whitelistUpdate = new HashMap<>();
        whitelistUpdate.put(customId, normalizedNumber);

        mDatabase.child("users").child(currentUserId).child("whitelist")
                .updateChildren(whitelistUpdate)
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

    private void saveSelectedContacts(String customNumber) {
        List<Contact> selectedContacts = new ArrayList<>();
        for (Contact contact : allContacts) {
            if (contact.isSelected && !contact.isWhitelisted) {
                selectedContacts.add(contact);
            }
        }

        Map<String, Object> whitelistUpdate = new HashMap<>();
        for (Contact contact : selectedContacts) {
            String normalizedNumber = normalizePhoneNumber(contact.phoneNumber);
            whitelistUpdate.put(contact.id, normalizedNumber);
        }

        if (!customNumber.isEmpty()) {
            String customId = "custom_" + UUID.randomUUID().toString();
            whitelistUpdate.put(customId, normalizePhoneNumber(customNumber));
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
                    for (Contact contact : allContacts) {
                        contact.isSelected = false;
                    }
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
