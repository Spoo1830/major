// RecordingsAdapter.java
package com.example.sos;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.ViewHolder> {

    private final List<File> recordingFiles;
    private final OnRecordingClickListener listener;

    // We create a listener interface that can handle two different events:
    // a click on the item itself (to play) and a click on the delete icon.
    public interface OnRecordingClickListener {
        void onRecordingClick(File file);
        void onDeleteClick(File file, int position); // We also pass the position
    }

    public RecordingsAdapter(List<File> recordingFiles, OnRecordingClickListener listener) {
        this.recordingFiles = recordingFiles;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_recording, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = recordingFiles.get(position);
        holder.recordingName.setText(file.getName());

        // Set the listener for the entire item (to play the recording)
        holder.itemView.setOnClickListener(v -> listener.onRecordingClick(file));

        // Set the listener specifically for the delete icon
        holder.deleteIcon.setOnClickListener(v -> listener.onDeleteClick(file, holder.getAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return recordingFiles.size();
    }

    // The ViewHolder now also holds a reference to the delete icon
    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView recordingName;
        ImageView deleteIcon; // The new delete icon

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            recordingName = itemView.findViewById(R.id.tvRecordingName);
            deleteIcon = itemView.findViewById(R.id.ivDeleteIcon); // Find the delete icon by its ID
        }
    }
}
