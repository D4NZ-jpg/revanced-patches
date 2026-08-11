package app.revanced.extension.shared;

public final class Logger {
    @FunctionalInterface
    public interface LogMessage {
        String buildMessageString();
    }

    public static void printDebug(LogMessage message) { }

    private Logger() { }
}
