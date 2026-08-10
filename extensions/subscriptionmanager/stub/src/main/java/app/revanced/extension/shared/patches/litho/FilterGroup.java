package app.revanced.extension.shared.patches.litho;

import app.revanced.extension.shared.settings.BooleanSetting;

public abstract class FilterGroup<T> {
    public static class StringFilterGroup extends FilterGroup<String> {
        public StringFilterGroup(BooleanSetting setting, String... filters) { }
    }
}
