package rkr.simplekeyboard.inputmethod.latin.topbar;

public interface TopBarListener {
    void onSettingsClicked();
    void onLanguageClicked();
    void onClipboardTextClicked(CharSequence text);
    void onSuggestionClicked(CharSequence text);
}
