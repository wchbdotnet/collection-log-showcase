package com.iviystical.collectionlogshowcase.sound;

public enum CollectionVoice
{
    C_ENGINEER("C Engineer", "c-engineer.wav"),
    BOATY_1("B0aty - variant 1", "boaty-1.wav"),
    BOATY_2("B0aty - variant 2", "boaty-2.wav"),
    TEDIOUS("Tedious", "tedious.wav"),
    ODABLOCK("Odablock", "odablock.wav"),
    RANDOM("Random", null),
    CUSTOM("Custom WAV", null);

    private final String label;
    private final String filename;
    CollectionVoice(String label, String filename) { this.label = label; this.filename = filename; }
    public String resource() { return filename == null ? null : "/sounds/voices/" + filename; }
    @Override public String toString() { return label; }
}
