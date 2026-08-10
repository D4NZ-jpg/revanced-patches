package app.revanced.extension.shared.settings;

public class IntegerSetting {
    private Integer value;
    public IntegerSetting(String key, Integer defaultValue) { value = defaultValue; }
    public Integer get() { return value; }
    public void save(Integer newValue) { value = newValue; }
}
