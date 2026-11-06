package com.nexgen.leadmaster;

import android.app.Activity;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
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
