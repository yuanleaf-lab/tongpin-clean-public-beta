package com.linjian.tongpin;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.service.notification.NotificationListenerService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.linjian.tongpin.data.PlaybackSnapshot;
import com.linjian.tongpin.data.Prefs;
import com.linjian.tongpin.data.RoomApi;
import com.linjian.tongpin.data.RoomCredentials;
import com.linjian.tongpin.lyrics.QQMusicLyricsAccessibilityService;
import com.linjian.tongpin.media.PlayerCatalog;
import com.linjian.tongpin.media.TongpinNotificationListener;
import com.linjian.tongpin.sync.TongpinForegroundService;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String VERSION = "1.3.1-public";
    private static final int REQUEST_IMPORT_LYRICS_FILE = 1208;
    private static final int REQUEST_PICK_BACKGROUND = 1216;
    private static final String UI_PREFS = "tongpin_ui_space";
    private static final String KEY_BACKGROUND_URI = "background_uri";
    private static final String KEY_GLASS_TRANSPARENCY = "glass_transparency";
    private static final int DEFAULT_GLASS_TRANSPARENCY = 18;
    private static final int TAB_PLAY = 0;
    private static final int TAB_NOTES = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int TAB_CHAT = 3;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Palette palette;
    private boolean secretVisible;
    private int activeTab = TAB_PLAY;
    private String pendingLyricsImportTrackKey = "";


    private EditText serverInput;
    private EditText sourceInput;
    private TextView statusPill;
    private TextView syncText;
    private TextView songTitle;
    private TextView songArtist;
    private TextView songAlbum;
    private TextView lyricText;
    private TextView nextLyricText;
    private TextView timeText;
    private TextView listeningDurationText;
    private TextView roomCodeText;
    private TextView roomSecretText;
    private TextView diagnosticsText;
    private TextView notesHintText;
    private TextView notesFilterChip;
    private TextView chatSongTitle;
    private TextView chatSongArtist;
    private TextView chatLyricText;
    private TextView chatMessagesText;
    private EditText chatInput;
    private LinearLayout notesList;
    private TextView artworkFallback;
    private ImageView artworkView;
    private ProgressBar progressBar;
    private Button createButton;
    private Button refreshButton;
    private Button playPauseButton;
    private Button backgroundSyncButton;
    private Button lyricsReaderButton;
    private Button ocrLyricsButton;
    private Button autoPlayPermissionButton;
    private Button navPlayButton;
    private Button navNotesButton;
    private Button navSettingsButton;
    private Button navChatButton;
    private Button clearManualLyricsButton;
    private TextView manualLyricsHintText;
    private LinearLayout playerSection;
    private LinearLayout notesSection;
    private LinearLayout settingsSection;
    private LinearLayout chatSection;
    private boolean notesCurrentOnly = true;
    private final StringBuilder chatTranscript = new StringBuilder();

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            uiHandler.postDelayed(this, 750L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleShareIntent(getIntent());
        palette = Palette.fromKey(Prefs.theme(this));
        applySystemBars();
        setContentView(buildScreen());
        requestNotificationPermissionIfNeeded();
        uiHandler.post(refreshRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.postDelayed(() -> {
            ensureBackgroundSyncIfReady();
            if (!TongpinNotificationListener.requestImmediateRefresh()) {
                NotificationListenerService.requestRebind(
                        new ComponentName(this, TongpinNotificationListener.class)
                );
            }
        }, 220L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
        if (sourceInput != null) sourceInput.setText(Prefs.sourceUrl(this));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_BACKGROUND) {
            handleBackgroundPick(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_IMPORT_LYRICS_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String trackKey = pendingLyricsImportTrackKey;
        pendingLyricsImportTrackKey = "";
        if (trackKey == null || trackKey.isEmpty()) trackKey = Prefs.playback(this).trackKey();
        try {
            String raw = readTextFromUri(data.getData());
            saveManualLyrics(trackKey, raw);
        } catch (Throwable error) {
            toast("导入失败：" + safeMessage(error));
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.surface);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        }
        int flags = window.getDecorView().getSystemUiVisibility();
        if (palette.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (palette.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private View buildScreen() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(appBackground());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(buildHeader());

        playerSection = new LinearLayout(this);
        playerSection.setOrientation(LinearLayout.VERTICAL);
        playerSection.addView(buildSongCard());
        root.addView(playerSection, matchWrap());

        notesSection = new LinearLayout(this);
        notesSection.setOrientation(LinearLayout.VERTICAL);
        notesSection.addView(buildNotesCard());
        root.addView(notesSection, matchWrap());

        chatSection = new LinearLayout(this);
        chatSection.setOrientation(LinearLayout.VERTICAL);
        chatSection.addView(buildChatCard());
        root.addView(chatSection, matchWrap());

        settingsSection = new LinearLayout(this);
        settingsSection.setOrientation(LinearLayout.VERTICAL);
        settingsSection.addView(buildRoomCard());
        settingsSection.addView(buildSetupCard());
        root.addView(settingsSection, matchWrap());

        TextView footer = text("同频Clean · 正在播放，也正在被听见。", 12f, palette.secondary, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(4), dp(8), 0);
        root.addView(footer, matchWrap());

        outer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        LinearLayout bottomNav = buildBottomNav();
        outer.addView(bottomNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        applyEdgeToEdgeInsets(outer, bottomNav);
        updateActiveTab();
        return outer;
    }

    private void applyEdgeToEdgeInsets(LinearLayout content, LinearLayout bottomNav) {
        final int contentTopPadding = content.getPaddingTop();
        final int navLeftPadding = bottomNav.getPaddingLeft();
        final int navTopPadding = bottomNav.getPaddingTop();
        final int navRightPadding = bottomNav.getPaddingRight();
        final int navBottomPadding = bottomNav.getPaddingBottom();

        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = topSystemInset(insets);
            int bottomInset = bottomSystemInset(insets);
            view.setPadding(
                    view.getPaddingLeft(),
                    contentTopPadding + topInset,
                    view.getPaddingRight(),
                    view.getPaddingBottom()
            );
            bottomNav.setPadding(
                    navLeftPadding,
                    navTopPadding,
                    navRightPadding,
                    navBottomPadding + bottomInset
            );
            return insets;
        });
        content.requestApplyInsets();
    }

    private int topSystemInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            return bars.top;
        }
        return insets.getSystemWindowInsetTop();
    }

    private int bottomSystemInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(12), dp(7), dp(12), dp(9));
        nav.setBackground(navGlassBackground());
        navPlayButton = navButton("播放", () -> switchTab(TAB_PLAY));
        navNotesButton = navButton("笔记", () -> switchTab(TAB_NOTES));
        navChatButton = navButton("陪听", () -> switchTab(TAB_CHAT));
        navSettingsButton = navButton("设置", () -> switchTab(TAB_SETTINGS));
        nav.addView(navPlayButton);
        nav.addView(navNotesButton);
        nav.addView(navChatButton);
        nav.addView(navSettingsButton);
        return nav;
    }

    private Button navButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.setMarginStart(dp(4));
        params.setMarginEnd(dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private void switchTab(int tab) {
        activeTab = tab;
        updateActiveTab();
        refreshUi();
    }

    private void updateActiveTab() {
        setVisible(playerSection, activeTab == TAB_PLAY);
        setVisible(notesSection, activeTab == TAB_NOTES);
        setVisible(chatSection, activeTab == TAB_CHAT);
        setVisible(settingsSection, activeTab == TAB_SETTINGS);
        updateNavButton(navPlayButton, activeTab == TAB_PLAY);
        updateNavButton(navNotesButton, activeTab == TAB_NOTES);
        updateNavButton(navChatButton, activeTab == TAB_CHAT);
        updateNavButton(navSettingsButton, activeTab == TAB_SETTINGS);
    }

    private void setVisible(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateNavButton(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? palette.onAccent : palette.accent);
        button.setBackground(rounded(
                selected ? translucent(palette.accent, 212) : translucent(palette.surface, glassAlpha(116)),
                selected ? translucent(Color.WHITE, 86) : translucent(palette.accent, 42),
                22
        ));
        button.setElevation(selected ? dp(2) : 0);
    }

    private View buildHeader() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(2), dp(2), dp(2), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("同频", 30f, palette.text, true);
        TextView subtitle = text("一起听见此刻", 13f, palette.secondary, false);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(title);
        titles.addView(subtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView themeButton = smallChip("主题 · " + palette.name, palette.accentSoft, palette.accent);
        themeButton.setOnClickListener(v -> showThemePicker());
        top.addView(themeButton);
        wrap.addView(top);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(14), 0, 0);

        statusPill = smallChip("正在检查连接", palette.accentSoft, palette.accent);
        statusRow.addView(statusPill);
        syncText = text("尚未同步", 13f, palette.secondary, false);
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        syncParams.setMarginStart(dp(10));
        statusRow.addView(syncText, syncParams);
        wrap.addView(statusRow);
        wrap.setLayoutParams(matchWrap());
        return wrap;
    }

    private View buildSongCard() {
        LinearLayout card = playbackCard();

        LinearLayout mediaRow = new LinearLayout(this);
        mediaRow.setOrientation(LinearLayout.HORIZONTAL);
        mediaRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout artwork = new FrameLayout(this);
        artwork.setPadding(dp(5), dp(5), dp(5), dp(5));
        artwork.setBackground(artworkGlassBackground());
        LinearLayout.LayoutParams artworkParams = new LinearLayout.LayoutParams(dp(84), dp(84));
        artworkParams.setMarginEnd(dp(14));
        mediaRow.addView(artwork, artworkParams);

        artworkView = new ImageView(this);
        artworkView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkView.setBackground(rounded(Color.TRANSPARENT, Color.TRANSPARENT, 18));
        artworkView.setClipToOutline(true);
        artwork.addView(artworkView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        artworkFallback = text("♫", 40f, palette.accent, true);
        artworkFallback.setGravity(Gravity.CENTER);
        artwork.addView(artworkFallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        songTitle = text("等待播放器", 22f, palette.text, true);
        songTitle.setMaxLines(2);
        songArtist = text("请先在 QQ 音乐播放一首歌", 14f, palette.secondary, false);
        songArtist.setPadding(0, dp(5), 0, 0);
        songAlbum = text("", 13f, palette.secondary, false);
        songAlbum.setPadding(0, dp(3), 0, 0);
        info.addView(songTitle);
        info.addView(songArtist);
        info.addView(songAlbum);
        mediaRow.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(mediaRow);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgressTintList(ColorStateList.valueOf(palette.accent));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(palette.progressTrack));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(5)
        );
        progressParams.topMargin = dp(14);
        card.addView(progressBar, progressParams);

        timeText = text("0:00 / 0:00", 12f, palette.secondary, false);
        timeText.setGravity(Gravity.END);
        timeText.setPadding(0, dp(6), 0, 0);
        card.addView(timeText, matchWrap());

        listeningDurationText = text("一起听了 0 分钟", 12f, palette.secondary, false);
        listeningDurationText.setGravity(Gravity.END);
        listeningDurationText.setPadding(0, dp(2), 0, 0);
        card.addView(listeningDurationText, matchWrap());

        LinearLayout lyricBox = new LinearLayout(this);
        lyricBox.setOrientation(LinearLayout.VERTICAL);
        lyricBox.setPadding(dp(15), dp(13), dp(15), dp(12));
        lyricBox.setBackground(rounded(
                translucent(blend(palette.surface, palette.surfaceAlt, 0.36f), glassAlpha(palette.dark ? 188 : 204)),
                translucent(blend(Color.WHITE, palette.accent, 0.08f), Math.max(58, glassAlpha(126))),
                20
        ));
        LinearLayout.LayoutParams lyricParams = matchWrap();
        lyricParams.topMargin = dp(13);
        card.addView(lyricBox, lyricParams);

        TextView lyricLabel = text("正在唱", 12f, palette.secondary, true);
        lyricBox.addView(lyricLabel);
        lyricText = text("等待同步歌词", 20f, palette.text, true);
        lyricText.setGravity(Gravity.CENTER);
        lyricText.setMinHeight(dp(42));
        lyricText.setMaxLines(2);
        lyricText.setPadding(0, dp(9), 0, dp(6));
        lyricBox.addView(lyricText, matchWrap());
        nextLyricText = text("", 13f, palette.secondary, false);
        nextLyricText.setGravity(Gravity.CENTER);
        nextLyricText.setMaxLines(2);
        lyricBox.addView(nextLyricText, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(14), 0, 0);
        controls.addView(roundControl("‹‹", () -> sendLocalControl("previous"), false));
        playPauseButton = roundControl("▶", () -> {
            PlaybackSnapshot playback = Prefs.playback(this);
            sendLocalControl(playback.playing ? "pause" : "play");
        }, true);
        controls.addView(playPauseButton);
        controls.addView(roundControl("››", () -> sendLocalControl("next"), false));
        card.addView(controls, matchWrap());

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setPadding(0, dp(11), 0, 0);
        refreshButton = actionButton("刷新状态", this::requestImmediateRefresh, false);
        Button retryLyrics = actionButton("重试歌词", () -> {
            if (TongpinNotificationListener.requestLyricsRetry()) {
                toast("正在重新匹配歌词");
            } else {
                requestServiceRebind();
                toast("媒体服务正在重新连接");
            }
        }, false);
        quick.addView(refreshButton);
        quick.addView(retryLyrics);
        quick.addView(actionButton("导入歌词", this::showImportLyricsDialog, false));
        card.addView(quick, matchWrap());
        return card;
    }

    private View buildNotesCard() {
        LinearLayout card = card();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = sectionTitle("听歌笔记");
        title.setPadding(0, 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        notesFilterChip = smallChip("只看当前歌曲", palette.accentSoft, palette.accent);
        notesFilterChip.setOnClickListener(v -> {
            notesCurrentOnly = !notesCurrentOnly;
            refreshUi();
        });
        header.addView(notesFilterChip);
        card.addView(header, matchWrap());

        notesHintText = text("AI / 网页遥控器写进来的听歌笔记会显示在这里。点当前歌曲的笔记，可以跳回对应进度；长按可以复制。", 12f, palette.secondary, false);
        notesHintText.setPadding(0, dp(9), 0, dp(10));
        card.addView(notesHintText, matchWrap());

        notesList = new LinearLayout(this);
        notesList.setOrientation(LinearLayout.VERTICAL);
        card.addView(notesList, matchWrap());
        return card;
    }

    private View buildChatCard() {
        LinearLayout card = playbackCard();
        card.addView(sectionTitle("共听"));

        LinearLayout contextBox = new LinearLayout(this);
        contextBox.setOrientation(LinearLayout.VERTICAL);
        contextBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        contextBox.setBackground(rounded(
                translucent(blend(palette.surface, palette.surfaceAlt, 0.30f), glassAlpha(palette.dark ? 156 : 172)),
                translucent(blend(Color.WHITE, palette.accent, 0.10f), Math.max(52, glassAlpha(104))),
                18
        ));
        card.addView(contextBox, matchWrap());

        TextView contextLabel = text("当前音乐", 12f, palette.secondary, true);
        contextBox.addView(contextLabel);
        chatSongTitle = text("等待播放器", 20f, palette.text, true);
        chatSongTitle.setPadding(0, dp(8), 0, 0);
        chatSongTitle.setMaxLines(2);
        contextBox.addView(chatSongTitle, matchWrap());
        chatSongArtist = text("播放歌曲后，这里会显示歌手信息", 13f, palette.secondary, false);
        chatSongArtist.setPadding(0, dp(4), 0, 0);
        chatSongArtist.setMaxLines(2);
        contextBox.addView(chatSongArtist, matchWrap());
        chatLyricText = text("当前歌词会显示在这里", 15f, palette.text, true);
        chatLyricText.setPadding(0, dp(12), 0, 0);
        chatLyricText.setMaxLines(3);
        contextBox.addView(chatLyricText, matchWrap());

        TextView chatLabel = text("共听对话", 12f, palette.secondary, true);
        chatLabel.setPadding(0, dp(16), 0, dp(8));
        card.addView(chatLabel, matchWrap());

        LinearLayout messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        messagesBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        messagesBox.setMinimumHeight(dp(148));
        messagesBox.setBackground(rounded(
                translucent(blend(palette.surface, palette.background, 0.22f), glassAlpha(palette.dark ? 118 : 132)),
                translucent(blend(Color.WHITE, palette.accent, 0.08f), Math.max(44, glassAlpha(88))),
                18
        ));
        chatMessagesText = text("一起听歌，一起聊歌。\n这里会记录你和音乐相遇的瞬间。", 13f, palette.secondary, false);
        chatMessagesText.setLineSpacing(dp(2), 1.18f);
        messagesBox.addView(chatMessagesText, matchWrap());
        card.addView(messagesBox, matchWrap());

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(12), 0, 0);
        chatInput = field("", "想聊聊这首歌...");
        chatInput.setSingleLine(false);
        chatInput.setMinLines(1);
        chatInput.setMaxLines(3);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputParams.setMarginEnd(dp(8));
        inputRow.addView(chatInput, inputParams);
        Button sendButton = actionButton("发送", this::sendChatMessage, true);
        inputRow.addView(sendButton, new LinearLayout.LayoutParams(dp(76), dp(44)));
        card.addView(inputRow, matchWrap());
        return card;
    }

    private View buildRoomCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("房间"));

        roomCodeText = text("尚未创建房间", 23f, palette.text, true);
        roomCodeText.setLetterSpacing(0.12f);
        card.addView(roomCodeText, matchWrap());
        roomSecretText = text("", 13f, palette.secondary, false);
        roomSecretText.setPadding(0, dp(7), 0, dp(4));
        card.addView(roomSecretText, matchWrap());

        LinearLayout roomActions = new LinearLayout(this);
        roomActions.setOrientation(LinearLayout.HORIZONTAL);
        roomActions.setPadding(0, dp(10), 0, 0);
        createButton = actionButton("创建新房间", this::createRoom, true);
        Button copyButton = actionButton("复制信息", this::copyRoom, false);
        roomActions.addView(createButton);
        roomActions.addView(copyButton);
        card.addView(roomActions, matchWrap());

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        secondaryActions.setPadding(0, dp(8), 0, 0);
        secondaryActions.addView(actionButton("显示 / 隐藏密钥", () -> {
            secretVisible = !secretVisible;
            refreshUi();
        }, false));
        secondaryActions.addView(actionButton("清除房间", this::confirmClearRoom, false));
        card.addView(secondaryActions, matchWrap());
        return card;
    }

    private View buildSetupCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("设置"));

        LinearLayout serverBox = new LinearLayout(this);
        serverBox.setOrientation(LinearLayout.VERTICAL);
        serverBox.addView(fieldLabel("服务器地址"));
        serverInput = field(Prefs.server(this), "https://your-service.onrender.com");
        serverBox.addView(serverInput, matchWrap());
        LinearLayout serverActions = new LinearLayout(this);
        serverActions.setOrientation(LinearLayout.HORIZONTAL);
        serverActions.setPadding(0, dp(8), 0, 0);
        serverActions.addView(actionButton("保存检测", this::saveAndTestServer, true));
        serverActions.addView(actionButton("遥控器", () -> {
            saveServerFromInput();
            openUrl(Prefs.server(this) + "/control");
        }, false));
        serverBox.addView(serverActions, matchWrap());
        card.addView(drawer("连接与服务器", "地址", true, serverBox));

        LinearLayout spaceBox = new LinearLayout(this);
        spaceBox.setOrientation(LinearLayout.VERTICAL);
        TextView spaceHint = text(backgroundUri().isEmpty()
                ? "使用低饱和主题背景，也可以从相册选择一张图片做成磨砂音乐空间。"
                : "已使用自定义背景；播放页会自动模糊和暗化，保证歌词与控制按钮清晰。", 12f, palette.secondary, false);
        spaceBox.addView(spaceHint, matchWrap());
        LinearLayout spaceActions = new LinearLayout(this);
        spaceActions.setOrientation(LinearLayout.HORIZONTAL);
        spaceActions.setPadding(0, dp(8), 0, 0);
        spaceActions.addView(actionButton("选择背景", this::pickSpaceBackground, true));
        spaceActions.addView(actionButton("恢复默认", this::clearSpaceBackground, false));
        spaceBox.addView(spaceActions, matchWrap());
        TextView glassLabel = text("玻璃透明度 · " + glassTransparency() + "%", 12f, palette.secondary, true);
        glassLabel.setPadding(0, dp(12), 0, dp(4));
        spaceBox.addView(glassLabel, matchWrap());
        SeekBar glassSlider = new SeekBar(this);
        glassSlider.setMax(45);
        glassSlider.setProgress(glassTransparency());
        glassSlider.setProgressTintList(ColorStateList.valueOf(palette.accent));
        glassSlider.setThumbTintList(ColorStateList.valueOf(palette.accent));
        glassSlider.setProgressBackgroundTintList(ColorStateList.valueOf(translucent(palette.accentSoft, 180)));
        glassSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                glassLabel.setText("玻璃透明度 · " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                saveGlassTransparency(seekBar.getProgress());
                recreate();
            }
        });
        spaceBox.addView(glassSlider, matchWrap());
        card.addView(drawer("音乐空间", backgroundUri().isEmpty() ? "默认背景" : "自定义背景", true, spaceBox));

        LinearLayout lanBox = new LinearLayout(this);
        lanBox.setOrientation(LinearLayout.VERTICAL);
        TextView lanIntro = text("局域网模式适合在同一 Wi-Fi 内用电脑直接跑服务端。", 12f, palette.secondary, false);
        lanBox.addView(lanIntro, matchWrap());
        LinearLayout lanActions = new LinearLayout(this);
        lanActions.setOrientation(LinearLayout.HORIZONTAL);
        lanActions.setPadding(0, dp(8), 0, 0);
        lanActions.addView(actionButton("局域网部署", this::showLanGuide, false));
        lanActions.addView(actionButton("填入局域网示例", () -> {
            serverInput.setText("http://192.168.1.100:3000");
            toast("把 192.168.1.100 改成电脑的局域网 IP");
        }, false));
        lanBox.addView(lanActions, matchWrap());
        TextView lanHint = text("服务器地址可填写 http://电脑IP:3000；手机和电脑需要连同一个 Wi-Fi。", 12f, palette.secondary, false);
        lanHint.setPadding(0, dp(6), 0, 0);
        lanBox.addView(lanHint, matchWrap());
        card.addView(drawer("局域网部署", "本地测试", false, lanBox));

        LinearLayout permissionBox = new LinearLayout(this);
        permissionBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout permissionActions = new LinearLayout(this);
        permissionActions.setOrientation(LinearLayout.HORIZONTAL);
        permissionActions.addView(actionButton("通知使用权", () -> {
            saveServerFromInput();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }, true));
        permissionActions.addView(actionButton("重新绑定", () -> {
            requestServiceRebind();
            toast("已请求重新绑定");
        }, false));
        permissionBox.addView(permissionActions, matchWrap());
        LinearLayout backgroundActions = new LinearLayout(this);
        backgroundActions.setOrientation(LinearLayout.HORIZONTAL);
        backgroundActions.setPadding(0, dp(8), 0, 0);
        backgroundSyncButton = actionButton("后台待命", this::toggleBackgroundSync, true);
        backgroundActions.addView(backgroundSyncButton);
        backgroundActions.addView(actionButton("后台设置", () -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }, false));
        permissionBox.addView(backgroundActions, matchWrap());
        TextView backgroundHint = text("开启后会显示常驻通知；切到 QQ 音乐、酷狗音乐、网易云音乐或锁屏后，房间仍会继续领取指令。", 12f, palette.secondary, false);
        backgroundHint.setPadding(0, dp(6), 0, 0);
        permissionBox.addView(backgroundHint, matchWrap());
        card.addView(drawer("手机权限", "通知 / 后台", false, permissionBox));

        LinearLayout lyricsBox = new LinearLayout(this);
        lyricsBox.setOrientation(LinearLayout.VERTICAL);
        manualLyricsHintText = text("优先级：手动导入 > LRCLIB > 播放器界面 > OCR > 未找到。", 12f, palette.secondary, false);
        manualLyricsHintText.setPadding(0, 0, 0, dp(8));
        lyricsBox.addView(manualLyricsHintText, matchWrap());
        LinearLayout lyricsActions = new LinearLayout(this);
        lyricsActions.setOrientation(LinearLayout.HORIZONTAL);
        lyricsReaderButton = actionButton("播放器歌词读取", this::toggleQqLyricsReading, true);
        ocrLyricsButton = actionButton("OCR兜底", this::toggleOcrLyrics, false);
        lyricsActions.addView(lyricsReaderButton);
        lyricsActions.addView(ocrLyricsButton);
        lyricsBox.addView(lyricsActions, matchWrap());
        LinearLayout manualActions = new LinearLayout(this);
        manualActions.setOrientation(LinearLayout.HORIZONTAL);
        manualActions.setPadding(0, dp(8), 0, 0);
        manualActions.addView(actionButton("导入歌词", this::showImportLyricsDialog, true));
        clearManualLyricsButton = actionButton("清除导入", this::confirmClearManualLyrics, false);
        manualActions.addView(clearManualLyricsButton);
        lyricsBox.addView(manualActions, matchWrap());
        TextView lyricsHint = text("导入 .lrc 可按时间轴同步；导入普通文本会固定显示。手动歌词只保存在本机。QQ 音乐、酷狗音乐、网易云音乐都可尝试界面歌词读取与 OCR。", 12f, palette.secondary, false);
        lyricsHint.setPadding(0, dp(6), 0, 0);
        lyricsBox.addView(lyricsHint, matchWrap());
        card.addView(drawer("歌词增强", "导入优先", true, lyricsBox));

        LinearLayout automationBox = new LinearLayout(this);
        automationBox.setOrientation(LinearLayout.VERTICAL);
        autoPlayPermissionButton = actionButton("自动点歌权限", this::openAutoPlayPermission, true);
        automationBox.addView(autoPlayPermissionButton, matchWrap());
        TextView automationHint = text("授权后，AI 发送歌名时会先尝试系统媒体搜索；若 QQ 音乐不响应，再短暂打开 QQ 音乐自动输入、匹配和点击。酷狗和网易云目前先支持基础控制与歌词兜底。", 12f, palette.secondary, false);
        automationHint.setPadding(0, dp(6), 0, 0);
        automationBox.addView(automationHint, matchWrap());
        card.addView(drawer("自动点歌", "可选", false, automationBox));

        LinearLayout sourceBox = new LinearLayout(this);
        sourceBox.setOrientation(LinearLayout.VERTICAL);
        sourceBox.addView(fieldLabel("QQ 音乐分享链接（可选）"));
        sourceInput = field(Prefs.sourceUrl(this), "从 QQ 音乐分享后粘贴到这里");
        sourceBox.addView(sourceInput, matchWrap());
        LinearLayout sourceActions = new LinearLayout(this);
        sourceActions.setOrientation(LinearLayout.HORIZONTAL);
        sourceActions.setPadding(0, dp(8), 0, 0);
        sourceActions.addView(actionButton("绑定当前歌曲", () -> {
            Prefs.saveSourceUrl(this, sourceInput.getText().toString());
            Prefs.bindSourceToCurrentTrack(this);
            toast("链接已绑定当前歌曲");
        }, true));
        sourceActions.addView(actionButton("打开 QQ 音乐", () -> {
            Prefs.saveSourceUrl(this, sourceInput.getText().toString());
            Prefs.bindSourceToCurrentTrack(this);
            String url = sourceInput.getText().toString().trim();
            if (!url.isEmpty()) openUrl(url); else openPackage("com.tencent.qqmusic");
        }, false));
        sourceBox.addView(sourceActions, matchWrap());
        card.addView(drawer("QQ 音乐分享链接", "可选", false, sourceBox));

        LinearLayout diagnosticsBox = new LinearLayout(this);
        diagnosticsBox.setOrientation(LinearLayout.VERTICAL);
        diagnosticsText = text("正在读取状态…", 12f, palette.secondary, false);
        diagnosticsText.setLineSpacing(dp(2), 1.05f);
        diagnosticsBox.addView(diagnosticsText, matchWrap());
        card.addView(drawer("诊断信息", "状态", false, diagnosticsBox));
        return card;
    }

    private View buildDiagnosticsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("诊断信息"));
        diagnosticsText = text("正在读取状态…", 13f, palette.secondary, false);
        diagnosticsText.setLineSpacing(dp(2), 1.05f);
        card.addView(diagnosticsText, matchWrap());
        return card;
    }

    private View drawer(String title, String status, boolean expanded, View... children) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(11), dp(12), dp(11));
        box.setBackground(rounded(palette.surfaceAlt, palette.border, 18));
        LinearLayout.LayoutParams boxParams = matchWrap();
        boxParams.bottomMargin = dp(10);
        box.setLayoutParams(boxParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = text(title, 14f, palette.text, true);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (status != null && !status.trim().isEmpty()) {
            header.addView(smallChip(status, palette.accentSoft, palette.accent));
        }
        box.addView(header, matchWrap());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(11), 0, 0);
        for (View child : children) {
            if (child != null) content.addView(child, matchWrap());
        }
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        box.addView(content, matchWrap());
        header.setOnClickListener(v -> content.setVisibility(content.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        return box;
    }

    private void createRoom() {
        if (createButton == null) return;
        createButton.setEnabled(false);
        createButton.setText("创建中…");
        saveServerFromInput();
        RoomApi.createRoom(this, Prefs.server(this), new RoomApi.Callback<>() {
            @Override
            public void onSuccess(RoomCredentials room) {
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    createButton.setText("创建新房间");
                    toast("房间创建成功");
                    TongpinForegroundService.start(MainActivity.this);
                    requestServiceRebind();
                    refreshUi();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    createButton.setText("创建新房间");
                    toast("创建失败：" + safeMessage(error));
                });
            }
        });
    }

    private void saveAndTestServer() {
        saveServerFromInput();
        toast("正在检测服务器");
        RoomApi.health(Prefs.server(this), new RoomApi.Callback<>() {
            @Override
            public void onSuccess(String value) {
                runOnUiThread(() -> toast("服务器正常 · " + value));
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> toast("检测失败：" + safeMessage(error)));
            }
        });
    }

    private void saveServerFromInput() {
        if (serverInput == null) return;
        Prefs.saveServer(this, serverInput.getText().toString());
        serverInput.setText(Prefs.server(this));
    }

    private void sendChatMessage() {
        if (chatInput == null || chatMessagesText == null) return;
        String message = chatInput.getText().toString().trim();
        if (message.isEmpty()) {
            toast("先写一句想聊的内容");
            return;
        }
        RoomCredentials room = Prefs.room(this);
        if (room.code.isEmpty() || room.secret.isEmpty()) {
            toast("请先创建房间");
            return;
        }

        appendChatLine("你", message);
        chatInput.setText("");
        renderChatTranscript("共听：正在听这首歌…");

        RoomApi.sendChat(this, message, new RoomApi.Callback<>() {
            @Override
            public void onSuccess(String reply) {
                runOnUiThread(() -> {
                    appendChatLine("共听", reply == null || reply.trim().isEmpty() ? "我听到了，但暂时没有组织好回复。" : reply.trim());
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    String message = safeMessage(error);
                    if (message.contains("AI_NOT_CONFIGURED")) {
                        appendChatLine("共听", "服务端还没有配置共听 AI。");
                    } else {
                        appendChatLine("共听", "网络失败：" + message);
                    }
                });
            }
        });
    }

    private void appendChatLine(String speaker, String value) {
        if (chatTranscript.length() > 0) chatTranscript.append("\n\n");
        chatTranscript.append(speaker).append("：").append(value);
        renderChatTranscript(null);
    }

    private void renderChatTranscript(String pendingLine) {
        if (chatMessagesText == null) return;
        String text = chatTranscript.length() == 0
                ? "一起听歌，一起聊歌。\n这里会记录你和音乐相遇的瞬间。"
                : chatTranscript.toString();
        if (pendingLine != null && !pendingLine.isEmpty()) {
            text += "\n\n" + pendingLine;
        }
        chatMessagesText.setText(text);
    }

    private void refreshUi() {
        if (statusPill == null) return;
        RoomCredentials room = Prefs.room(this);
        PlaybackSnapshot playback = Prefs.playback(this);
        long lastSync = Prefs.lastSync(this);
        long lastPublish = Prefs.lastPlaybackPublish(this);
        long listeningDurationMs = Prefs.listeningDurationMs(this);
        boolean permission = isNotificationAccessEnabled();

        boolean connected = permission && !room.code.isEmpty() && lastSync > 0L
                && System.currentTimeMillis() - lastSync < 12_000L;
        statusPill.setText(connected ? "● 正在同频" : permission ? "○ 等待播放器" : "○ 需要通知权限");
        statusPill.setTextColor(connected ? palette.success : palette.accent);
        statusPill.setBackground(rounded(connected ? palette.successSoft : palette.accentSoft, Color.TRANSPARENT, 99));
        syncText.setText(lastPublish == 0L ? "尚未上传播放状态" : "状态更新于 " + relativeTime(lastPublish));
        if (backgroundSyncButton != null) {
            boolean backgroundEnabled = Prefs.backgroundSyncEnabled(this);
            backgroundSyncButton.setText(backgroundEnabled ? "后台待命 · 已开启" : "开启后台待命");
        }
        if (lyricsReaderButton != null) {
            boolean enabled = Prefs.qqLyricsEnabled(this);
            boolean authorized = isLyricsAccessibilityEnabled();
            lyricsReaderButton.setText(!enabled
                    ? "开启歌词读取"
                    : authorized ? "歌词读取 · 已开启" : "歌词读取 · 待授权");
        }
        if (ocrLyricsButton != null) {
            ocrLyricsButton.setText(Prefs.ocrLyricsEnabled(this) ? "OCR 兜底 · 已开启" : "开启 OCR 兜底");
            ocrLyricsButton.setEnabled(Build.VERSION.SDK_INT >= 30);
        }
        if (autoPlayPermissionButton != null) {
            autoPlayPermissionButton.setText(isLyricsAccessibilityEnabled()
                    ? "自动点歌 · 已授权"
                    : "开启自动点歌权限");
        }

        roomCodeText.setText(room.code.isEmpty() ? "尚未创建房间" : room.code);
        if (room.code.isEmpty()) {
            roomSecretText.setText("创建房间后，才能与 ChatGPT 建立专属连接。");
        } else if (secretVisible) {
            roomSecretText.setText("房间密钥  " + room.secret + "\n请只发给可信对象。");
        } else {
            roomSecretText.setText("房间密钥  " + maskSecret(room.secret) + "  ·  已隐藏");
        }

        long position = liveDisplayPosition(playback);
        songTitle.setText(playback.title);
        songArtist.setText(playback.artist);
        String meta = playbackMeta(playback);
        songAlbum.setText(meta);
        songAlbum.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        timeText.setText(formatTime(position) + " / " + formatTime(playback.durationMs));
        listeningDurationText.setText(formatListeningDuration(listeningDurationMs));
        int progress = playback.durationMs > 0L
                ? (int) Math.min(1000L, Math.round(position * 1000.0 / playback.durationMs))
                : 0;
        progressBar.setProgress(progress);
        playPauseButton.setText(playback.playing ? "Ⅱ" : "▶");
        boolean hasManualLyrics = Prefs.hasManualLyrics(this, playback.trackKey());
        if (manualLyricsHintText != null) {
            manualLyricsHintText.setText(
                    "优先级：手动导入 > LRCLIB > 播放器界面 > OCR > 未找到。"
                            + (hasManualLyrics
                            ? System.lineSeparator() + "当前歌曲已保存手动歌词，会优先使用。"
                            : System.lineSeparator() + "当前歌曲还没有手动歌词。")
            );
        }
        if (clearManualLyricsButton != null) clearManualLyricsButton.setEnabled(hasManualLyrics);

        boolean hasLyricText = !clean(playback.lyric).isEmpty() || !clean(playback.nextLyric).isEmpty();
        if (playback.lyricsSynced || hasLyricText) {
            lyricText.setText(clean(playback.lyric).isEmpty() ? "♪ 前奏 / 间奏" : playback.lyric);
            nextLyricText.setText(clean(playback.nextLyric).isEmpty() ? "" : "下一句 · " + playback.nextLyric);
        } else {
            String source = playback.lyricsSource.isEmpty() ? "等待同步歌词" : playback.lyricsSource;
            lyricText.setText(source);
            nextLyricText.setText(source.contains("加载") ? "正在为当前歌曲匹配时间轴歌词" : "可点击“重试歌词”再次匹配");
        }
        if (chatSongTitle != null) {
            chatSongTitle.setText(playback.title);
            chatSongArtist.setText(playback.artist.isEmpty() ? "等待歌手信息" : playback.artist);
            if (playback.lyricsSynced || hasLyricText) {
                chatLyricText.setText(clean(playback.lyric).isEmpty() ? "♪ 前奏 / 间奏" : playback.lyric);
            } else {
                chatLyricText.setText(playback.lyricsSource.isEmpty() ? "等待同步歌词" : playback.lyricsSource);
            }
        }

        Bitmap artwork = TongpinNotificationListener.currentArtwork();
        if (artwork != null) {
            artworkView.setImageBitmap(artwork);
            artworkView.setVisibility(View.VISIBLE);
            artworkFallback.setVisibility(View.GONE);
        } else {
            artworkView.setImageDrawable(null);
            artworkView.setVisibility(View.GONE);
            artworkFallback.setVisibility(View.VISIBLE);
        }

        updateNotes(playback);

        diagnosticsText.setText(
                "版本  " + VERSION
                        + "\n同步状态  " + Prefs.status(this)
                        + "\n服务器  " + Prefs.server(this)
                        + "\n最近轮询  " + (lastSync == 0L ? "尚无" : formatClock(lastSync))
                        + "\n最近上报  " + (lastPublish == 0L ? "尚无" : formatClock(lastPublish))
                        + "\n最近命令  " + Prefs.lastCommandResult(this)
                        + "\n后台待命  " + (Prefs.backgroundSyncEnabled(this) ? "已开启" : "已关闭")
                        + "\n歌词读取  " + (Prefs.qqLyricsEnabled(this)
                                ? isLyricsAccessibilityEnabled() ? "已授权" : "待系统授权"
                                : "已关闭")
                        + "\nOCR 兜底  " + (Prefs.ocrLyricsEnabled(this) ? "已开启" : "已关闭")
                        + "\n手动歌词  " + (hasManualLyrics ? "当前歌曲已保存" : "当前歌曲未导入")
                        + "\n歌词优先级  手动导入 > LRCLIB > 播放器界面 > OCR > 未找到"
                        + "\n媒体来源  " + PlayerCatalog.displayName(playback.packageName) + (playback.packageName.isEmpty() ? "" : " · " + playback.packageName)
                        + "\n歌词来源  " + (playback.lyricsSource.isEmpty() ? "尚无" : playback.lyricsSource)
        );
    }

    private void sendLocalControl(String type) {
        sendLocalControl(type, null);
    }

    private void sendLocalControl(String type, Long positionMs) {
        if (!TongpinNotificationListener.requestLocalCommand(type, positionMs)) {
            requestServiceRebind();
            toast("媒体服务正在重新连接");
            return;
        }
        toast("已发送" + commandLabel(type) + "，正在等待播放器确认");
    }

    private void updateNotes(PlaybackSnapshot playback) {
        if (notesList == null || notesHintText == null || notesFilterChip == null) return;
        notesList.removeAllViews();
        notesFilterChip.setText(notesCurrentOnly ? "显示全部笔记" : "只看当前歌曲");

        JSONArray notes = Prefs.roomNotes(this);
        String currentTitle = playback == null ? "" : clean(playback.title);
        int shown = 0;
        for (int i = notes.length() - 1; i >= 0 && shown < 20; i--) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            String trackTitle = clean(note.optString("trackTitle", "未知歌曲"));
            boolean sameTrack = !currentTitle.isEmpty() && currentTitle.equals(trackTitle);
            if (notesCurrentOnly && !sameTrack) continue;
            notesList.addView(noteRow(note, sameTrack));
            shown += 1;
        }

        if (shown == 0) {
            notesHintText.setText(notesCurrentOnly
                    ? "当前歌曲还没有听歌笔记。AI 写入后会自动出现在这里。"
                    : "暂时还没有听歌笔记。AI / 网页遥控器写进来的句子会显示在这里。"
            );
        } else {
            notesHintText.setText(notesCurrentOnly
                    ? "正在只看当前歌曲的笔记。点笔记可跳到对应进度，长按复制。"
                    : "最近听歌笔记 · 点当前歌曲的笔记可跳到对应进度，长按复制。"
            );
        }
    }

    private View noteRow(JSONObject note, boolean sameTrack) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(13), dp(11), dp(13), dp(11));
        row.setBackground(rounded(sameTrack ? palette.accentSoft : palette.surfaceAlt, palette.border, 16));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);

        long position = Math.max(0L, note.optLong("positionMs", 0L));
        String trackTitle = note.optString("trackTitle", "未知歌曲").trim();
        String textValue = note.optString("text", "").trim();
        long createdAt = note.optLong("createdAt", 0L);

        String meta = formatTime(position) + " · " + (trackTitle.isEmpty() ? "未知歌曲" : "《" + trackTitle + "》");
        if (createdAt > 0L) meta += " · " + relativeTime(createdAt);
        TextView metaView = text(meta, 12f, sameTrack ? palette.accent : palette.secondary, true);
        row.addView(metaView, matchWrap());

        TextView body = text(textValue.isEmpty() ? "（空笔记）" : textValue, 14f, palette.text, false);
        body.setPadding(0, dp(6), 0, 0);
        row.addView(body, matchWrap());

        row.setOnClickListener(v -> {
            if (sameTrack && position > 0L) {
                sendLocalControl("seek", position);
            } else if (sameTrack) {
                toast("这条笔记没有可跳转的进度");
            } else {
                toast("这条笔记属于《" + (trackTitle.isEmpty() ? "未知歌曲" : trackTitle) + "》");
            }
        });
        row.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("同频听歌笔记", textValue));
            toast("笔记已复制");
            return true;
        });
        return row;
    }

    private void requestImmediateRefresh() {
        if (refreshButton == null) return;
        long baseline = Prefs.lastPlaybackPublish(this);
        refreshButton.setEnabled(false);
        refreshButton.setText("同步中…");
        boolean accepted = TongpinNotificationListener.requestImmediateRefresh();
        if (!accepted) {
            requestServiceRebind();
            toast("媒体服务正在重新连接");
            uiHandler.postDelayed(this::resetRefreshButton, 1200L);
            return;
        }
        waitForRefreshResult(baseline, 0);
    }

    private void waitForRefreshResult(long baseline, int attempt) {
        refreshUi();
        if (Prefs.lastPlaybackPublish(this) > baseline) {
            refreshButton.setText("已同步");
            uiHandler.postDelayed(this::resetRefreshButton, 900L);
            return;
        }
        if (attempt >= 28) {
            refreshButton.setText("暂未响应");
            toast("播放器暂时没有返回新状态");
            uiHandler.postDelayed(this::resetRefreshButton, 1200L);
            return;
        }
        uiHandler.postDelayed(() -> waitForRefreshResult(baseline, attempt + 1), 250L);
    }

    private void resetRefreshButton() {
        refreshUi();
        if (refreshButton != null) {
            refreshButton.setEnabled(true);
            refreshButton.setText("刷新状态");
        }
    }


    private void showLanGuide() {
        new AlertDialog.Builder(this)
                .setTitle("局域网部署")
                .setMessage("1. 电脑安装 Node.js 22+。\n"
                        + "2. 在电脑进入 services/server，执行 npm ci、npm run build、npm start。\n"
                        + "3. 查看电脑局域网 IP，例如 192.168.1.100。\n"
                        + "4. 手机和电脑连接同一个 Wi-Fi。\n"
                        + "5. App 服务器地址填写 http://电脑IP:3000。\n\n"
                        + "也可以打开 http://电脑IP:3000/lan 查看自检说明。离开同一个 Wi-Fi 后，请改回 Render 地址。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showThemePicker() {
        Palette[] values = Palette.values();
        String[] names = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name;
            if (values[i].key.equals(palette.key)) checked = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择主题")
                .setSingleChoiceItems(names, checked, null)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int selected = dialog.getListView().getCheckedItemPosition();
            if (selected >= 0 && selected < values.length) {
                Prefs.saveTheme(this, values[selected].key);
                dialog.dismiss();
                recreate();
            }
        }));
        dialog.show();
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher matcher = Pattern.compile("https?://\\S+").matcher(text);
        if (matcher.find()) {
            String url = matcher.group();
            while (url.endsWith("。") || url.endsWith("，") || url.endsWith(",") || url.endsWith(")")) {
                url = url.substring(0, url.length() - 1);
            }
            Prefs.saveSourceUrl(this, url);
            Prefs.bindSourceToCurrentTrack(this);
        }
    }

    private void toggleQqLyricsReading() {
        boolean enabled = !Prefs.qqLyricsEnabled(this);
        Prefs.saveQqLyricsEnabled(this, enabled);
        if (!enabled) {
            Prefs.saveOcrLyricsEnabled(this, false);
            Prefs.clearLiveLyrics(this);
            toast("播放器歌词读取已关闭");
            refreshUi();
            return;
        }
        toast("请在系统页面开启“同频歌词与自动点歌”");
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        refreshUi();
    }

    private void openAutoPlayPermission() {
        toast("请在系统页面开启“同频歌词与自动点歌”");
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void toggleOcrLyrics() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("当前 Android 版本不支持本地屏幕识别");
            return;
        }
        if (!Prefs.qqLyricsEnabled(this)) {
            Prefs.saveQqLyricsEnabled(this, true);
            Prefs.saveOcrLyricsEnabled(this, true);
            toast("请先在系统页面开启“同频歌词与自动点歌”");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            refreshUi();
            return;
        }
        boolean enabled = !Prefs.ocrLyricsEnabled(this);
        Prefs.saveOcrLyricsEnabled(this, enabled);
        toast(enabled ? "本地 OCR 兜底已开启" : "本地 OCR 兜底已关闭");
        refreshUi();
    }

    private void showImportLyricsDialog() {
        PlaybackSnapshot playback = Prefs.playback(this);
        String trackKey = playback.trackKey();
        if (playback.title.trim().isEmpty() || "等待播放器".equals(playback.title)) {
            toast("请先播放一首歌，再导入歌词");
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        layout.setPadding(pad, pad, pad, 0);
        TextView target = text("当前绑定：《" + playback.title + "》 · " + playback.artist, 13f, palette.secondary, false);
        target.setPadding(0, 0, 0, dp(8));
        layout.addView(target, matchWrap());
        EditText input = new EditText(this);
        input.setHint("粘贴 .lrc 时间轴歌词，或普通歌词文本");
        input.setMinLines(8);
        input.setMaxLines(14);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextSize(13f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(false);
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.secondary);
        input.setBackground(rounded(palette.surfaceAlt, palette.border, 14));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setText(Prefs.manualLyrics(this, trackKey));
        layout.addView(input, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导入当前歌曲歌词")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setNeutralButton("选择文件", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String raw = input.getText().toString();
                if (raw.trim().isEmpty()) {
                    toast("歌词内容为空");
                    return;
                }
                saveManualLyrics(trackKey, raw);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                pendingLyricsImportTrackKey = trackKey;
                openLyricsFilePicker();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void openLyricsFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/octet-stream", "application/lrc"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT_LYRICS_FILE);
        } catch (Throwable error) {
            toast("无法打开文件选择器：" + safeMessage(error));
        }
    }

    private String readTextFromUri(Uri uri) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStream input = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private void saveManualLyrics(String trackKey, String raw) {
        if (trackKey == null || trackKey.isEmpty()) {
            toast("没有可绑定的当前歌曲");
            return;
        }
        if (raw == null || raw.trim().isEmpty()) {
            toast("歌词内容为空");
            return;
        }
        Prefs.saveManualLyrics(this, trackKey, raw);
        if (!TongpinNotificationListener.requestImmediateRefresh()) requestServiceRebind();
        toast("已保存手动歌词，以后播放这首会优先使用");
        refreshUi();
    }

    private void confirmClearManualLyrics() {
        PlaybackSnapshot playback = Prefs.playback(this);
        String trackKey = playback.trackKey();
        if (!Prefs.hasManualLyrics(this, trackKey)) {
            toast("当前歌曲没有手动歌词");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清除当前歌曲导入歌词？")
                .setMessage("只会删除本机为《" + playback.title + "》保存的手动歌词。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    Prefs.clearManualLyrics(this, trackKey);
                    if (!TongpinNotificationListener.requestImmediateRefresh()) requestServiceRebind();
                    toast("已清除当前歌曲手动歌词");
                    refreshUi();
                })
                .show();
    }

    private boolean isLyricsAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null || enabled.isEmpty()) return false;
        ComponentName component = new ComponentName(this, QQMusicLyricsAccessibilityService.class);
        String full = component.flattenToString();
        String shortName = component.flattenToShortString();
        return enabled.contains(full) || enabled.contains(shortName);
    }

    private void toggleBackgroundSync() {
        if (Prefs.backgroundSyncEnabled(this)) {
            TongpinForegroundService.stop(this);
            Prefs.saveStatus(this, "后台待命已关闭");
            toast("后台待命已关闭");
        } else {
            RoomCredentials room = Prefs.room(this);
            if (room.code.isEmpty()) {
                toast("请先创建房间");
                return;
            }
            if (!isNotificationAccessEnabled()) {
                toast("请先开启通知使用权");
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                return;
            }
            TongpinForegroundService.start(this);
            requestServiceRebind();
            toast("后台待命已开启");
        }
        refreshUi();
    }

    private void ensureBackgroundSyncIfReady() {
        RoomCredentials room = Prefs.room(this);
        if (!Prefs.backgroundSyncEnabled(this) || room.code.isEmpty() || !isNotificationAccessEnabled()) return;
        TongpinForegroundService.start(this);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 524);
    }

    private void requestServiceRebind() {
        NotificationListenerService.requestRebind(
                new ComponentName(this, TongpinNotificationListener.class)
        );
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void copyRoom() {
        RoomCredentials room = Prefs.room(this);
        if (room.code.isEmpty()) {
            toast("还没有房间");
            return;
        }
        String value = "房间码:" + room.code + "\n房间密钥:" + room.secret;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("同频房间", value));
        toast("房间信息已复制");
    }

    private void confirmClearRoom() {
        new AlertDialog.Builder(this)
                .setTitle("清除当前房间？")
                .setMessage("只会清除手机本地保存的房间信息。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    Prefs.clearRoom(this);
                    TongpinForegroundService.stop(this);
                    Prefs.saveStatus(this, "房间已清除");
                    refreshUi();
                })
                .show();
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            toast("链接为空");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable error) {
            toast("无法打开：" + safeMessage(error));
        }
    }

    private void openPackage(String name) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(name);
        if (launch != null) startActivity(launch); else toast("没有找到 QQ 音乐");
    }

    private void pickSpaceBackground() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_BACKGROUND);
        } catch (Throwable error) {
            toast("无法打开相册：" + safeMessage(error));
        }
    }

    private void handleBackgroundPick(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Throwable ignored) {
            // Some pickers grant only temporary access; the URI is still useful for the current session.
        }
        saveBackgroundUri(uri.toString());
        toast("音乐空间背景已更新");
        recreate();
    }

    private void clearSpaceBackground() {
        saveBackgroundUri("");
        toast("已恢复默认背景");
        recreate();
    }

    private String backgroundUri() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_BACKGROUND_URI, "");
    }

    private void saveBackgroundUri(String value) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_BACKGROUND_URI, value == null ? "" : value)
                .apply();
    }

    private int glassTransparency() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getInt(KEY_GLASS_TRANSPARENCY, DEFAULT_GLASS_TRANSPARENCY);
    }

    private void saveGlassTransparency(int value) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_GLASS_TRANSPARENCY, Math.max(0, Math.min(45, value)))
                .apply();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.setBackground(rounded(palette.surface, palette.border, 22));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout playbackCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(15));
        card.setBackground(glassCardBackground());
        card.setElevation(dp(3));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 16f, palette.text, true);
        title.setPadding(0, 0, 0, dp(13));
        return title;
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 12f, palette.secondary, true);
        label.setPadding(0, 0, 0, dp(7));
        return label;
    }

    private EditText field(String value, String hint) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(13f);
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.secondary);
        input.setPadding(dp(13), dp(11), dp(13), dp(11));
        input.setBackground(rounded(palette.surfaceAlt, palette.border, 14));
        return input;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.18f);
        if (bold) view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        return view;
    }

    private TextView smallChip(String label, int background, int foreground) {
        TextView chip = text(label, 11f, foreground, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        chip.setBackground(rounded(translucent(background, palette.dark ? 218 : 236), Color.TRANSPARENT, 99));
        return chip;
    }

    private Button actionButton(String label, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setTextColor(primary ? palette.onAccent : palette.accent);
        button.setBackground(rounded(
                primary ? palette.accent : translucent(blend(palette.surface, palette.accentSoft, 0.22f), glassAlpha(palette.dark ? 112 : 128)),
                primary ? translucent(Color.WHITE, 58) : translucent(blend(Color.WHITE, palette.accent, 0.10f), Math.max(42, glassAlpha(92))),
                18
        ));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setElevation(primary ? dp(1) : 0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.setMarginStart(dp(3));
        params.setMarginEnd(dp(3));
        button.setLayoutParams(params);
        return button;
    }

    private Button roundControl(String label, Runnable action, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(primary ? 20f : 17f);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setTextColor(primary ? palette.onAccent : palette.accent);
        button.setBackground(primary
                ? ovalGradient(
                blend(palette.accent, Color.WHITE, palette.dark ? 0.04f : 0.22f),
                blend(palette.accent, Color.BLACK, palette.dark ? 0.18f : 0.04f)
        )
                : oval(translucent(blend(palette.surface, palette.accentSoft, 0.10f), glassAlpha(palette.dark ? 88 : 102)), translucent(blend(Color.WHITE, palette.accent, 0.16f), Math.max(52, glassAlpha(96)))));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setElevation(primary ? dp(4) : dp(1));
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(v -> action.run());
        int size = dp(primary ? 54 : 44);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginStart(dp(10));
        params.setMarginEnd(dp(10));
        button.setLayoutParams(params);
        return button;
    }

    private Drawable appBackground() {
        Drawable image = customBackgroundDrawable();
        if (image != null) return image;
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        palette.background,
                        blend(palette.background, palette.accentSoft, palette.dark ? 0.12f : 0.20f),
                        blend(palette.surfaceAlt, palette.background, 0.46f)
                }
        );
        base.setShape(GradientDrawable.RECTANGLE);

        GradientDrawable glow = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        translucent(blend(palette.accentSoft, palette.background, 0.35f), palette.dark ? 44 : 112),
                        Color.TRANSPARENT
                }
        );
        glow.setShape(GradientDrawable.RECTANGLE);

        GradientDrawable depth = new GradientDrawable(
                GradientDrawable.Orientation.BR_TL,
                new int[]{
                        translucent(blend(palette.surfaceAlt, palette.accent, 0.10f), palette.dark ? 38 : 82),
                        Color.TRANSPARENT
                }
        );
        depth.setShape(GradientDrawable.RECTANGLE);

        return new LayerDrawable(new Drawable[]{base, glow, depth});
    }

    private Drawable customBackgroundDrawable() {
        String value = backgroundUri();
        if (value.isEmpty()) return null;
        try (InputStream input = getContentResolver().openInputStream(Uri.parse(value))) {
            Bitmap decoded = BitmapFactory.decodeStream(input);
            if (decoded == null) return null;
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, dp(96), dp(170), true);
            if (decoded != scaled) decoded.recycle();
            Bitmap blurred = boxBlur(scaled, 3);
            BitmapDrawable image = new BitmapDrawable(getResources(), blurred);
            image.setGravity(Gravity.FILL);
            image.setDither(true);

            GradientDrawable shade = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{
                            translucent(palette.background, palette.dark ? 172 : 118),
                            translucent(blend(palette.background, Color.BLACK, palette.dark ? 0.42f : 0.16f), palette.dark ? 210 : 154)
                    }
            );
            shade.setShape(GradientDrawable.RECTANGLE);

            GradientDrawable tint = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{
                            translucent(palette.accentSoft, palette.dark ? 46 : 86),
                            Color.TRANSPARENT
                    }
            );
            tint.setShape(GradientDrawable.RECTANGLE);
            return new LayerDrawable(new Drawable[]{image, shade, tint});
        } catch (Throwable error) {
            return null;
        }
    }

    private Drawable glassCardBackground() {
        GradientDrawable glass = roundedGradient(
                translucent(blend(palette.surface, Color.WHITE, palette.dark ? 0.04f : 0.22f), glassAlpha(palette.dark ? 150 : 162)),
                translucent(blend(palette.surfaceAlt, palette.background, 0.42f), glassAlpha(palette.dark ? 118 : 132)),
                translucent(blend(Color.WHITE, palette.accent, palette.dark ? 0.10f : 0.08f), Math.max(62, glassAlpha(palette.dark ? 86 : 148))),
                28
        );
        GradientDrawable rim = roundedGradient(
                translucent(blend(Color.WHITE, palette.accent, palette.dark ? 0.08f : 0.12f), Math.max(72, glassAlpha(palette.dark ? 84 : 132))),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                28
        );
        GradientDrawable floor = roundedGradient(
                Color.TRANSPARENT,
                translucent(blend(palette.accentSoft, palette.background, 0.30f), Math.max(34, glassAlpha(76))),
                Color.TRANSPARENT,
                28
        );
        LayerDrawable layers = new LayerDrawable(new Drawable[]{glass, rim, floor});
        layers.setLayerInset(1, dp(1), dp(1), dp(1), dp(8));
        layers.setLayerInset(2, dp(2), dp(9), dp(2), dp(2));
        return layers;
    }

    private Drawable artworkGlassBackground() {
        GradientDrawable glass = roundedGradient(
                translucent(blend(palette.surface, Color.WHITE, 0.16f), glassAlpha(118)),
                translucent(blend(palette.surfaceAlt, palette.background, 0.24f), glassAlpha(92)),
                translucent(blend(Color.WHITE, palette.accent, 0.10f), Math.max(54, glassAlpha(104))),
                24
        );
        GradientDrawable sheen = roundedGradient(
                translucent(Color.WHITE, Math.max(38, glassAlpha(72))),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                24
        );
        LayerDrawable layers = new LayerDrawable(new Drawable[]{glass, sheen});
        layers.setLayerInset(1, dp(1), dp(1), dp(1), dp(5));
        return layers;
    }

    private Drawable navGlassBackground() {
        GradientDrawable base = roundedGradient(
                translucent(blend(palette.surface, Color.WHITE, palette.dark ? 0.04f : 0.24f), glassAlpha(palette.dark ? 140 : 154)),
                translucent(blend(palette.surfaceAlt, palette.background, 0.28f), glassAlpha(palette.dark ? 116 : 126)),
                translucent(blend(Color.WHITE, palette.accent, 0.08f), Math.max(42, glassAlpha(82))),
                0
        );
        GradientDrawable topLine = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{translucent(Color.WHITE, palette.dark ? 28 : 92), Color.TRANSPARENT}
        );
        topLine.setShape(GradientDrawable.RECTANGLE);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, topLine});
        layers.setLayerInset(1, 0, 0, 0, dp(42));
        return layers;
    }

    private GradientDrawable roundedGradient(int top, int bottom, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom}
        );
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setColor(fill);
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable oval(int fill) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fill);
        return drawable;
    }

    private GradientDrawable oval(int fill, int stroke) {
        GradientDrawable drawable = oval(fill);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable ovalGradient(int top, int bottom) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom}
        );
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private Bitmap boxBlur(Bitmap source, int radius) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] input = new int[width * height];
        int[] output = new int[width * height];
        source.getPixels(input, 0, width, 0, 0, width, height);
        int safeRadius = Math.max(1, radius);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int oy = -safeRadius; oy <= safeRadius; oy++) {
                    int py = Math.max(0, Math.min(height - 1, y + oy));
                    for (int ox = -safeRadius; ox <= safeRadius; ox++) {
                        int px = Math.max(0, Math.min(width - 1, x + ox));
                        int color = input[py * width + px];
                        red += Color.red(color);
                        green += Color.green(color);
                        blue += Color.blue(color);
                        count++;
                    }
                }
                output[y * width + x] = Color.rgb(red / count, green / count, blue / count);
            }
        }
        Bitmap blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        blurred.setPixels(output, 0, width, 0, 0, width, height);
        source.recycle();
        return blurred;
    }

    private int translucent(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    private int glassAlpha(int baseAlpha) {
        return Math.max(18, Math.min(255, baseAlpha - glassTransparency()));
    }

    private int blend(int from, int to, float ratio) {
        float clamped = Math.max(0f, Math.min(1f, ratio));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * clamped),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * clamped),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped)
        );
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isEmpty() ? "未知错误" : message;
    }

    private static String commandLabel(String type) {
        switch (type) {
            case "play": return "播放";
            case "pause": return "暂停";
            case "next": return "下一首";
            case "previous": return "上一首";
            case "seek": return "跳转进度";
            default: return "控制";
        }
    }

    private static String maskSecret(String value) {
        if (value == null || value.length() < 8) return "••••••••";
        return value.substring(0, 3) + "••••••••" + value.substring(value.length() - 3);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String playbackMeta(PlaybackSnapshot playback) {
        if (playback == null) return "";
        String packageName = clean(playback.packageName);
        String player = packageName.isEmpty() ? "" : clean(PlayerCatalog.displayName(packageName));
        String album = clean(playback.album);
        if (album.equals(clean(playback.title)) || album.equals(clean(playback.artist))) {
            album = "";
        }
        if (player.equals(packageName)) player = "";
        if (!player.isEmpty() && !album.isEmpty()) return player + " · " + album;
        if (!player.isEmpty()) return player;
        return album;
    }

    private static long liveDisplayPosition(PlaybackSnapshot playback) {
        long position = playback.positionMs;
        if (playback.playing && playback.observedAt > 0L) {
            position += Math.max(0L, System.currentTimeMillis() - playback.observedAt);
        }
        if (playback.durationMs > 0L) position = Math.min(position, playback.durationMs);
        return Math.max(0L, position);
    }

    private static String formatTime(long ms) {
        long seconds = Math.max(0L, ms) / 1000L;
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String formatListeningDuration(long ms) {
        long minutes = Math.max(0L, ms) / 60_000L;
        if (minutes < 60L) return "一起听了 " + minutes + " 分钟";
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0L
                ? "一起听了 " + hours + " 小时"
                : "一起听了 " + hours + " 小时 " + rest + " 分钟";
    }

    private static String formatClock(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(ms));
    }

    private static String relativeTime(long timestamp) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 3L) return "刚刚";
        if (seconds < 60L) return seconds + " 秒前";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + " 分钟前";
        return formatClock(timestamp);
    }

    private static final class Palette {
        final String key;
        final String name;
        final int background;
        final int surface;
        final int surfaceAlt;
        final int text;
        final int secondary;
        final int accent;
        final int onAccent;
        final int accentSoft;
        final int border;
        final int progressTrack;
        final int success;
        final int successSoft;
        final boolean dark;

        Palette(
                String key,
                String name,
                String background,
                String surface,
                String surfaceAlt,
                String text,
                String secondary,
                String accent,
                String onAccent,
                String accentSoft,
                String border,
                String progressTrack,
                String success,
                String successSoft,
                boolean dark
        ) {
            this.key = key;
            this.name = name;
            this.background = Color.parseColor(background);
            this.surface = Color.parseColor(surface);
            this.surfaceAlt = Color.parseColor(surfaceAlt);
            this.text = Color.parseColor(text);
            this.secondary = Color.parseColor(secondary);
            this.accent = Color.parseColor(accent);
            this.onAccent = Color.parseColor(onAccent);
            this.accentSoft = Color.parseColor(accentSoft);
            this.border = Color.parseColor(border);
            this.progressTrack = Color.parseColor(progressTrack);
            this.success = Color.parseColor(success);
            this.successSoft = Color.parseColor(successSoft);
            this.dark = dark;
        }

        static Palette[] values() {
            return new Palette[]{
                    new Palette("cream", "奶油白", "#FAF6EE", "#FFFDFC", "#F4EDE2", "#2E2923", "#7B6F62", "#C98152", "#FFFFFF", "#F4DCCB", "#E8DAC9", "#E8DED2", "#5F8A68", "#E1F0E3", false),
                    new Palette("star", "星空蓝", "#0E1730", "#16213F", "#1D2C51", "#F6F8FF", "#AAB7D7", "#7EA6FF", "#0E1730", "#253D70", "#2B3A60", "#293B66", "#82D3B1", "#1D4B43", true),
                    new Palette("matcha", "抹茶绿", "#F3F7EE", "#FEFFFC", "#E8F0DF", "#283126", "#6D7868", "#789868", "#FFFFFF", "#DCE9D3", "#D2DEC8", "#DCE6D4", "#4F8460", "#DDEEE1", false),
                    new Palette("mist", "雾蓝", "#EFF6F5", "#FCFFFE", "#E0ECEB", "#243231", "#667A78", "#5F8C89", "#FFFFFF", "#D8E9E7", "#CADBD9", "#D5E3E1", "#4E8273", "#DCEFE8", false),
                    new Palette("sakura", "樱花粉", "#FBF3F5", "#FFFDFD", "#F4E5E9", "#33272B", "#826D73", "#B47183", "#FFFFFF", "#F0D9E0", "#E4CED5", "#EBD9DE", "#6F8A66", "#E4F0DE", false),
                    new Palette("lilac", "雾紫", "#F7F2FA", "#FFFCFF", "#EFE5F4", "#30283A", "#786B82", "#8E68B1", "#FFFFFF", "#E8D9F1", "#DED0E6", "#E5D9EC", "#5D8A72", "#DDEEE5", false)
            };
        }

        static Palette fromKey(String key) {
            for (Palette value : values()) {
                if (value.key.equals(key)) return value;
            }
            return values()[0];
        }
    }
}
