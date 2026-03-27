package com.defensevo1.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DefenseVO1";
    private static final int PERM_REQUEST = 100;
    private static final String CHANNEL_ID = "defense_vo1_alert";
    private static int notifId = 1;

    private WebView webView;
    private SmsReceiver smsReceiver;

    private static final String[] PERMISSIONS = {
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen — status bar transparan
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        }

        webView = new WebView(this);
        setContentView(webView);

        createNotificationChannel();
        setupWebView();
        checkPermissions();
    }

    // ══════════════════════════════════════
    // WebView Setup
    // ══════════════════════════════════════
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // JS Bridge
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (!url.startsWith("file://") && !url.startsWith("about:")) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)));
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    // ══════════════════════════════════════
    // JavaScript Bridge
    // ══════════════════════════════════════
    public class AndroidBridge {

        @JavascriptInterface
        public String getPlatform() { return "android"; }

        @JavascriptInterface
        public boolean hasSmsPermission() {
            return ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestSmsPermission() {
            MainActivity.this.checkPermissions();
        }

        @JavascriptInterface
        public void showToast(String msg) {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        /** Dipanggil dari JS saat deteksi ancaman — tampilkan notif native */
        @JavascriptInterface
        public void showNativeAlert(String sender, String body, String level) {
            new Handler(Looper.getMainLooper()).post(() -> {
                String title = level.equals("high")
                    ? "🚨 PESAN BERBAHAYA!" : "⚠️ Pesan Mencurigakan";
                showNotification(title, sender + ": " + body);
            });
        }
    }

    // ══════════════════════════════════════
    // SMS BroadcastReceiver — tangkap SMS masuk
    // ══════════════════════════════════════
    private class SmsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

            SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (msgs == null) return;

            for (SmsMessage sms : msgs) {
                String sender = sms.getDisplayOriginatingAddress();
                String body   = sms.getMessageBody();
                String time   = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                    Locale.US).format(new Date(sms.getTimestampMillis()));

                Log.d(TAG, "SMS masuk dari: " + sender);

                try {
                    JSONObject json = new JSONObject();
                    json.put("sender",    sender != null ? sender : "Unknown");
                    json.put("body",      body   != null ? body   : "");
                    json.put("timestamp", time);

                    final String js = "if(window.receiveAndroidSms)" +
                        "window.receiveAndroidSms(" + json + ");";

                    new Handler(Looper.getMainLooper()).post(() ->
                        webView.evaluateJavascript(js, null));

                } catch (Exception e) {
                    Log.e(TAG, "Error kirim ke JS: " + e.getMessage());
                }
            }
        }
    }

    // ══════════════════════════════════════
    // Notifikasi native Android
    // ══════════════════════════════════════
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Defense VO1 Alert",
                NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Peringatan SMS berbahaya");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    private void showNotification(String title, String body) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVibrate(new long[]{0, 300, 100, 300});

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .notify(notifId++, nb.build());
    }

    // ══════════════════════════════════════
    // Permission
    // ══════════════════════════════════════
    private void checkPermissions() {
        boolean allOk = true;
        for (String p : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                allOk = false; break;
            }
        }
        if (allOk) registerSmsReceiver();
        else ActivityCompat.requestPermissions(this, PERMISSIONS, PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_REQUEST) {
            boolean ok = results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                registerSmsReceiver();
                webView.evaluateJavascript(
                    "if(window.onSmsPermissionGranted)window.onSmsPermissionGranted();", null);
                Toast.makeText(this, "✅ SMS monitoring aktif!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                    "⚠️ Izin SMS ditolak — SMS tidak bisa dipantau",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void registerSmsReceiver() {
        if (smsReceiver != null) return;
        smsReceiver = new SmsReceiver();
        IntentFilter f = new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(smsReceiver, f);
        }
        Log.d(TAG, "SMS Receiver aktif");
    }

    // ══════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override
    protected void onDestroy() {
        if (smsReceiver != null) { unregisterReceiver(smsReceiver); smsReceiver = null; }
        webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
