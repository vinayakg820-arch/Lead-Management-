package com.nexgen.leadmaster;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LeadMaster Android app: shows the bundled web app (web/index.html) in a WebView and adds
 * native features the browser cannot do:
 *  - shareWhatsApp(): sends the welcome message + product photos straight into the customer's chat
 *  - saveTextFile(): saves backups to Downloads
 *  - photo upload (file chooser) and opening tel:/maps/other links in their apps
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int MAX_PHOTOS = 30;

    /** Increase by 1 whenever this Java code / AndroidManifest changes. The GitHub build publishes it in
     *  update.json; if a newer release has a higher number the app downloads the new APK, otherwise it only
     *  downloads the new web app (instant update, no reinstall). */
    public static final int NATIVE_VERSION = 2;
    private static final String UPDATE_URL =
            "https://github.com/vinayakg820-arch/Lead-Management-/releases/latest/download/update.json";
    private static final String CHANNEL_ID = "lead_alerts";
    private volatile JSONObject pendingUpdate;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clearDownloadedWebIfAppUpdated();
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2001);
        }

        webView = new WebView(this);
        setContentView(webView);

        // Serves the downloaded (updated) web app if there is one, otherwise the one bundled in the APK
        final WebViewAssetLoader.AssetsPathHandler bundled = new WebViewAssetLoader.AssetsPathHandler(this);
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.PathHandler() {
                    @Override
                    public WebResourceResponse handle(String path) {
                        if ("index.html".equals(path)) {
                            File updated = downloadedWebFile();
                            if (updated.exists() && updated.length() > 0) {
                                try {
                                    WebResourceResponse r = new WebResourceResponse("text/html", "utf-8", new FileInputStream(updated));
                                    Map<String, String> headers = new HashMap<>();
                                    headers.put("Cache-Control", "no-cache");
                                    r.setResponseHeaders(headers);
                                    return r;
                                } catch (IOException e) {
                                    // fall back to the bundled copy
                                }
                            }
                        }
                        return bundled.handle(path);
                    }
                })
                .build();

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) return false;
                openExternal(uri);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                String type = "*/*";
                String[] accept = params.getAcceptTypes();
                if (accept != null && accept.length > 0 && accept[0] != null && accept[0].contains("/")) type = accept[0];
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(type);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select"), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast("No app found to pick files");
                    return false;
                }
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        webView.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- JS bridge

    private class Bridge {
        @JavascriptInterface
        public void shareWhatsApp(final String phone, final String text, final String imagesJson, final String appPref) {
            io.execute(new Runnable() {
                @Override
                public void run() { doShareWhatsApp(phone, text, imagesJson, appPref); }
            });
        }

        @JavascriptInterface
        public void saveTextFile(final String name, final String content) {
            io.execute(new Runnable() {
                @Override
                public void run() { doSaveTextFile(name, content); }
            });
        }

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public int nativeVersion() { return NATIVE_VERSION; }

        @JavascriptInterface
        public void checkForUpdate(final String currentWebVersion) {
            io.execute(new Runnable() {
                @Override
                public void run() { doCheckForUpdate(currentWebVersion); }
            });
        }

        @JavascriptInterface
        public void applyUpdate() {
            io.execute(new Runnable() {
                @Override
                public void run() { doApplyUpdate(); }
            });
        }

        @JavascriptInterface
        public void showNotification(final String title, final String body) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() { postNotification(title, body); }
            });
        }
    }

    // ---------------------------------------------------------------- In-app updates

    private File downloadedWebFile() {
        return new File(new File(getFilesDir(), "web"), "index.html");
    }

    /** A newly installed APK already contains the latest web app, so drop any older downloaded copy. */
    private void clearDownloadedWebIfAppUpdated() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            SharedPreferences prefs = getSharedPreferences("leadmaster", MODE_PRIVATE);
            if (prefs.getLong("installedVersionCode", -1) != code) {
                File f = downloadedWebFile();
                if (f.exists()) f.delete();
                prefs.edit().putLong("installedVersionCode", code).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private void doCheckForUpdate(String currentWebVersion) {
        try {
            JSONObject u = new JSONObject(httpGetString(UPDATE_URL));
            String version = u.optString("version", "");
            if (version.isEmpty() || compareVersions(version, currentWebVersion) <= 0) return;
            u.put("needsApk", u.optInt("nativeVersion", 0) > NATIVE_VERSION);
            pendingUpdate = u;
            sendToJs("update", u.toString());
        } catch (Exception ignored) {
            // offline or no release yet: try again later
        }
    }

    private void doApplyUpdate() {
        JSONObject u = pendingUpdate;
        if (u == null) {
            sendToJs("updateError", "Update information not found. Please try again.");
            return;
        }
        try {
            if (u.optBoolean("needsApk", false)) {
                installApkUpdate(u.getString("apk"));
                return;
            }
            String version = u.getString("version");
            File target = downloadedWebFile();
            File dir = target.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create folder");
            File fresh = new File(dir, "index.new.html");
            if (fresh.exists()) fresh.delete();
            if (!download(u.getString("web"), fresh)) throw new IOException("Download failed. Check your internet and try again.");
            String html = new String(java.nio.file.Files.readAllBytes(fresh.toPath()), StandardCharsets.UTF_8);
            if (fresh.length() < 50000 || !html.contains("const APP_VERSION") || !html.contains("'" + version + "'")) {
                fresh.delete();
                throw new IOException("Downloaded file is not valid");
            }
            if (target.exists()) target.delete();
            if (!fresh.renameTo(target)) throw new IOException("Could not save the update");
            mainHandler.post(new Runnable() {
                @Override
                public void run() { webView.reload(); }
            });
        } catch (Exception e) {
            sendToJs("updateError", "Update failed: " + e.getMessage());
        }
    }

    private void installApkUpdate(String apkUrl) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            sendToJs("updateError", "Please allow LeadMaster to install updates (turn the switch ON), come back and tap Update Now again.");
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                }
            });
            return;
        }
        sendToJs("updateProgress", "Downloading the new app version…");
        File dir = new File(getCacheDir(), "shared");
        if (!dir.exists()) dir.mkdirs();
        File apk = new File(dir, "LeadMaster-update.apk");
        if (apk.exists()) apk.delete();
        if (!download(apkUrl, apk)) {
            sendToJs("updateError", "Download failed. Check your internet and try again.");
            return;
        }
        final Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk),
                "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(install);
                } catch (Exception e) {
                    sendToJs("updateError", "Could not open the installer: " + e.getMessage());
                }
            }
        });
    }

    private String httpGetString(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static int compareVersions(String a, String b) {
        String[] pa = (a == null ? "" : a).split("\\."), pb = (b == null ? "" : b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0, y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void sendToJs(final String type, final String payload) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript("window.__lmNative && window.__lmNative(" + JSONObject.quote(type) + "," + JSONObject.quote(payload) + ")", null);
            }
        });
    }

    // ---------------------------------------------------------------- Notifications

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Lead alerts", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("New or updated leads");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void postNotification(String title, String body) {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify((int) (System.currentTimeMillis() % 100000), n);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- WhatsApp

    private void doShareWhatsApp(String phone, final String text, String imagesJson, String appPref) {
        final String pkg = pickWhatsApp(appPref);
        if (pkg == null) {
            toastFromBackground("WhatsApp is not installed on this phone");
            return;
        }
        final String number = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        final ArrayList<Uri> photos = new ArrayList<>();
        try {
            JSONArray urls = new JSONArray(imagesJson == null || imagesJson.isEmpty() ? "[]" : imagesJson);
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create cache folder");
            for (int i = 0; i < urls.length() && photos.size() < MAX_PHOTOS; i++) {
                String url = urls.getString(i);
                File file = new File(dir, "p_" + md5(url) + ".jpg");
                if ((file.exists() && file.length() > 0) || download(url, file)) {
                    photos.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
                }
            }
        } catch (Exception e) {
            // send whatever photos were downloaded (or text only)
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (photos.isEmpty()) {
                    openChatWithText(pkg, number, text);
                    return;
                }
                Intent intent;
                if (photos.size() == 1) {
                    intent = new Intent(Intent.ACTION_SEND);
                    intent.putExtra(Intent.EXTRA_STREAM, photos.get(0));
                } else {
                    intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, photos);
                }
                intent.setType("image/jpeg");
                if (text != null && !text.isEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text);
                intent.putExtra("jid", number + "@s.whatsapp.net"); // opens this customer's chat directly
                intent.setPackage(pkg);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    openChatWithText(pkg, number, text);
                }
            }
        });
    }

    private void openChatWithText(String pkg, String number, String text) {
        Uri uri = Uri.parse("https://wa.me/" + number + (text == null || text.isEmpty() ? "" : "?text=" + Uri.encode(text)));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage(pkg);
        try {
            startActivity(intent);
        } catch (Exception e) {
            openExternal(uri);
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String pickWhatsApp(String pref) {
        boolean business = isInstalled("com.whatsapp.w4b");
        boolean personal = isInstalled("com.whatsapp");
        if ("personal".equals(pref) && personal) return "com.whatsapp";
        if ("business".equals(pref) && business) return "com.whatsapp.w4b";
        if (business) return "com.whatsapp.w4b";
        if (personal) return "com.whatsapp";
        return null;
    }

    private boolean download(String url, File out) {
        HttpURLConnection conn = null;
        File tmp = new File(out.getPath() + ".part");
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return tmp.renameTo(out);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---------------------------------------------------------------- Backup file

    private void doSaveTextFile(String name, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Could not create the file");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IOException("Could not write the file");
                    os.write(bytes);
                }
                toastFromBackground("Saved to Downloads: " + name);
            } else {
                File dir = new File(getCacheDir(), "shared");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create cache folder");
                File file = new File(dir, name);
                try (OutputStream os = new FileOutputStream(file)) {
                    os.write(bytes);
                }
                final Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/json");
                send.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() { startActivity(Intent.createChooser(send, "Save backup")); }
                });
            }
        } catch (Exception e) {
            toastFromBackground("Backup failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private void openExternal(Uri uri) {
        try {
            Intent intent = "tel".equals(uri.getScheme())
                    ? new Intent(Intent.ACTION_DIAL, uri)
                    : new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("No app found to open this link");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void toastFromBackground(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() { toast(msg); }
        });
    }
}
