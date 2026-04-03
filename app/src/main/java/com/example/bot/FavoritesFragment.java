package com.example.bot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FavoritesFragment extends Fragment {

    private static final String PREFS_NAME = "FavoritesPrefs";
    private static final String KEY_FAVORITES_DATA = "favorites_data";

    private ListView listView;
    private Button clearAllButton;
    private FavoriteAdapter adapter;
    private List<String> favoritesList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_favorites, container, false);

        listView = view.findViewById(R.id.favoritesListView);
        clearAllButton = view.findViewById(R.id.clearAllButton);

        loadFavorites();

        clearAllButton.setOnClickListener(v -> {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_FAVORITES_DATA).apply();
            favoritesList.clear();
            adapter.notifyDataSetChanged();
            Toast.makeText(getContext(), "Список очищен", Toast.LENGTH_SHORT).show();
        });

        // Настраиваем клик по самому элементу списка
        listView.setOnItemClickListener((parent, view1, position, id) -> {
            showPlantDetailDialog(favoritesList.get(position));
        });

        // Предотвращаем клики сквозь фрагмент
        view.setOnClickListener(v -> {});

        return view;
    }

    private void showPlantDetailDialog(String itemData) {
        String[] parts = itemData.split("\\|");
        String original = parts.length > 0 ? parts[0] : "";
        String translated = parts.length > 1 ? parts[1] : "";
        String description = parts.length > 2 ? parts[2] : "";
        String base64 = parts.length > 3 ? parts[3] : "";

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_plant_detail, null);
        
        ImageView detailImage = dialogView.findViewById(R.id.detailImage);
        TextView originalName = dialogView.findViewById(R.id.detailOriginalName);
        TextView translatedName = dialogView.findViewById(R.id.detailTranslatedName);
        TextView descriptionText = dialogView.findViewById(R.id.detailDescription);
        Button closeButton = dialogView.findViewById(R.id.closeDetailButton);

        originalName.setText(original);
        translatedName.setText(translated);
        descriptionText.setText(description);
        
        if (!base64.isEmpty()) {
            detailImage.setImageBitmap(base64ToBitmap(base64));
        }

        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void loadFavorites() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(KEY_FAVORITES_DATA, new HashSet<>());
        favoritesList = new ArrayList<>(set);
        adapter = new FavoriteAdapter(requireContext(), favoritesList);
        listView.setAdapter(adapter);
    }

    private Bitmap base64ToBitmap(String base64) {
        try {
            byte[] decodedString = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
        } catch (Exception e) {
            return null;
        }
    }

    private class FavoriteAdapter extends ArrayAdapter<String> {
        public FavoriteAdapter(Context context, List<String> items) {
            super(context, R.layout.item_favorite, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_favorite, parent, false);
            }

            String item = getItem(position);
            if (item == null) return convertView;

            String[] parts = item.split("\\|");
            String original = parts.length > 0 ? parts[0] : "";
            String translated = parts.length > 1 ? parts[1] : "";
            String description = parts.length > 2 ? parts[2] : "";
            String base64 = parts.length > 3 ? parts[3] : "";

            TextView originalText = convertView.findViewById(R.id.favOriginalName);
            TextView translatedText = convertView.findViewById(R.id.favTranslatedName);
            TextView descriptionText = convertView.findViewById(R.id.favDescription);
            ImageView imgView = convertView.findViewById(R.id.favImage);
            ImageButton deleteBtn = convertView.findViewById(R.id.deleteFav);

            originalText.setText(original);
            translatedText.setText(translated);
            descriptionText.setText(description);
            
            if (!base64.isEmpty()) {
                imgView.setImageBitmap(base64ToBitmap(base64));
            }

            // Важно: кнопка удаления не должна мешать клику по всей строке
            deleteBtn.setFocusable(false);
            deleteBtn.setOnClickListener(v -> {
                SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                Set<String> favorites = new HashSet<>(prefs.getStringSet(KEY_FAVORITES_DATA, new HashSet<>()));
                favorites.remove(item);
                prefs.edit().putStringSet(KEY_FAVORITES_DATA, favorites).apply();

                favoritesList.remove(position);
                notifyDataSetChanged();
            });

            return convertView;
        }
    }
}