package com.lumio.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public final class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession mediaSession;
    private VolumeBoostAudioProcessor volumeBoostProcessor;
    private static volatile VolumeBoostAudioProcessor sharedVolumeBoostProcessor;

    public static void setVolumeBoost(float factor) {
        VolumeBoostAudioProcessor processor = sharedVolumeBoostProcessor;
        if (processor != null) processor.setGain(factor);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        volumeBoostProcessor = new VolumeBoostAudioProcessor();
        sharedVolumeBoostProcessor = volumeBoostProcessor;

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                    boolean enableAudioOutputPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[]{PlaybackService.this.volumeBoostProcessor})
                        .build();
            }
        };

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player = new ExoPlayer.Builder(this, renderersFactory)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        mediaSession = new MediaSession.Builder(this, player).build();
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
        sharedVolumeBoostProcessor = null;
        volumeBoostProcessor = null;
        super.onDestroy();
    }
}
