package rkr.simplekeyboard.inputmethod.latin.topbar;

public interface TopBarListener {
    void onSettingsClicked();
    void onLanguageClicked();
    void onClipboardClicked();
    void onSuggestionClicked(CharSequence text);
}
