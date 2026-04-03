package com.example.bot;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.LocalModel;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Query;

// Обновленный интерфейс для Wikipedia API с использованием поиска (generator=search)
interface WikipediaService {
    @GET("api.php?action=query&format=json&prop=extracts&exintro&explaintext&redirects=1&generator=search&gsrlimit=1")
    Call<WikiResponse> getDetails(@Query("gsrsearch") String query);
}

class WikiResponse { public QueryData query; }
class QueryData { public Map<String, PageData> pages; }
class PageData { public String extract; public String title; }

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PlantRecognition";
    private static final String PREFS_NAME = "FavoritesPrefs";
    private static final String KEY_FAVORITES_DATA = "favorites_data";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA, Manifest.permission.INTERNET};

    private static final Map<String, String> PLANT_DICTIONARY = new HashMap<String, String>() {{
        put("leucanthemum vulgare", "Нивяник");
        put("taraxacum officinale", "Одуванчик");
        put("daisy", "Маргаритка");
        put("rose", "Роза");
        put("tulip", "Тюльпан");
    }};

    private PreviewView previewView;
    private ImageView selectedImageView;
    private TextView resultTextView;
    private Button identifyButton;
    private Button galleryButton;
    private Button viewFavoritesButton;
    private ImageButton favoriteButton;
    
    private ImageLabeler labeler;
    private Translator translator;
    private ExecutorService cameraExecutor;
    
    private String currentOriginalName = "";
    private String currentTranslatedName = "";
    private String currentDescription = "";
    private Bitmap currentBitmap;
    private boolean isTranslatorReady = false;

    private WikipediaService wikiService;

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) processGalleryImage(imageUri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        selectedImageView = findViewById(R.id.selectedImageView);
        resultTextView = findViewById(R.id.resultTextView);
        identifyButton = findViewById(R.id.identifyButton);
        galleryButton = findViewById(R.id.galleryButton);
        viewFavoritesButton = findViewById(R.id.viewFavoritesButton);
        favoriteButton = findViewById(R.id.favoriteButton);

        initLabeler();
        initTranslator();
        initWikiService();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        identifyButton.setOnClickListener(v -> {
            if (selectedImageView.getVisibility() == View.VISIBLE) resetUI();
            else identifyPlant();
        });

        galleryButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            galleryLauncher.launch(intent);
        });

        favoriteButton.setOnClickListener(v -> toggleFavorite());

        viewFavoritesButton.setOnClickListener(v -> {
            getSupportFragmentManager().beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                    .replace(R.id.fragment_container, new FavoritesFragment())
                    .addToBackStack(null)
                    .commit();
        });

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void initWikiService() {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://ru.wikipedia.org/w/")
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        wikiService = retrofit.create(WikipediaService.class);
    }

    private void toggleFavorite() {
        if (currentOriginalName.isEmpty() || currentBitmap == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> favorites = new HashSet<>(prefs.getStringSet(KEY_FAVORITES_DATA, new HashSet<>()));

        String entryPrefix = currentOriginalName + "|";
        String existingEntry = null;
        for (String entry : favorites) {
            if (entry.startsWith(entryPrefix)) {
                existingEntry = entry;
                break;
            }
        }

        if (existingEntry != null) {
            favorites.remove(existingEntry);
            favoriteButton.setImageResource(android.R.drawable.btn_star_big_off);
            Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show();
        } else {
            String base64Image = bitmapToBase64(currentBitmap);
            favorites.add(currentOriginalName + "|" + currentTranslatedName + "|" + currentDescription + "|" + base64Image);
            favoriteButton.setImageResource(android.R.drawable.btn_star_big_on);
            Toast.makeText(this, "Добавлено в избранное!", Toast.LENGTH_SHORT).show();
        }

        prefs.edit().putStringSet(KEY_FAVORITES_DATA, favorites).apply();
    }

    private String bitmapToBase64(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 200, 200, true);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    private void resetUI() {
        selectedImageView.setVisibility(View.GONE);
        favoriteButton.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        identifyButton.setText("Камера");
        resultTextView.setText("Наведите камеру или выберите фото");
        currentOriginalName = "";
        currentTranslatedName = "";
        currentDescription = "";
        currentBitmap = null;
    }

    private void initLabeler() {
        try {
            LocalModel localModel = new LocalModel.Builder().setAssetFilePath("3.tflite").build();
            CustomImageLabelerOptions options = new CustomImageLabelerOptions.Builder(localModel)
                    .setConfidenceThreshold(0.3f).setMaxResultCount(1).build();
            labeler = ImageLabeling.getClient(options);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка модели", e);
        }
    }

    private void initTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.RUSSIAN).build();
        translator = Translation.getClient(options);
        
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    isTranslatorReady = true;
                    Log.d(TAG, "Переводчик готов");
                })
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка загрузки переводчика", e));
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
        Bitmap bitmap = previewView.getBitmap();
        if (bitmap == null) return;
        currentBitmap = bitmap;
        displaySelectedImage(bitmap);
        processImage(InputImage.fromBitmap(bitmap, 0));
    }

    private void processGalleryImage(Uri uri) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
            currentBitmap = bitmap;
            displaySelectedImage(bitmap);
            processImage(InputImage.fromBitmap(bitmap, 0));
        } catch (IOException e) {
            resultTextView.setText("Ошибка загрузки фото");
        }
    }

    private void displaySelectedImage(Bitmap bitmap) {
        selectedImageView.setImageBitmap(bitmap);
        selectedImageView.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
        identifyButton.setText("Сбросить");
    }

    private void processImage(InputImage image) {
        resultTextView.setText("🔍 Распознаю объект...");
        labeler.process(image).addOnSuccessListener(labels -> {
            if (labels.isEmpty()) {
                resultTextView.setText("Растение не определено");
            } else {
                ImageLabel label = labels.get(0);
                String original = label.getText();
                currentOriginalName = original;
                
                String key = original.toLowerCase(Locale.US).trim();
                if (PLANT_DICTIONARY.containsKey(key)) {
                    currentTranslatedName = PLANT_DICTIONARY.get(key);
                    fetchInfoWithFallback(currentTranslatedName, original, label.getConfidence());
                } else if (isTranslatorReady) {
                    translator.translate(original).addOnSuccessListener(translated -> {
                        currentTranslatedName = translated;
                        fetchInfoWithFallback(translated, original, label.getConfidence());
                    }).addOnFailureListener(e -> {
                        currentTranslatedName = original;
                        fetchInfoWithFallback(original, null, label.getConfidence());
                    });
                } else {
                    currentTranslatedName = original;
                    fetchInfoWithFallback(original, null, label.getConfidence());
                }
            }
        }).addOnFailureListener(e -> resultTextView.setText("Ошибка анализа изображения"));
    }

    private void fetchInfoWithFallback(String query, String fallback, float confidence) {
        resultTextView.setText("🌐 Ищу в Википедии: " + query + "...");
        wikiService.getDetails(query).enqueue(new Callback<WikiResponse>() {
            @Override
            public void onResponse(Call<WikiResponse> call, Response<WikiResponse> response) {
                if (response.isSuccessful() && hasWikiContent(response.body())) {
                    String description = getWikiExtract(response.body());
                    updateResultUI(currentOriginalName, currentTranslatedName, description, confidence);
                } else if (fallback != null && !fallback.isEmpty()) {
                    // Если по основному запросу не нашли, пробуем оригинал (латынь)
                    fetchInfoWithFallback(fallback, null, confidence);
                } else {
                    updateResultUI(currentOriginalName, currentTranslatedName, "К сожалению, подробная информация в Википедии не найдена.", confidence);
                }
            }

            @Override
            public void onFailure(Call<WikiResponse> call, Throwable t) {
                updateResultUI(currentOriginalName, currentTranslatedName, "Ошибка сети: не удалось получить данные из интернета.", confidence);
            }
        });
    }

    private boolean hasWikiContent(WikiResponse response) {
        if (response == null || response.query == null || response.query.pages == null) return false;
        for (PageData page : response.query.pages.values()) {
            if (page.extract != null && !page.extract.isEmpty()) return true;
        }
        return false;
    }

    private String getWikiExtract(WikiResponse response) {
        for (PageData page : response.query.pages.values()) {
            if (page.extract != null) return page.extract;
        }
        return "";
    }

    private void updateResultUI(String original, String translated, String description, float confidence) {
        currentDescription = description;
        String structuredDescription = structureWikiText(description);

        String displayText = String.format(Locale.US, "🌿 %s\n(%s)\nУверенность: %.0f%%\n\n%s", 
                original, translated.isEmpty() ? original : translated, confidence * 100, structuredDescription);
        resultTextView.setText(displayText);
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> favorites = prefs.getStringSet(KEY_FAVORITES_DATA, new HashSet<>());
        
        boolean isFav = false;
        String prefix = currentOriginalName + "|";
        for (String entry : favorites) {
            if (entry.startsWith(prefix)) {
                isFav = true;
                break;
            }
        }
        
        favoriteButton.setImageResource(isFav ? 
                android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
        
        favoriteButton.setVisibility(View.VISIBLE);
    }

    private String structureWikiText(String text) {
        if (text.isEmpty() || text.contains("не найдена") || text.contains("Ошибка")) return text;
        StringBuilder sb = new StringBuilder();
        sb.append("📖 Справка из Википедии:\n");
        if (text.length() > 600) sb.append(text.substring(0, 600)).append("...");
        else sb.append(text);
        return sb.toString();
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (translator != null) translator.close();
        cameraExecutor.shutdown();
    }
}