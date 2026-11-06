package com.nexgen.leadmaster;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    public static final int NATIVE_VERSION = 4;
    private static final String UPDATE_URL =
            "https://github.com/vinayakg820-arch/Lead-Management-/releases/latest/download/update.json";
    private static final String CHANNEL_ID = "lead_alerts";
    private volatile JSONObject pendingUpdate;

    // WhatsApp step-by-step sending (welcome → each product → links)
    private final ArrayList<Intent> waQueue = new ArrayList<>();
    private int waIndex = 0;
    private boolean waAwaitingReturn = false;
    private boolean waLeftApp = false;
    private volatile boolean waCancelled = false;

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
    protected void onStop() {
        super.onStop();
        if (waAwaitingReturn) waLeftApp = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waAwaitingReturn && waLeftApp) {
            waAwaitingReturn = false;
            waLeftApp = false;
            if (waCancelled || waIndex >= waQueue.size()) {
                finishWaSequence(waCancelled ? "cancelled" : "done");
            } else {
                sendToJs("waNext", waIndex + "/" + waQueue.size());
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() { launchNextWaStep(); }
                }, 1800);
            }
        }
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
        public void sendWhatsAppSequence(final String phone, final String stepsJson, final String appPref) {
            waCancelled = false;
            io.execute(new Runnable() {
                @Override
                public void run() { prepareWaSequence(phone, stepsJson, appPref); }
            });
        }

        @JavascriptInterface
        public void cancelWhatsAppSequence() {
            waCancelled = true;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!waAwaitingReturn) finishWaSequence("cancelled");
                }
            });
        }

        @JavascriptInterface
        public void checkForUpdate(final String currentWebVersion) {
            io.execute(new Runnable() {
                @Override
                public void run() { doCheckForUpdate(currentWebVersion); }
            });
        }

        /** Checks the EdgeOne website: a newer uploaded ZIP shows "Update Now" in the app. */
        @JavascriptInterface
        public void checkForUpdateFrom(final String currentWebVersion, final String siteUrl) {
            io.execute(new Runnable() {
                @Override
                public void run() { doCheckSite(currentWebVersion, siteUrl); }
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

    // ---------------------------------------------------------------- WhatsApp sequence

    /** Builds one WhatsApp share per step: text, or a product (photos + caption once). */
    private void prepareWaSequence(String phone, String stepsJson, String appPref) {
        final String pkg = pickWhatsApp(appPref);
        if (pkg == null) {
            toastFromBackground("WhatsApp is not installed on this phone");
            sendToJs("waDone", "error:WhatsApp is not installed");
            return;
        }
        final String jid = (phone == null ? "" : phone.replaceAll("[^0-9]", "")) + "@s.whatsapp.net";
        final ArrayList<Intent> intents = new ArrayList<>();
        try {
            JSONArray steps = new JSONArray(stepsJson == null ? "[]" : stepsJson);
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create cache folder");
            for (int i = 0; i < steps.length(); i++) {
                if (waCancelled) { sendToJs("waDone", "cancelled"); return; }
                JSONObject step = steps.getJSONObject(i);
                String caption = step.optString("caption", step.optString("text", ""));
                JSONArray urls = step.optJSONArray("images");
                if ("images".equals(step.optString("type")) && urls != null && urls.length() > 0) {
                    sendToJs("waProgress", "Preparing photos " + (i + 1) + "/" + steps.length());
                    ArrayList<File> files = new ArrayList<>();
                    for (int k = 0; k < urls.length() && k < 6; k++) {
                        String url = urls.getString(k);
                        File f = new File(dir, "p_" + md5(url) + ".jpg");
                        if ((f.exists() && f.length() > 0) || download(url, f)) files.add(f);
                    }
                    if (files.isEmpty()) {
                        if (!caption.isEmpty()) intents.add(textIntent(pkg, jid, caption));
                        continue;
                    }
                    if (step.optBoolean("collage", true)) {
                        File collage = files.size() == 1 ? files.get(0) : buildCollage(files, new File(dir, "c_" + md5(urls.toString()) + ".jpg"));
                        intents.add(imageIntent(pkg, jid, java.util.Collections.singletonList(collage), caption));
                    } else {
                        intents.add(imageIntent(pkg, jid, files, caption));
                    }
                } else if (!caption.isEmpty()) {
                    intents.add(textIntent(pkg, jid, caption));
                }
            }
        } catch (Exception e) {
            sendToJs("waDone", "error:" + e.getMessage());
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                waQueue.clear();
                waQueue.addAll(intents);
                waIndex = 0;
                if (waQueue.isEmpty()) finishWaSequence("done");
                else launchNextWaStep();
            }
        });
    }

    private Intent textIntent(String pkg, String jid, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        i.putExtra("jid", jid);
        i.setPackage(pkg);
        return i;
    }

    private Intent imageIntent(String pkg, String jid, List<File> files, String caption) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType("image/jpeg");
        if (caption != null && !caption.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, caption);
        i.putExtra("jid", jid);
        i.setPackage(pkg);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    private void launchNextWaStep() {
        if (waCancelled || waIndex >= waQueue.size()) {
            finishWaSequence(waCancelled ? "cancelled" : "done");
            return;
        }
        Intent next = waQueue.get(waIndex);
        waIndex++;
        sendToJs("waStep", waIndex + "/" + waQueue.size());
        waAwaitingReturn = true;
        waLeftApp = false;
        try {
            startActivity(next);
        } catch (Exception e) {
            waAwaitingReturn = false;
            finishWaSequence("error:Could not open WhatsApp");
        }
    }

    private void finishWaSequence(String result) {
        waQueue.clear();
        waIndex = 0;
        waAwaitingReturn = false;
        sendToJs("waDone", result);
    }

    /** Puts 2–6 product photos into one picture (grid), so the caption appears only once under them. */
    private File buildCollage(List<File> files, File out) throws IOException {
        if (out.exists() && out.length() > 0) return out;
        final int W = 1440, GAP = 12;
        int n = Math.min(files.size(), 6);
        ArrayList<Rect> cells = new ArrayList<>();
        int H;
        if (n == 2) {
            int c = (W - GAP) / 2;
            cells.add(new Rect(0, 0, c, c));
            cells.add(new Rect(c + GAP, 0, W, c));
            H = c;
        } else if (n == 3) {
            int top = W * 2 / 3, c = (W - GAP) / 2;
            cells.add(new Rect(0, 0, W, top));
            cells.add(new Rect(0, top + GAP, c, top + GAP + c));
            cells.add(new Rect(c + GAP, top + GAP, W, top + GAP + c));
            H = top + GAP + c;
        } else if (n == 4) {
            int c = (W - GAP) / 2;
            for (int r = 0; r < 2; r++)
                for (int col = 0; col < 2; col++)
                    cells.add(new Rect(col * (c + GAP), r * (c + GAP), col * (c + GAP) + c, r * (c + GAP) + c));
            H = 2 * c + GAP;
        } else if (n == 5) {
            int c3 = (W - 2 * GAP) / 3, c2 = (W - GAP) / 2;
            cells.add(new Rect(0, 0, c2, c3));
            cells.add(new Rect(c2 + GAP, 0, W, c3));
            for (int col = 0; col < 3; col++)
                cells.add(new Rect(col * (c3 + GAP), c3 + GAP, col * (c3 + GAP) + c3, 2 * c3 + GAP));
            H = 2 * c3 + GAP;
        } else {
            int c = (W - 2 * GAP) / 3;
            for (int r = 0; r < 2; r++)
                for (int col = 0; col < 3; col++)
                    cells.add(new Rect(col * (c + GAP), r * (c + GAP), col * (c + GAP) + c, r * (c + GAP) + c));
            H = 2 * c + GAP;
        }
        Bitmap canvasBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBmp);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < n; i++) {
            Rect dst = cells.get(i);
            Bitmap b = decodeSampled(files.get(i), dst.width(), dst.height());
            if (b == null) continue;
            float scale = Math.max((float) dst.width() / b.getWidth(), (float) dst.height() / b.getHeight());
            int sw = Math.round(dst.width() / scale), sh = Math.round(dst.height() / scale);
            int sx = (b.getWidth() - sw) / 2, sy = (b.getHeight() - sh) / 2;
            canvas.drawBitmap(b, new Rect(sx, sy, sx + sw, sy + sh), dst, paint);
            b.recycle();
        }
        try (OutputStream os = new FileOutputStream(out)) {
            canvasBmp.compress(Bitmap.CompressFormat.JPEG, 88, os);
        } finally {
            canvasBmp.recycle();
        }
        return out;
    }

    private Bitmap decodeSampled(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
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

    private void doCheckSite(String currentWebVersion, String siteUrl) {
        try {
            if (siteUrl == null || !siteUrl.startsWith("https://")) return;
            String html = httpGetString(siteUrl + (siteUrl.contains("?") ? "&" : "?") + "lmcheck=" + System.currentTimeMillis());
            Matcher v = Pattern.compile("const APP_VERSION = '([0-9][0-9.]*)'").matcher(html);
            if (!v.find()) return;
            String version = v.group(1);
            if (compareVersions(version, currentWebVersion) <= 0) return;
            int minNative = 0;
            Matcher mn = Pattern.compile("const MIN_NATIVE_VERSION = (\\d+)").matcher(html);
            if (mn.find()) minNative = parseIntSafe(mn.group(1));
            String notes = "";
            Matcher nt = Pattern.compile("const RELEASE_NOTES_RAW = (\"(?:[^\"\\\\]|\\\\.)*\")").matcher(html);
            if (nt.find()) {
                try { notes = new JSONArray("[" + nt.group(1) + "]").getString(0); } catch (Exception ignored) { }
            }
            JSONObject u = new JSONObject();
            u.put("version", version);
            u.put("notes", notes);
            u.put("web", siteUrl + (siteUrl.contains("?") ? "&" : "?") + "lmv=" + version);
            boolean needsApk = minNative > NATIVE_VERSION;
            u.put("needsApk", needsApk);
            if (needsApk) {
                try {
                    JSONObject gh = new JSONObject(httpGetString(UPDATE_URL));
                    u.put("apk", gh.optString("apk", ""));
                } catch (Exception e) {
                    return; // cannot find the new APK right now; try again later
                }
            }
            pendingUpdate = u;
            sendToJs("update", u.toString());
        } catch (Exception ignored) {
            // offline: try again later
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
