package it.bose.overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import java.net.URI;

public class Setup extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(56, 36, 56, 36);
        TextView title = new TextView(this);
        title.setText("Bose Overlay");
        title.setTextSize(30);
        layout.addView(title);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("http://indirizzo-raspberry:5000");
        address.setText(getPreferences(0).getString("address", ""));
        layout.addView(address);
        Button enable = new Button(this);
        enable.setText("Consenti visualizzazione sopra altre app");
        enable.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "Permesso overlay non disponibile in Impostazioni", Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(enable);
        Button start = new Button(this);
        start.setText("Avvia collegamento al Raspberry");
        start.setOnClickListener(v -> {
            String url = address.getText().toString().trim().replaceAll("/+$", "");
            try {
                URI uri = URI.create(url);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null
                        || uri.getUserInfo() != null) throw new IllegalArgumentException();
                if (!hasPermission()) return;
                getPreferences(0).edit().putString("address", url).apply();
                startForegroundService(new Intent(this, Monitor.class).putExtra("address", url));
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "Inserisci un URL HTTP o HTTPS valido", Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(start);
        Button test = new Button(this);
        test.setText("Prova overlay tra 5 secondi");
        test.setOnClickListener(v -> {
            if (hasPermission()) startForegroundService(new Intent(this, Monitor.class).putExtra("test", true));
        });
        layout.addView(test);
        Button stop = new Button(this);
        stop.setText("Ferma companion");
        stop.setOnClickListener(v -> stopService(new Intent(this, Monitor.class)));
        layout.addView(stop);
        setContentView(layout);
        start.requestFocus();
        String savedAddress = getPreferences(0).getString("address", "");
        if (!savedAddress.isEmpty() && Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, Monitor.class).putExtra("address", savedAddress));
        }
    }

    private boolean hasPermission() {
        if (Settings.canDrawOverlays(this)) return true;
        Toast.makeText(this, "Consenti prima la visualizzazione sopra altre app", Toast.LENGTH_LONG).show();
        return false;
    }
}
