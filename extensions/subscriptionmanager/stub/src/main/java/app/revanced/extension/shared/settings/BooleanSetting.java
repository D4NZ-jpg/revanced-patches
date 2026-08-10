package app.revanced.extension.shared.settings;

public class BooleanSetting {
    private Boolean value;
    public BooleanSetting(String key, Boolean defaultValue) { value = defaultValue; }
    public Boolean get() { return value; }
    public void save(Boolean newValue) { value = newValue; }
}
