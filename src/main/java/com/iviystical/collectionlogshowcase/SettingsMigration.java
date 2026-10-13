package com.iviystical.collectionlogshowcase;

import net.runelite.client.config.ConfigManager;

/** Copy shared preferences once, without changing the upstream plugin's configuration. */
final class SettingsMigration
{
    static final String GROUP = "collection-log-showcase";
    static final String LEGACY = "collection-log-popup-enhanced";
    private static final String[] KEYS = {
        "rarityBasis", "valueUncommonThreshold", "valueRareThreshold", "valueVeryRareThreshold",
        "rightPanelStat", "bulkUnlockSfx", "showProgressBar", "compactShowOwnership",
        "compactShimmer", "compactTextColour", "overlayDisplaySeconds", "overlayScalePercent",
        "backgroundOpacity", "foregroundOpacity", "colourCaption",
        "delayCoxPopupUntilChest", "coxChatCensor"
    };

    static void initializePreview(ConfigManager manager)
    {
        if (manager.getConfiguration(GROUP, "previewInitialized") != null) { return; }
        manager.setConfiguration(GROUP, "previewTier", "NONE");
        manager.setConfiguration(GROUP, "previewLoop", false);
        manager.setConfiguration(GROUP, "previewInitialized", true);
    }

    static void migrate(ConfigManager manager)
    {
        // Also applies to existing Showcase profiles, which already completed the upstream migration.
        if (manager.getConfiguration(GROUP, "showKillCount") == null)
        {
            String previous = manager.getConfiguration(GROUP, "leftPanelStat");
            if (previous == null) { previous = manager.getConfiguration(LEGACY, "leftPanelStat"); }
            if (previous != null) { manager.setConfiguration(GROUP, "showKillCount", "KILL_COUNT".equals(previous)); }
        }
        if (manager.getConfiguration(GROUP, "settingsMigrated") != null) { return; }
        for (String key : KEYS) { copy(manager, key, key); }
        copy(manager, "soundEnabledCommon", "soundEnabled");
        copy(manager, "soundVolumeCommon", "soundVolume");
        if (manager.getConfiguration(GROUP, "panelStyle") == null)
        {
            String previous = manager.getConfiguration(LEGACY, "panelStyle");
            manager.setConfiguration(GROUP, "panelStyle", "AUDIO_ONLY".equals(previous) ? "AUDIO_ONLY" : "COMPACT");
        }
        manager.setConfiguration(GROUP, "settingsMigrated", true);
    }

    private static void copy(ConfigManager manager, String oldKey, String newKey)
    {
        if (manager.getConfiguration(GROUP, newKey) != null) { return; }
        String value = manager.getConfiguration(LEGACY, oldKey);
        if (value != null) { manager.setConfiguration(GROUP, newKey, value); }
    }
}
