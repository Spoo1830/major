// RecordingsActivity.java
package com.example.sos;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// We now implement the listener interface from our adapter
public class RecordingsActivity extends AppCompatActivity implements RecordingsAdapter.OnRecordingClickListener {

    private RecyclerView recyclerView;
    private TextView noRecordingsView;
    private RecordingsAdapter adapter;
    private MaterialToolbar topAppBar;

    private List<File> recordingFiles; // Make the list a class-level variable

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);

        recyclerView = findViewById(R.id.recyclerViewRecordings);
        noRecordingsView = findViewById(R.id.tvNoRecordings);
        topAppBar = findViewById(R.id.topAppBar);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        topAppBar.setNavigationOnClickListener(v -> finish());

        loadRecordings();
    }

    private void loadRecordings() {
        recordingFiles = getRecordingFiles(); // Load files into the class-level list

        if (recordingFiles.isEmpty()) {
            noRecordingsView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            noRecordingsView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            // "this" works because our Activity now implements the listener interface
            adapter = new RecordingsAdapter(recordingFiles, this);
            recyclerView.setAdapter(adapter);
        }
    }

    private List<File> getRecordingFiles() {
        File recordingsDir = new File(getExternalFilesDir(null), "Recordings");
        if (recordingsDir.exists() && recordingsDir.isDirectory()) {
            File[] files = recordingsDir.listFiles();
            if (files != null) {
                List<File> fileList = new ArrayList<>(Arrays.asList(files));
                Collections.sort(fileList, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                return fileList;
            }
        }
        return new ArrayList<>();
    }

    // This method is called when the user clicks anywhere on the list item EXCEPT the delete icon
    @Override
    public void onRecordingClick(File file) {
        Uri fileUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, "audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(intent, "Play or Share Recording"));
        } catch (Exception e) {
            Toast.makeText(this, "No app found to handle this audio file.", Toast.LENGTH_SHORT).show();
        }
    }

    // This method is called ONLY when the user clicks the delete icon
    @Override
    public void onDeleteClick(final File file, final int position) {
        // Build an alert dialog to confirm the deletion
        new AlertDialog.Builder(this)
                .setTitle("Delete Recording")
                .setMessage("Are you sure you want to permanently delete this recording?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    // User clicked "Delete"
                    deleteFileAndRefreshList(file, position);
                })
                .setNegativeButton("Cancel", null) // User clicked "Cancel", do nothing
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void deleteFileAndRefreshList(File fileToDelete, int position) {
        try {
            if (fileToDelete.delete()) {
                // File was deleted successfully
                recordingFiles.remove(position); // Remove the file from our list
                adapter.notifyItemRemoved(position); // Tell the adapter to update the UI
                adapter.notifyItemRangeChanged(position, recordingFiles.size()); // Update positions for remaining items

                Toast.makeText(this, "Recording deleted.", Toast.LENGTH_SHORT).show();

                // If the list is now empty, show the "No Recordings Found" text
                if (recordingFiles.isEmpty()) {
                    noRecordingsView.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                }
            } else {
                // File could not be deleted
                Toast.makeText(this, "Failed to delete recording.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error deleting file.", Toast.LENGTH_SHORT).show();
        }
    }
}
