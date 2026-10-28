package com.wavemusic.player;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.app.AlertDialog;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.webkit.WebViewAssetLoader;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Trusted offline HTML frontend + native Media3 playback/Android file picker. */
public class MainActivity extends Activity {
    private static final int REQUEST_PICK = 101, REQUEST_EXPORT = 102;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}(?:-[A-Za-z0-9_-]{1,100})*");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private ValueCallback<Uri[]> fileCallback;
    private final Object ioLock = new Object();
    private FileOutputStream uploadStream, exportStream;
    private File uploadTemp, exportTemp, stagedExport;
    private String uploadId, exportName;
    private long uploadExpected, uploadReceived, exportExpected, exportReceived;
    private volatile String stateJson = "{\"playing\":false,\"id\":\"\",\"position\":0,\"duration\":0,\"ready\":false}";
    private Runnable queuedPlay;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090c10);
        getWindow().setNavigationBarColor(0xff11161c);
        File music = musicDir();
        web = new WebView(this);
        web.setBackgroundColor(0xff090c10);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);           // ONLY for bundled, trusted app UI.
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Required for user-chosen Storage Access Framework files.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        WebView.setWebContentsDebuggingEnabled(false);
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!"https".equals(u.getScheme()) || !"appassets.androidplatform.net".equals(u.getHost()))
                    return new WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
                return assetLoader.shouldInterceptRequest(u);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !APP_URL.equals(req.getUrl().toString());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                    .setPositiveButton("Ja",(d,w)->result.confirm())
                    .setNegativeButton("Abbrechen",(d,w)->result.cancel())
                    .setOnCancelListener(d->result.cancel()).show();
                return true;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(intent, REQUEST_PICK); }
                catch (ActivityNotFoundException e) { fileCallback.onReceiveValue(null); fileCallback = null; return false; }
                return true;
            }
        });
        // Only the offline app origin has access to the bridge; remote navigation is blocked.
        web.addJavascriptInterface(new Bridge(), "WAVEAndroid");
        SessionToken token = new SessionToken(this, new android.content.ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                if (queuedPlay != null) { queuedPlay.run(); queuedPlay = null; }
            } catch (Exception e) { Log.e("WAVE", "Cannot connect to player", e); }
        }, command -> handler.post(command));
        web.loadUrl(APP_URL);
        handler.post(statePump);
    }

    private final Runnable statePump = new Runnable() {
        @Override public void run() {
            try {
                JSONObject data = new JSONObject();
                MediaController p = controller;
                boolean available = p != null;
                data.put("ready", available);
                data.put("playing", available && p.isPlaying());
                MediaItem item = available ? p.getCurrentMediaItem() : null;
                data.put("id", item == null ? "" : item.mediaId);
                data.put("position", available ? Math.max(0,p.getCurrentPosition()) : 0);
                long duration = available ? p.getDuration() : 0;
                data.put("duration", duration == C.TIME_UNSET ? 0 : Math.max(0,duration));
                data.put("ended", available && p.getPlaybackState() == Player.STATE_ENDED);
                stateJson = data.toString();
            } catch (Exception e) { Log.w("WAVE", "Could not read playback state", e); }
            handler.postDelayed(this, 400);
        }
    };

    private File musicDir() { File f = new File(getFilesDir(), "music"); f.mkdirs(); return f; }
    private File trackFile(String id) { return new File(musicDir(), id + ".mp3"); }
    private static boolean validId(String id) { return id != null && SAFE_ID.matcher(id).matches(); }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQUEST_PICK) {
            if (fileCallback != null) {
                Uri[] files = null;
                if (result == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = Math.min(data.getClipData().getItemCount(), 500);
                        files = new Uri[n];
                        for (int i=0; i<n; i++) files[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) files = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(files);
                fileCallback = null;
            }
        } else if (req == REQUEST_EXPORT) {
            File src = stagedExport; stagedExport = null;
            if (src != null && result == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                new Thread(() -> {
                    try (FileInputStream in = new FileInputStream(src); OutputStream out = getContentResolver().openOutputStream(uri,"w")) {
                        if (out == null) throw new Exception("Speicherort nicht verfügbar");
                        byte[] buf = new byte[64*1024]; int n;
                        while ((n=in.read(buf))>=0) out.write(buf,0,n);
                        runOnUiThread(() -> Toast.makeText(this,"Datei gespeichert",Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this,"Export fehlgeschlagen: "+e.getMessage(),Toast.LENGTH_LONG).show());
                    } finally { src.delete(); }
                }).start();
            } else if (src != null) src.delete();
        }
    }

    @Override public void onBackPressed() {
        // Use WAVE's navigation; leave fullscreen as usual when pressed on the homepage.
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statePump);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        if (web != null) { web.removeJavascriptInterface("WAVEAndroid"); web.destroy(); web = null; }
        synchronized(ioLock) {
            try { if(uploadStream != null) uploadStream.close(); } catch (Exception ignored) {}
            try { if(exportStream != null) exportStream.close(); } catch (Exception ignored) {}
            if (uploadTemp != null) uploadTemp.delete();
            if (exportTemp != null) exportTemp.delete();
        }
        super.onDestroy();
    }

    private void invokePlayer(Runnable action) {
        handler.post(() -> { if (controller != null) action.run(); else queuedPlay = action; });
    }

    private final class Bridge {
        @JavascriptInterface public String state() { return stateJson; }
        @JavascriptInterface public boolean hasAudio(String id) {
            return validId(id) && trackFile(id).isFile() && trackFile(id).length() > 0;
        }

        @JavascriptInterface public boolean beginAudio(String id, long size) {
            if (!validId(id) || size < 1 || size > 120L*1024*1024) return false;
            synchronized (ioLock) {
                try {
                    if (uploadStream != null) return false;
                    uploadTemp = new File(musicDir(), id + ".partial");
                    uploadStream = new FileOutputStream(uploadTemp, false);
                    uploadId = id; uploadExpected = size; uploadReceived = 0;
                    return true;
                } catch (Exception e) { return false; }
            }
        }

        @JavascriptInterface public boolean audioPart(String id, String base64) {
            synchronized (ioLock) {
                if (uploadStream == null || !id.equals(uploadId) || base64.length() > 180_000) return false;
                try {
                    byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
                    if (uploadReceived + bytes.length > uploadExpected) return false;
                    uploadStream.write(bytes);
                    uploadReceived += bytes.length;
                    return true;
                } catch (Exception e) { return false; }
            }
        }
        @JavascriptInterface public boolean endAudio(String id) {
            synchronized (ioLock) {
                if (uploadStream == null || !id.equals(uploadId)) return false;
                try {
                    uploadStream.close(); uploadStream = null;
                    if (uploadReceived != uploadExpected || uploadTemp == null) return false;
                    File finished = trackFile(id);
                    if (!uploadTemp.renameTo(finished)) return false;
                    uploadTemp = null; uploadId = null;
                    return true;
                } catch (Exception e) { return false; }
                finally { if (uploadTemp != null) {uploadTemp.delete();uploadTemp=null;} uploadStream=null; }
            }
        }
        @JavascriptInterface public void abandonAudio(String id) {
            synchronized(ioLock) {
                if (!id.equals(uploadId)) return;
                try { if (uploadStream != null) uploadStream.close(); } catch (Exception ignored) {}
                uploadStream = null;
                if(uploadTemp != null) uploadTemp.delete();
                uploadTemp = null; uploadId = null;
            }
        }
        @JavascriptInterface public void deleteAudio(String id) {
            if(validId(id)) trackFile(id).delete();
        }

        @JavascriptInterface public void play(String id, String metadataJson) {
            if (!validId(id)) return;
            invokePlayer(() -> {
                try {
                    if (controller == null) return;
                    JSONArray input = new JSONArray(metadataJson);
                    List<MediaItem> items = new ArrayList<>();
                    int start = -1;
                    for (int i = 0; i < input.length() && i < 3000; i++) {
                        JSONObject s = input.optJSONObject(i);
                        if (s == null) continue;
                        String sid = s.optString("id", "");
                        if (!validId(sid) || !hasAudio(sid)) continue;
                        File f = trackFile(sid);
                        String title = s.optString("title", "Unbekannt");
                        String artist = s.optString("artist", "Unbekannt");
                        if (title.length()>160 || artist.length()>120) continue;
                        MediaItem item = new MediaItem.Builder().setMediaId(sid).setUri(Uri.fromFile(f))
                          .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).setArtist(artist).build()).build();
                        if (sid.equals(id)) start = items.size();
                        items.add(item);
                    }
                    if (start < 0) return;
                    controller.setMediaItems(items, start, 0);
                    controller.prepare();
                    controller.play();
                } catch (Exception e) { Log.e("WAVE", "Cannot start song", e); }
            });
        }
        @JavascriptInterface public void stop() { invokePlayer(() -> {controller.stop();controller.clearMediaItems();}); }
        @JavascriptInterface public void toggle() { invokePlayer(() -> { if(controller.isPlaying()) controller.pause(); else controller.play(); }); }
        @JavascriptInterface public void next() { invokePlayer(() -> controller.seekToNextMediaItem()); }
        @JavascriptInterface public void previous() { invokePlayer(() -> { if(controller.getCurrentPosition()>3000) controller.seekTo(0); else controller.seekToPreviousMediaItem(); }); }
        @JavascriptInterface public void seek(long ms) { invokePlayer(() -> controller.seekTo(Math.max(0,ms))); }
        @JavascriptInterface public void repeat(String mode) { invokePlayer(() -> controller.setRepeatMode("one".equals(mode)?Player.REPEAT_MODE_ONE:"all".equals(mode)?Player.REPEAT_MODE_ALL:Player.REPEAT_MODE_OFF)); }
        @JavascriptInterface public void shuffle(boolean on) { invokePlayer(() -> controller.setShuffleModeEnabled(on)); }
        @JavascriptInterface public void sound(String json) {
            if (json == null || json.length() > 4000) return;
            // Update native EQ on a connected service, never execute input as code.
            handler.post(() -> { try {
                Intent intent = new Intent(MainActivity.this, PlaybackService.class);
                intent.setAction(PlaybackService.ACTION_SOUND);
                intent.putExtra(PlaybackService.EXTRA_SOUND, json);
                startService(intent);
            } catch (Exception e) { Log.w("WAVE", "EQ not available", e); } });
        }

        @JavascriptInterface public boolean beginExport(String name, long size) {
            if (name == null || !name.matches("[A-Za-z0-9_ äöüÄÖÜß.\\-]{1,85}\\.(waveplaylist|wavepreset)")
                    || size <= 0 || size > 1024L*1024*1024) return false;
            synchronized (ioLock) {
                try {
                    if (exportStream != null) return false;
                    exportTemp = File.createTempFile("wave-share-", ".tmp", getCacheDir());
                    exportStream = new FileOutputStream(exportTemp);
                    exportName = name; exportExpected = size; exportReceived = 0;
                    return true;
                } catch (Exception e) { return false; }
            }
        }
        @JavascriptInterface public boolean exportPart(String base64) {
            synchronized(ioLock) {
                if(exportStream == null || base64.length()>180000) return false;
                try {
                    byte[] bytes = Base64.decode(base64,Base64.NO_WRAP);
                    if(exportReceived + bytes.length > exportExpected) return false;
                    exportStream.write(bytes); exportReceived += bytes.length;
                    return true;
                } catch (Exception e) { return false; }
            }
        }
        @JavascriptInterface public boolean finishExport() {
            synchronized(ioLock) {
                if(exportStream == null) return false;
                try {
                    exportStream.close(); exportStream = null;
                    if(exportReceived != exportExpected) return false;
                    stagedExport = exportTemp; exportTemp = null;
                    final String name = exportName;
                    handler.post(() -> {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/octet-stream");
                        intent.putExtra(Intent.EXTRA_TITLE,name);
                        startActivityForResult(intent,REQUEST_EXPORT);
                    });
                    return true;
                } catch (Exception e) { return false; }
                finally { if (exportTemp != null) { exportTemp.delete(); exportTemp=null; } exportStream=null; }
            }
        }
    }
}
