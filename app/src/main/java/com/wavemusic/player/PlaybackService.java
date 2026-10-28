package com.wavemusic.player;

import android.content.Intent;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native media session: persists independently of the WAVE WebView/activity. */
public final class PlaybackService extends MediaSessionService {
    public static final String ACTION_SOUND = "com.wavemusic.player.SET_SOUND";
    public static final String EXTRA_SOUND = "profile";
    private MediaSession session;
    private ExoPlayer player;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private JSONObject profile = new JSONObject();
    private int sessionId = 0;

    @Override public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this)
           .setHandleAudioBecomingNoisy(true)
           .setWakeMode(C.WAKE_MODE_LOCAL)
           .setAudioAttributes(new AudioAttributes.Builder()
               .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
           .build();
        player.addListener(new Player.Listener() {
            @Override public void onAudioSessionIdChanged(int audioSessionId) {
                if (audioSessionId != sessionId) {
                    sessionId = audioSessionId;
                    setupEffects();
                }
            }
        });
        session = new MediaSession.Builder(this, player).build();
    }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SOUND.equals(intent.getAction())) {
            try { profile = new JSONObject(intent.getStringExtra(EXTRA_SOUND)); applyEffects(); }
            catch (Exception e) { Log.w("WAVE", "Ignoring invalid sound profile", e); }
            return START_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void releaseEffects() {
        if (equalizer != null) { try { equalizer.release(); } catch (Exception ignored) {} equalizer = null; }
        if (bassBoost != null) { try { bassBoost.release(); } catch (Exception ignored) {} bassBoost = null; }
    }

    private void setupEffects() {
        releaseEffects();
        if (sessionId <= 0) return;
        try { equalizer = new Equalizer(0, sessionId); equalizer.setEnabled(true); }
        catch (Exception e) { Log.w("WAVE", "Hardware equalizer not available"); equalizer = null; }
        try { bassBoost = new BassBoost(0, sessionId); bassBoost.setEnabled(true); }
        catch (Exception e) { Log.w("WAVE", "Hardware bass effect not available"); bassBoost = null; }
        applyEffects();
    }

    private static double clamp(double n, double lo, double hi) {
        return Math.max(lo, Math.min(hi, n));
    }

    private void applyEffects() {
        // Many Android devices offer fewer EQ bands and less gain than the web audio engine.
        // Clip requests to the hardware-supported range. Reduce volume as bass increases.
        double bass = clamp(profile.optDouble("bass", 0), 0, 48);
        double speed = clamp(profile.optDouble("speed", 1), .5, 2.5);
        double vocal = clamp(profile.optDouble("vocal", 0), -18, 18);
        double presence = clamp(profile.optDouble("presence", 0), -18, 18);
        double treble = clamp(profile.optDouble("treble", 0), -18, 18);
        try { player.setPlaybackSpeed((float) speed); } catch (Exception ignored) {}
        if (bassBoost != null) {
            try { bassBoost.setStrength((short)Math.round(clamp(bass / 48 * 1000, 0, 1000))); }
            catch (Exception e) { Log.w("WAVE", "BassBoost rejected strength", e); }
        }
        if (equalizer != null) {
            try {
                JSONArray db = profile.optJSONArray("eq");
                double[] bands = {31,62,125,250,500,1000,2000,4000,8000,16000};
                short[] range = equalizer.getBandLevelRange();
                for (short b = 0; b < equalizer.getNumberOfBands(); b++) {
                    double freq = equalizer.getCenterFreq(b) / 1000.0;
                    int closest = 0; double error = Double.MAX_VALUE;
                    for (int j = 0; j < bands.length; j++) {
                        double delta = Math.abs(Math.log(Math.max(freq,1) / bands[j]));
                        if (delta < error) { closest=j; error=delta; }
                    }
                    double gain = db == null ? 0 : clamp(db.optDouble(closest, 0), -18, 18);
                    if (freq < 170) gain += bass * .36;
                    if (freq >= 500 && freq < 2200) gain += vocal * .6;
                    if (freq >= 2200 && freq < 6000) gain += presence * .6;
                    if (freq >= 6000) gain += treble * .6;
                    short level = (short)Math.round(clamp(gain*100,range[0],range[1]));
                    equalizer.setBandLevel(b, level);
                }
            } catch (Exception e) { Log.w("WAVE", "EQ not fully supported", e); }
        }
        // This is attenuation, not a true peak limiter. Avoid promising clipping protection.
        float outputFactor = (float)(clamp(1.0 - (bass / 48.0 * .42), .58, 1) * clamp(profile.optDouble("volume", .55), 0, 1));
        try { player.setVolume(outputFactor); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        releaseEffects();
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
