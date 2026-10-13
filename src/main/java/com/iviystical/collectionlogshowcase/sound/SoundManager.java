package com.iviystical.collectionlogshowcase.sound;

import com.iviystical.collectionlogshowcase.CollectionLogShowcaseConfig;
import com.iviystical.collectionlogshowcase.rarity.RarityTier;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import net.runelite.client.audio.AudioPlayer;

/** A single voice and gain for all unlocks. File access and decoding run off the client thread. */
@Slf4j
@Singleton
public class SoundManager
{
    private static final CollectionVoice[] BUNDLED_VOICES = Arrays.stream(CollectionVoice.values())
        .filter(voice -> voice.resource() != null).toArray(CollectionVoice[]::new);
    private int previousRandomIndex = -1;
    @FunctionalInterface
    public interface DirectoryProvider
    {
        Filepath get() throws IOException;
    }

    private final DirectoryProvider soundsDirectory;
    private final AudioPlayer audioPlayer;
    private final CollectionLogShowcaseConfig config;
    private final ScheduledExecutorService executor;

    public SoundManager(AudioPlayer audioPlayer, CollectionLogShowcaseConfig config, ScheduledExecutorService executor, DirectoryProvider soundsDirectory)
    {
        this.audioPlayer = audioPlayer;
        this.config = config;
        this.executor = executor;
        this.soundsDirectory = soundsDirectory;
    }

    public void play(RarityTier tier)
    {
        int volume = config.soundVolume();
        if (!config.soundEnabled() || volume <= 0) { return; }
        CollectionVoice selection = config.collectionVoice();
        CollectionVoice voice = selection == CollectionVoice.RANDOM ? randomVoice() : selection;
        String customFilename = config.customSoundFile();
        executor.execute(() -> {
            try
            {
                float gain = 20f * (float) Math.log10(Math.min(100, volume) / 100f);
                if (voice == CollectionVoice.CUSTOM)
                {
                    if (customFilename == null || !customFilename.toLowerCase(java.util.Locale.ROOT).endsWith(".wav")
                        || customFilename.contains("/") || customFilename.contains("\\") || customFilename.contains(":"))
                    {
                        log.warn("Custom collection log sound must be a WAV filename without a path");
                        return;
                    }
                    // Obtaining the plugin directory can migrate legacy files, so do it here
                    // on the executor, not during injection or on the client thread.
                    Filepath directory = soundsDirectory.get();
                    Filepath sound = directory.joinSegment(customFilename);
                    directory.createDirectories();
                    if (sound.isFile()) { audioPlayer.play(sound, gain); }
                    else { log.warn("Custom collection log sound is missing: {}", sound); }
                }
                else
                {
                    audioPlayer.play(SoundManager.class, voice.resource(), gain);
                }
            }
            catch (Exception e) { log.warn("Failed to play collection log voice {}", voice, e); }
        });
    }

    private synchronized CollectionVoice randomVoice()
    {
        int index = ThreadLocalRandom.current().nextInt(BUNDLED_VOICES.length - (previousRandomIndex < 0 ? 0 : 1));
        if (previousRandomIndex >= 0 && index >= previousRandomIndex) { index++; }
        previousRandomIndex = index;
        return BUNDLED_VOICES[index];
    }
}
