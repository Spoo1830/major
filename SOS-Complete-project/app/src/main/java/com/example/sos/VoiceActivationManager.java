// VoiceActivationManager.java
package com.example.sos;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler; // <<< CRITICAL IMPORT
import android.os.Looper;   // <<< CRITICAL IMPORT
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;

public class VoiceActivationManager implements RecognitionListener {

    private static final String TAG = "VoiceActivationManager";
    private static final String ACTIVATION_PHRASE = "help me";

    private final Context context;
    private final SpeechRecognizer speechRecognizer;
    private final Intent speechRecognizerIntent;
    private final ActivationCallback callback;
    private boolean isListening = false;

    // This is the handler that will fix the problem
    private final Handler mainThreadHandler;

    public interface ActivationCallback {
        void onVoiceCommandDetected();
    }

    public VoiceActivationManager(Context context, ActivationCallback callback) {
        this.context = context;
        this.callback = callback;

        // Initialize the handler to run on the main application thread
        this.mainThreadHandler = new Handler(Looper.getMainLooper());

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(this);
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
    }

    public void startListening() {
        if (!isListening && SpeechRecognizer.isRecognitionAvailable(context)) {
            isListening = true;
            // Post the startListening call to the main thread to ensure it's safe
            mainThreadHandler.post(() -> {
                speechRecognizer.startListening(speechRecognizerIntent);
                Log.i(TAG, "Voice listener started on main thread.");
            });
        } else {
            Log.w(TAG, "Speech recognition not available or already listening.");
        }
    }

    public void stopListening() {
        if (isListening) {
            isListening = false;
            mainThreadHandler.post(() -> {
                speechRecognizer.stopListening();
                Log.i(TAG, "Voice listener stopped explicitly.");
            });
        }
    }

    public void destroy() {
        isListening = false;
        mainThreadHandler.post(() -> {
            speechRecognizer.destroy();
            Log.i(TAG, "Voice listener destroyed.");
        });
    }

    @Override
    public void onResults(Bundle results) {
        // This is not used because we use partial results for faster response.
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String spokenText = matches.get(0).toLowerCase().trim();
            Log.i(TAG, "Heard partial phrase: " + spokenText);

            if (spokenText.contains(ACTIVATION_PHRASE)) {
                Log.d(TAG, "!!! ACTIVATION PHRASE DETECTED !!!");

                // --- THIS IS THE CRITICAL FIX ---
                // Instead of calling the callback directly, we post it to the main thread handler.
                // This guarantees that ServiceMine will receive the command.
                if (callback != null) {
                    mainThreadHandler.post(callback::onVoiceCommandDetected);
                }
            }
        }
    }

    @Override
    public void onError(int error) {
        String errorMessage;
        switch (error) {
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: errorMessage = "No speech input"; break;
            case SpeechRecognizer.ERROR_NO_MATCH: errorMessage = "No match"; break;
            default: errorMessage = "Error code: " + error; break;
        }
        Log.d(TAG, "onError: " + errorMessage);

        // When an error occurs, we restart listening to make it continuous
        if (isListening) {
            mainThreadHandler.post(() -> {
                speechRecognizer.cancel();
                speechRecognizer.startListening(speechRecognizerIntent);
            });
        }
    }

    // --- Other required methods (no changes needed) ---
    @Override
    public void onReadyForSpeech(Bundle params) { Log.d(TAG, "onReadyForSpeech"); }

    @Override
    public void onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech"); }

    @Override
    public void onRmsChanged(float rmsdB) { /* Not needed */ }

    @Override
    public void onBufferReceived(byte[] buffer) { /* Not needed */ }

    @Override
    public void onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech"); }

    @Override
    public void onEvent(int eventType, Bundle params) { /* Not needed */ }
}
