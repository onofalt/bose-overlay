package it.bose.overlay;

import android.app.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

public class Monitor extends Service {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> polling;
    private WindowManager windows;
    private LinearLayout panel;
    private TextView label;
    private ProgressBar bar;
    private final Runnable hide = () -> {
        if (panel != null) {
            windows.removeView(panel);
            panel = null;
        }
    };
    private Integer previous;
    private int generation;
    private String address;
    private boolean awake;
    private boolean listening;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (address == null) return;
            boolean nextAwake = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if (nextAwake != awake) setAwake(nextAwake, goAsync());
        }
    };
    private final Runnable preview = () -> show(25, true);

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        windows = getSystemService(WindowManager.class);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("bose", "Bose Overlay", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "bose").setContentTitle("Bose Overlay")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build());
        if (intent == null) {
            stopSelf(startId);
        } else if (intent.getBooleanExtra("test", false)) {
            ui.removeCallbacks(preview);
            ui.postDelayed(preview, 5000);
        } else {
            String configured = intent.getStringExtra("address");
            if (configured == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (!configured.equals(address)) {
                address = configured;
                if (!listening) {
                    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
                    filter.addAction(Intent.ACTION_SCREEN_OFF);
                    registerReceiver(screen, filter);
                    listening = true;
                }
                if (getSystemService(PowerManager.class).isInteractive()) setAwake(true, null);
            }
        }
        return START_NOT_STICKY;
    }

    private void setAwake(boolean nextAwake, BroadcastReceiver.PendingResult result) {
        awake = nextAwake;
        String host = address;
        int requestGeneration = ++generation;
        previous = null;
        if (polling != null) {
            polling.cancel(false);
            polling = null;
        }
        if (!awake) {
            ui.removeCallbacks(preview);
            ui.removeCallbacks(hide);
            hide.run();
        }
        executor.execute(() -> {
            try {
                boolean accepted = sendPower(host, nextAwake);
                ui.post(() -> {
                    if (generation != requestGeneration || !nextAwake) return;
                    if (accepted) showMessage("On");
                    polling = executor.scheduleWithFixedDelay(
                            () -> poll(host, requestGeneration), 0, 125, TimeUnit.MILLISECONDS);
                });
            } finally {
                if (result != null) result.finish();
            }
        });
    }

    private boolean sendPower(String host, boolean on) {
        String path = on ? "/On" : "/Off";
        for (int attempt = 0; attempt < (on ? 3 : 1); attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(host + path).openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(2200);
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                android.util.Log.i("BoseOverlay", path + " HTTP " + status);
                if (status == 200) return true;
            } catch (IOException error) {
                android.util.Log.w("BoseOverlay", path + " fallito: " + error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (on && attempt < 2) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private void poll(String address, int requestGeneration) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address + "/api/state").openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) return;
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                for (int length; (length = stream.read(buffer)) != -1;) {
                    if (body.size() + length > 8192) return;
                    body.write(buffer, 0, length);
                }
                int volume = new JSONObject(body.toString(StandardCharsets.UTF_8.name())).optInt("volume", -1);
                if (volume < 0 || volume > 100) return;
                ui.post(() -> {
                    if (generation != requestGeneration) return;
                    if (previous != null && previous != volume) show(volume, false);
                    previous = volume;
                });
            }
        } catch (IOException | org.json.JSONException e) {
            android.util.Log.d("BoseOverlay", "Lettura stato fallita", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void show(int volume, boolean test) {
        render((test ? "Prova · " : "") + volume, volume);
    }

    private void showMessage(String message) {
        render(message, null);
    }

    private void render(String message, Integer volume) {
        if (!Settings.canDrawOverlays(this)) return;
        if (panel == null) {
            LinearLayout view = new LinearLayout(this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(20), dp(14), dp(20), dp(14));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(26, 28, 32));
            background.setCornerRadius(dp(14));
            view.setBackground(background);
            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            view.addView(header, new LinearLayout.LayoutParams(-1, -2));
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.bose_logo);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setContentDescription("Bose");
            header.addView(logo, new LinearLayout.LayoutParams(dp(110), dp(20)));
            label = new TextView(this);
            label.setTextSize(22);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.END);
            header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            view.addView(bar, new LinearLayout.LayoutParams(-1, dp(12)));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(300), -2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            params.x = dp(40);
            params.y = dp(40);
            try {
                windows.addView(view, params);
                panel = view;
            } catch (SecurityException | WindowManager.BadTokenException e) {
                android.util.Log.w("BoseOverlay", "Overlay rifiutato", e);
                return;
            }
        }
        label.setText(message);
        bar.setVisibility(volume == null ? View.GONE : View.VISIBLE);
        if (volume != null) bar.setProgress(volume);
        ui.removeCallbacks(hide);
        ui.postDelayed(hide, 2800);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
        generation++;
        if (listening) unregisterReceiver(screen);
        if (polling != null) polling.cancel(false);
        executor.shutdown();
        ui.removeCallbacksAndMessages(null);
        hide.run();
        super.onDestroy();
    }
}
