package rkr.simplekeyboard.inputmethod.latin.topbar;

public interface TopBarListener {
    void onSettingsClicked();
    void onLanguageClicked();
    void onClipboardClicked();
    void onEmojiClicked();
    void onSuggestionClicked(CharSequence text);
    void onClipboardSuggestionClicked(String fullClipText);
    void onScreenshotSuggestionClicked(String imageUri);
}
