package com.example.bot;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.LocalModel;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PlantRecognition";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private PreviewView previewView;
    private TextView resultTextView;
    private Button identifyButton;
    private ImageLabeler labeler;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);
        identifyButton = findViewById(R.id.identifyButton);

        initLabeler();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        identifyButton.setOnClickListener(v -> identifyPlant());
        cameraExecutor = Executors.newSingleThreadExecutor();
        
        resultTextView.setText("Наведите камеру на растение");
    }

    private void initLabeler() {
        try {
            LocalModel localModel = new LocalModel.Builder()
                    .setAssetFilePath("3.tflite")
                    .build();

            CustomImageLabelerOptions options = new CustomImageLabelerOptions.Builder(localModel)
                    .setConfidenceThreshold(0.3f)
                    .setMaxResultCount(5)
                    .build();

            labeler = ImageLabeling.getClient(options);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка модели", e);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Ошибка камеры", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void identifyPlant() {
        if (labeler == null) return;

        Bitmap bitmap = previewView.getBitmap();
        if (bitmap == null) return;

        identifyButton.setEnabled(false);
        resultTextView.setText("🔍 Распознаю...");

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        labeler.process(image)
                .addOnSuccessListener(labels -> {
                    if (labels.isEmpty()) {
                        resultTextView.setText("Растение не определено");
                    } else {
                        showResults(labels);
                    }
                    identifyButton.setEnabled(true);
                })
                .addOnFailureListener(e -> {
                    resultTextView.setText("Ошибка анализа");
                    identifyButton.setEnabled(true);
                });
    }

    private void showResults(List<ImageLabel> labels) {
        StringBuilder sb = new StringBuilder();
        sb.append("Результаты:\n");
        for (ImageLabel label : labels) {
            sb.append("🌿 ").append(label.getText())
                    .append(String.format(Locale.US, " (%.0f%%)\n", label.getConfidence() * 100));
        }
        resultTextView.setText(sb.toString());
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}