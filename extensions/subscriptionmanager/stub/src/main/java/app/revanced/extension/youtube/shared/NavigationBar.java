package app.revanced.extension.youtube.shared;

public final class NavigationBar {
    public enum NavigationButton {
        HOME, SHORTS, CREATE, SUBSCRIPTIONS, LIBRARY, NOTIFICATIONS;
        public static NavigationButton getSelectedNavigationButton() { return null; }
    }
    private NavigationBar() { }
}
