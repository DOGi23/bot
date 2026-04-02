package com.example.bot;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.LocalModel;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
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
    private Translator translator;
    private ExecutorService cameraExecutor;
    private boolean isTranslatorReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);
        identifyButton = findViewById(R.id.identifyButton);

        initLabeler();
        initTranslator();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        identifyButton.setOnClickListener(v -> identifyPlant());
        cameraExecutor = Executors.newSingleThreadExecutor();
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

    private void initTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build();
        translator = Translation.getClient(options);

        // УБРАЛИ требование Wi-Fi, теперь можно качать через любой интернет
        DownloadConditions conditions = new DownloadConditions.Builder()
                .build();

        resultTextView.setText("⏳ Загрузка переводчика (нужен интернет)...");
        identifyButton.setEnabled(false);

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    isTranslatorReady = true;
                    identifyButton.setEnabled(true);
                    resultTextView.setText("Наведите камеру на растение");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Ошибка загрузки переводчика", e);
                    resultTextView.setText("Ошибка загрузки. Проверьте интернет и нажмите кнопку Начать заново (перезапустите приложение)");
                    // Позволим кнопке работать, если перевод не удался (будет на англ)
                    identifyButton.setEnabled(true);
                });
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
                        identifyButton.setEnabled(true);
                        return;
                    }

                    if (isTranslatorReady) {
                        translateResults(labels);
                    } else {
                        showEnglishResults(labels);
                    }
                })
                .addOnFailureListener(e -> {
                    resultTextView.setText("Ошибка анализа");
                    identifyButton.setEnabled(true);
                });
    }

    private void translateResults(List<ImageLabel> labels) {
        List<Task<String>> translationTasks = new ArrayList<>();
        for (ImageLabel label : labels) {
            translationTasks.add(translator.translate(label.getText()));
        }

        Tasks.whenAllSuccess(translationTasks).addOnSuccessListener(translatedList -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Результаты (RU):\n");
            for (int i = 0; i < translatedList.size(); i++) {
                String translated = (String) translatedList.get(i);
                float confidence = labels.get(i).getConfidence();
                sb.append("🌿 ").append(translated)
                        .append(String.format(Locale.US, " (%.0f%%)\n", confidence * 100));
            }
            resultTextView.setText(sb.toString());
            identifyButton.setEnabled(true);
        }).addOnFailureListener(e -> {
            showEnglishResults(labels);
        });
    }

    private void showEnglishResults(List<ImageLabel> labels) {
        StringBuilder sb = new StringBuilder();
        sb.append("Результаты (EN - перевод не готов):\n");
        for (ImageLabel label : labels) {
            sb.append("🌿 ").append(label.getText())
                    .append(String.format(Locale.US, " (%.0f%%)\n", label.getConfidence() * 100));
        }
        resultTextView.setText(sb.toString());
        identifyButton.setEnabled(true);
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
        if (translator != null) translator.close();
        cameraExecutor.shutdown();
    }
}