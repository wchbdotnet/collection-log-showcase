package com.iviystical.collectionlogshowcase;

import com.iviystical.collectionlogshowcase.cox.CoxChatCensorMode;
import com.iviystical.collectionlogshowcase.overlay.PanelStyle;
import com.iviystical.collectionlogshowcase.sound.CollectionVoice;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SettingsMigrationTest
{
    @Test
    public void firstActivationDisablesPreviewAndLoopWithoutImportingLegacyPreview()
    {
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfiguration(SettingsMigration.LEGACY, "previewTier")).thenReturn("RANDOM");
        SettingsMigration.migrate(manager);
        verify(manager, never()).setConfiguration(SettingsMigration.GROUP, "previewTier", "RANDOM");
        SettingsMigration.initializePreview(manager);
        verify(manager).setConfiguration(SettingsMigration.GROUP, "previewTier", "NONE");
        verify(manager).setConfiguration(SettingsMigration.GROUP, "previewLoop", false);
        verify(manager).setConfiguration(SettingsMigration.GROUP, "previewInitialized", true);
    }

    @Test
    public void laterActivationsPreserveExplicitPreviewChoices()
    {
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfiguration(SettingsMigration.GROUP, "previewInitialized")).thenReturn("true");
        SettingsMigration.initializePreview(manager);
        verify(manager, never()).setConfiguration(anyString(), anyString(), any());
    }

    @Test
    public void legacyStylesAndAudioMigrateWithoutWritingUpstream()
    {
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfiguration(SettingsMigration.LEGACY, "panelStyle")).thenReturn("NEUTRAL");
        when(manager.getConfiguration(SettingsMigration.LEGACY, "soundVolumeCommon")).thenReturn("42");
        when(manager.getConfiguration(SettingsMigration.LEGACY, "soundEnabledCommon")).thenReturn("false");
        when(manager.getConfiguration(SettingsMigration.LEGACY, "delayCoxPopupUntilChest")).thenReturn("true");
        SettingsMigration.migrate(manager);
        verify(manager).setConfiguration(SettingsMigration.GROUP, "panelStyle", "COMPACT");
        verify(manager).setConfiguration(SettingsMigration.GROUP, "soundVolume", "42");
        verify(manager).setConfiguration(SettingsMigration.GROUP, "soundEnabled", "false");
        verify(manager).setConfiguration(SettingsMigration.GROUP, "delayCoxPopupUntilChest", "true");
        verify(manager, never()).setConfiguration(eq(SettingsMigration.LEGACY), anyString(), any());
    }

    @Test
    public void migrationDoesNotOverwriteShowcaseSettingsAndOnlyRunsOnce()
    {
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfiguration(SettingsMigration.GROUP, "soundVolume")).thenReturn("80");
        when(manager.getConfiguration(SettingsMigration.LEGACY, "panelStyle")).thenReturn("AUDIO_ONLY");
        SettingsMigration.migrate(manager);
        verify(manager, never()).setConfiguration(eq(SettingsMigration.GROUP), eq("soundVolume"), any());
        verify(manager).setConfiguration(SettingsMigration.GROUP, "panelStyle", "AUDIO_ONLY");
        clearInvocations(manager);
        when(manager.getConfiguration(SettingsMigration.GROUP, "settingsMigrated")).thenReturn("true");
        SettingsMigration.migrate(manager);
        verify(manager, never()).setConfiguration(anyString(), anyString(), any());
    }

    @Test
    public void standaloneDefaultsKeepCoxOptInAndUseCEngineer()
    {
        CollectionLogShowcaseConfig config = new CollectionLogShowcaseConfig() {};
        assertEquals(2, PanelStyle.values().length);
        assertEquals(PanelStyle.COMPACT, config.panelStyle());
        assertEquals(CollectionVoice.C_ENGINEER, config.collectionVoice());
        assertTrue(config.soundEnabled());
        assertEquals(65, config.soundVolume());
        assertFalse(config.delayCoxPopupUntilChest());
        assertEquals(CoxChatCensorMode.OFF, config.coxChatCensor());
    }
}
