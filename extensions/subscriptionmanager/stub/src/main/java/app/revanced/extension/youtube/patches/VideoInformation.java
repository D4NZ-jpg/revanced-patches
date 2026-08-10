package app.revanced.extension.youtube.patches;

public final class VideoInformation {
    public static boolean lastVideoIdIsShort() { return false; }
    public static long getVideoLength() { return 0; }
    public static String getVideoId() { return null; }
    private VideoInformation() { }
}
