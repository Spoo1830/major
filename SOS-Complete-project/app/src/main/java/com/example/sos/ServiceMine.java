package com.example.sos;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class ServiceMine extends Service implements SensorEventListener, VoiceActivationManager.ActivationCallback {

    private static final String TAG = "ServiceMine";
    private static final int SHAKE_THRESHOLD_G_FORCE = 3;
    private static final long ALERT_COOLDOWN_MS = 20000; // 20-second cooldown
    private static final long RECORDING_DURATION_MS = 40000; // 40 seconds

    private Vibrator vibrator;
    private DatabaseHelper db;
    private FusedLocationProviderClient fusedLocationClient;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private VoiceActivationManager voiceManager;
    private MediaRecorder mediaRecorder;

    private long lastAlertTime = 0;
    private volatile boolean isAlertInProgress = false;

    @Override
    public void onCreate() {
        super.onCreate();
        updateServiceStatus(true);
        Log.d(TAG, "Service CREATED.");

        db = new DatabaseHelper(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        voiceManager = new VoiceActivationManager(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equalsIgnoreCase(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundServiceNotification();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Accelerometer listener registered.");
        }
        // Start listening for the "helpMe" command
        voiceManager.startListening();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        updateServiceStatus(false);
        Log.d(TAG, "Service DESTROYED.");
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (voiceManager != null) voiceManager.destroy();
        stopAudioRecording();
        stopForeground(true);
    }

    // --- DETECTION METHODS ---

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            double gForce = Math.sqrt(Math.pow(event.values[0], 2) + Math.pow(event.values[1], 2) + Math.pow(event.values[2], 2)) / SensorManager.GRAVITY_EARTH;
            if (gForce > SHAKE_THRESHOLD_G_FORCE) {
                // When a shake is detected, call the master trigger method.
                triggerAlert("Shake");
            }
        }
    }

    @Override
    public void onVoiceCommandDetected() {
        // When the voice command is heard, call the same master trigger method.
        triggerAlert("Voice");
    }

    // --- MASTER ALERT TRIGGER ---

    private synchronized void triggerAlert(String source) {
        long currentTime = System.currentTimeMillis();

        // **CRITICAL CHECK**: Only proceed if an alert is NOT already in progress
        // AND the 20-second cooldown period has passed.
        if (isAlertInProgress || (currentTime - lastAlertTime) < ALERT_COOLDOWN_MS) {
            Log.w(TAG, "Alert trigger from [" + source + "] ignored: Cooldown active or another alert is already in progress.");
            return;
        }

        // --- START THE ALERT PROCESS ---
        Log.d(TAG, "ALERT TRIGGERED by [" + source + "]. Locking process.");

        // 1. Lock the process immediately
        isAlertInProgress = true;
        lastAlertTime = currentTime;

        // 2. Vibrate for feedback
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        // 3. Send SMS with Location (in the background)
        updateLocationAndSendSms();

        // 4. Start Audio Recording (which will unlock the process when finished)
        startAudioRecording();
    }


    // --- ACTION METHODS ---

    private void updateLocationAndSendSms() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SMS not sent. Location permission denied.");
            sendSmsMessages("Location permission denied.");
            return;
        }
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    String myLocation = (location != null) ? "https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() : "Could not get current location.";
                    sendSmsMessages(myLocation);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get location.", e);
                    sendSmsMessages("Failed to get location.");
                });
    }

    private void sendSmsMessages(String location) {
        ArrayList<ContactModel> list = db.fetchData();
        if (list.isEmpty()) {
            Log.w(TAG, "No contacts found to send SMS.");
            return; // No contacts registered, no need to proceed.
        }
        SharedPreferences sp = getSharedPreferences("message", MODE_PRIVATE);
        String customMsg = sp.getString("msg", "I am in DANGER, I need help. Please urgently reach me out.");
        String finalMessage = String.format("Hey, %%s! %s\n\nMy location:\n%s", customMsg, location);
        SmsManager smsManager = SmsManager.getDefault();
        for (ContactModel contact : list) {
            try {
                smsManager.sendTextMessage(contact.getNumber(), null, String.format(finalMessage, contact.getName()), null, null);
                Log.d(TAG, "SMS sent successfully to " + contact.getName());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send SMS to " + contact.getNumber(), e);
            }
        }
    }

    private void startAudioRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio permission denied. Unlocking process.");
            isAlertInProgress = false; // Unlock if we can't record
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "SOS_Recording_" + timeStamp + ".3gp";
        File recordingsDir = new File(getExternalFilesDir(null), "Recordings");
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }
        File audioFile = new File(recordingsDir, fileName);
        String audioFilePath = audioFile.getAbsolutePath();

        mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            Log.d(TAG, "Audio recording started. Saving to: " + audioFilePath);

            // Schedule the stop and the final unlock after 40 seconds
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "40 seconds passed. Stopping audio and unlocking process.");
                stopAudioRecording();
                isAlertInProgress = false; // **UNLOCK** after recording is done
            }, RECORDING_DURATION_MS);

        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "MediaRecorder setup failed. Unlocking process.", e);
            releaseMediaRecorder();
            isAlertInProgress = false; // **UNLOCK** on failure
        }
    }

    private void stopAudioRecording() {
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); Log.d(TAG, "Audio recording stopped."); }
            catch (RuntimeException e) { Log.e(TAG, "MediaRecorder stop failed.", e); }
            finally { releaseMediaRecorder(); }
        }
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset(); mediaRecorder.release(); mediaRecorder = null;
        }
    }

    private void updateServiceStatus(boolean isRunning) {
        SharedPreferences prefs = getSharedPreferences("SOS_SERVICE_STATUS", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("is_running", isRunning);
        editor.apply();
    }

    private void startForegroundServiceNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, "MYID")
                .setContentTitle("SOS Service is Active")
                .setContentText("Listening for shake & voice commands.")
                .setSmallIcon(R.drawable.siren)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(115, notification);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
