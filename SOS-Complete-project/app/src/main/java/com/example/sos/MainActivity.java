package com.example.sos;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

// CRITICAL FIX: Import MaterialButton specifically
import com.google.android.material.button.MaterialButton;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // CRITICAL FIX: Use MaterialButton instead of the generic Button
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private TextView serviceStatusTextView;
    private ImageView serviceStatusIcon;

    private DatabaseHelper databaseHelper;
    private ActivityResultLauncher<String[]> requestPermissionLauncher;
    private ActivityResultLauncher<IntentSenderRequest> locationSettingsLauncher;

    private final Handler uiUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable uiUpdateRunnable;

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_LOCATION
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity created.");

        startButton = findViewById(R.id.start);
        stopButton = findViewById(R.id.stop);
        serviceStatusTextView = findViewById(R.id.statusText);
        serviceStatusIcon = findViewById(R.id.statusIcon);

        databaseHelper = new DatabaseHelper(this);
        setupLaunchers();
        createNotificationChannel();

        startButton.setOnClickListener(v -> handleStartClick());
        stopButton.setOnClickListener(v -> handleStopClick());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Starting UI updater.");
        startUiUpdater();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Stopping UI updater.");
        stopUiUpdater();
    }

    private void startUiUpdater() {
        uiUpdateRunnable = () -> {
            updateUiState();
            uiUpdateHandler.postDelayed(uiUpdateRunnable, 500);
        };
        uiUpdateHandler.post(uiUpdateRunnable);
    }

    private void stopUiUpdater() {
        if (uiUpdateRunnable != null) {
            uiUpdateHandler.removeCallbacks(uiUpdateRunnable);
        }
    }

    private void updateUiState() {
        SharedPreferences prefs = getSharedPreferences("SOS_SERVICE_STATUS", MODE_PRIVATE);
        boolean isServiceRunning = prefs.getBoolean("is_running", false);

        startButton.setEnabled(!isServiceRunning);
        stopButton.setEnabled(isServiceRunning);
        serviceStatusTextView.setText(isServiceRunning ? "Service Active" : "Service Inactive");

        if (isServiceRunning) {
            serviceStatusIcon.setImageResource(R.drawable.safety_check);
            // CRITICAL FIX: Use universal Android colors
            stopButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_dark)));
            stopButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
            stopButton.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.white)));
        } else {
            serviceStatusIcon.setImageResource(R.drawable.safety_check_off);
            // CRITICAL FIX: Use universal Android colors to reset
            stopButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray)));
            stopButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
            stopButton.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.white)));
        }
    }

    private void handleStartClick() {
        Log.d(TAG, "Start button clicked.");
        if (databaseHelper.count() == 0) { promptToRegisterContacts(); return; }
        if (!areAllPermissionsGranted()) { requestMissingPermissions(); return; }
        if (!isLocationEnabled()) { promptToEnableLocation(); return; }

        startTheService();

        // Immediate feedback
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        serviceStatusTextView.setText("Service Active");
        serviceStatusIcon.setImageResource(R.drawable.safety_check);
        // CRITICAL FIX: Use universal Android colors for immediate feedback
        stopButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_dark)));
        stopButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        stopButton.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.white)));
    }

    private void handleStopClick() {
        Log.d(TAG, "Stop button clicked.");
        Intent serviceIntent = new Intent(this, ServiceMine.class);
        serviceIntent.setAction("stop");
        startService(serviceIntent);

        // Immediate feedback
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        serviceStatusTextView.setText("Service Inactive");
        serviceStatusIcon.setImageResource(R.drawable.safety_check_off);
        // CRITICAL FIX: Use universal Android colors for immediate feedback
        stopButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray)));
        stopButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        stopButton.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.white)));
    }

    // --- All other helper methods are unchanged and correct. ---

    private void startTheService() {
        Log.d(TAG, "Attempting to start the service now.");
        Intent serviceIntent = new Intent(this, ServiceMine.class);
        serviceIntent.setAction("Start");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Starting Monitoring...", Toast.LENGTH_SHORT).show();
    }

    private void setupLaunchers() {
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
            if (areAllPermissionsGranted()) handleStartClick();
            else showPermissionDeniedDialog();
        });
        locationSettingsLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) handleStartClick();
            else Toast.makeText(this, "Location must be enabled to start.", Toast.LENGTH_LONG).show();
        });
    }

    private boolean areAllPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void requestMissingPermissions() {
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS);
    }

    private void promptToRegisterContacts() {
        Toast.makeText(this, "Please register at least one contact", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(this, RegisterNumberActivity.class);
        startActivity(intent);
        finish();
    }

    private void promptToEnableLocation() {
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder().addLocationRequest(LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)).setAlwaysShow(true);
        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());
        task.addOnFailureListener(this, e -> {
            if (e instanceof ResolvableApiException) {
                try {
                    locationSettingsLauncher.launch(new IntentSenderRequest.Builder(((ResolvableApiException) e).getResolution()).build());
                } catch (Exception ex) { Log.e(TAG, "Failed to show location settings dialog.", ex); }
            }
        });
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("This app needs all permissions to function. Please grant them in app settings.")
                .setPositiveButton("Go to Settings", (dialog, which) -> openAppSettings())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel("MYID", "SOS Service Channel", NotificationManager.IMPORTANCE_DEFAULT)
            );
        }
    }
}
