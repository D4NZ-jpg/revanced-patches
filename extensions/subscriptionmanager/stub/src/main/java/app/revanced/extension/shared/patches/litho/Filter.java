package app.revanced.extension.shared.patches.litho;

import app.revanced.extension.shared.patches.litho.FilterGroup.StringFilterGroup;

public abstract class Filter {
    public enum FilterContentType { IDENTIFIER, PATH, ACCESSIBILITY, PROTOBUFFER }
    protected final void addIdentifierCallbacks(StringFilterGroup... groups) { }
    protected final void addPathCallbacks(StringFilterGroup... groups) { }
    public boolean isFiltered(String identifier, String accessibility, String path, byte[] buffer,
                              StringFilterGroup matchedGroup, FilterContentType contentType,
                              int contentIndex) { return true; }
}
