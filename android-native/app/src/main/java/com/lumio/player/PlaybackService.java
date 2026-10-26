package com.lumio.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.ArrayList;
import java.util.List;

public final class PlaybackService extends MediaSessionService {
    private static final String PREFS = "lumio_playback";
    private ExoPlayer player;
    private MediaSession mediaSession;
    private VolumeBoostAudioProcessor volumeBoostProcessor;
    private DefaultAudioSink audioSink;
    private static volatile PlaybackService instance;
    private static volatile VolumeBoostAudioProcessor sharedVolumeBoostProcessor;

    public static void setVolumeBoost(float factor) {
        VolumeBoostAudioProcessor processor = sharedVolumeBoostProcessor;
        if (processor != null) processor.setGain(factor);
    }

    public static void setAudioDelayMillis(int delayMillis) {
        PlaybackService service = instance;
        if (service == null) return;
        int bounded = Math.max(-2000, Math.min(2000, delayMillis));
        new Handler(Looper.getMainLooper()).post(() -> {
            SharedPreferences prefs = service.getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putInt("av_delay_ms", bounded).apply();
            DefaultAudioSink sink = service.audioSink;
            if (sink != null) {
                try {
                    sink.setOutputStreamOffsetUs(bounded * 1000L);
                } catch (Exception ignored) {
                    // The active sink may be reinitializing after a track change.
                }
            }
        });
    }

    public static void setDecoderMode(String mode) {
        String normalized = normalizeDecoderMode(mode);
        PlaybackService service = instance;
        if (service == null) return;
        new Handler(Looper.getMainLooper()).post(() -> service.rebuildPlayer(normalized));
    }

    private static String normalizeDecoderMode(String mode) {
        if ("hardware".equalsIgnoreCase(mode)) return "hardware";
        if ("software".equalsIgnoreCase(mode)) return "software";
        return "auto";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        player = createPlayer(prefs.getString("decoder_mode", "auto"));
        mediaSession = new MediaSession.Builder(this, player).build();
    }

    private ExoPlayer createPlayer(String decoderMode) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        volumeBoostProcessor = new VolumeBoostAudioProcessor();
        volumeBoostProcessor.setGain(prefs.getFloat("volume_boost", 1.0f));
        sharedVolumeBoostProcessor = volumeBoostProcessor;

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                    boolean enableAudioOutputPlaybackParams) {
                DefaultAudioSink sink = new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[]{PlaybackService.this.volumeBoostProcessor})
                        .build();
                long delayUs = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("av_delay_ms", 0) * 1000L;
                sink.setOutputStreamOffsetUs(delayUs);
                audioSink = sink;
                return sink;
            }
        };
        renderersFactory.setEnableDecoderFallback(true);
        renderersFactory.setMediaCodecSelector(createCodecSelector(decoderMode));

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        return new ExoPlayer.Builder(this, renderersFactory)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build();
    }

    private MediaCodecSelector createCodecSelector(String mode) {
        if ("software".equals(mode)) {
            // Prefer software decoders, while retaining a fallback list for devices with no SW codec.
            return MediaCodecSelector.PREFER_SOFTWARE;
        }
        if ("hardware".equals(mode)) {
            return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                List<MediaCodecInfo> codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                List<MediaCodecInfo> ordered = new ArrayList<>(codecs.size());
                for (MediaCodecInfo info : codecs) if (info.hardwareAccelerated) ordered.add(info);
                for (MediaCodecInfo info : codecs) if (!info.hardwareAccelerated) ordered.add(info);
                return ordered;
            };
        }
        // In automatic mode, try software HEVC decoders before vendor codecs. Some
        // devices expose a software decoder that supports Main 10 even when their
        // hardware decoder only advertises 8-bit HEVC. Media3 still checks each
        // decoder's advertised capabilities and can fall back to the remaining list.
        return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            List<MediaCodecInfo> codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (!"video/hevc".equals(mimeType)) return codecs;
            List<MediaCodecInfo> ordered = new ArrayList<>(codecs.size());
            for (MediaCodecInfo info : codecs) if (!info.hardwareAccelerated) ordered.add(info);
            for (MediaCodecInfo info : codecs) if (info.hardwareAccelerated) ordered.add(info);
            android.util.Log.i("LUMIO_CODEC", "HEVC decoder candidates=" + ordered.size()
                    + ", software-first automatic selection");
            return ordered;
        };
    }

    private void rebuildPlayer(String decoderMode) {
        if (player == null || mediaSession == null) return;
        ExoPlayer oldPlayer = player;
        List<MediaItem> queue = new ArrayList<>();
        for (int i = 0; i < oldPlayer.getMediaItemCount(); i++) queue.add(oldPlayer.getMediaItemAt(i));
        int index = oldPlayer.getCurrentMediaItemIndex();
        long position = Math.max(0L, oldPlayer.getCurrentPosition());
        boolean playWhenReady = oldPlayer.getPlayWhenReady();

        ExoPlayer replacement = createPlayer(decoderMode);
        if (!queue.isEmpty()) {
            int targetIndex = Math.max(0, Math.min(index, queue.size() - 1));
            replacement.setMediaItems(queue, targetIndex, position);
            replacement.prepare();
            replacement.setPlayWhenReady(playWhenReady);
        }
        mediaSession.setPlayer(replacement);
        player = replacement;
        oldPlayer.release();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (instance == this) instance = null;
        audioSink = null;
        sharedVolumeBoostProcessor = null;
        volumeBoostProcessor = null;
        super.onDestroy();
    }
}
