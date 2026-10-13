package com.iviystical.collectionlogshowcase.sound;

import com.iviystical.collectionlogshowcase.CollectionLogShowcaseConfig;
import com.iviystical.collectionlogshowcase.rarity.RarityTier;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.concurrent.ScheduledExecutorService;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import net.runelite.client.audio.AudioPlayer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class VoiceAudioTest
{
    @Test
    public void everyBundledVoiceIsDecodable() throws Exception
    {
        for (CollectionVoice voice : CollectionVoice.values())
        {
            if (voice == CollectionVoice.CUSTOM) { continue; }
            try (InputStream input = getClass().getResourceAsStream(voice.resource()))
            {
                assertNotNull(voice.toString(), input);
                try (AudioInputStream audio = AudioSystem.getAudioInputStream(new BufferedInputStream(input)))
                {
                    assertTrue(audio.getFrameLength() > 0);
                    assertTrue(audio.getFormat().getSampleRate() > 0);
                }
            }
        }
    }

    @Test
    public void allTiersUseOneVoiceAndMutePreventsScheduling() throws Exception
    {
        CollectionLogShowcaseConfig config = mock(CollectionLogShowcaseConfig.class);
        AudioPlayer player = mock(AudioPlayer.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(config.soundEnabled()).thenReturn(true);
        when(config.soundVolume()).thenReturn(65);
        when(config.collectionVoice()).thenReturn(CollectionVoice.C_ENGINEER);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));
        SoundManager manager = new SoundManager(player, config, executor, () -> { throw new AssertionError("Bundled audio must not access the filesystem"); });
        for (RarityTier tier : RarityTier.values()) { manager.play(tier); }
        verify(player, times(RarityTier.values().length)).play(eq(SoundManager.class), eq(CollectionVoice.C_ENGINEER.resource()), anyFloat());
        clearInvocations(player, executor);
        when(config.soundEnabled()).thenReturn(false);
        manager.play(RarityTier.PET);
        verifyNoInteractions(player, executor);
        when(config.soundEnabled()).thenReturn(true);
        when(config.soundVolume()).thenReturn(0);
        manager.play(RarityTier.COMMON);
        verifyNoInteractions(player, executor);
    }
    @Test
    public void customAudioUsesFilepathOnlyOnTheExecutor() throws Exception
    {
        CollectionLogShowcaseConfig config = customConfig("custom.wav");
        AudioPlayer player = mock(AudioPlayer.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        SoundManager.DirectoryProvider directoryProvider = mock(SoundManager.DirectoryProvider.class);
        Filepath directory = mock(Filepath.class);
        Filepath sound = mock(Filepath.class);
        when(directoryProvider.get()).thenReturn(directory);
        when(directory.joinSegment("custom.wav")).thenReturn(sound);
        when(sound.isFile()).thenReturn(true);
        SoundManager manager = new SoundManager(player, config, executor, directoryProvider);
        manager.play(RarityTier.RARE);
        verifyNoInteractions(directoryProvider, directory, sound, player);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(directoryProvider).get();
        verify(directory).joinSegment("custom.wav");
        verify(directory).createDirectories();
        verify(player).play(eq(sound), anyFloat());
    }

    @Test
    public void invalidCustomNamesNeverAccessTheFilesystem() throws Exception
    {
        for (String name : new String[] {null, "../other.wav", "..\\other.wav", "C:other.wav", "custom.ogg"})
        {
            CollectionLogShowcaseConfig config = customConfig(name);
            AudioPlayer player = mock(AudioPlayer.class);
            ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
            SoundManager.DirectoryProvider directory = mock(SoundManager.DirectoryProvider.class);
            doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(executor).execute(any(Runnable.class));
            new SoundManager(player, config, executor, directory).play(RarityTier.COMMON);
            verifyNoInteractions(directory, player);
        }
    }

    @Test
    public void missingCustomSoundDoesNotFallBackToBundledAudio() throws Exception
    {
        AudioPlayer player = mock(AudioPlayer.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        Filepath directory = mock(Filepath.class);
        Filepath sound = mock(Filepath.class);
        when(directory.joinSegment("missing.wav")).thenReturn(sound);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
            .when(executor).execute(any(Runnable.class));
        new SoundManager(player, customConfig("missing.wav"), executor, () -> directory).play(RarityTier.PET);
        verify(directory).createDirectories();
        verify(sound).isFile();
        verifyNoInteractions(player);
    }

    private CollectionLogShowcaseConfig customConfig(String filename)
    {
        CollectionLogShowcaseConfig config = mock(CollectionLogShowcaseConfig.class);
        when(config.soundEnabled()).thenReturn(true);
        when(config.soundVolume()).thenReturn(65);
        when(config.collectionVoice()).thenReturn(CollectionVoice.CUSTOM);
        when(config.customSoundFile()).thenReturn(filename);
        return config;
    }

}
