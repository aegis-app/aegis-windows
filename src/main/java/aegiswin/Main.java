package aegiswin;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.prefs.Preferences;

// ========================================
// 🖥️ Main (Swing GUI / --cli / --version / --diag)
// ========================================
// 📌 タブ5つ (ブリーフィング / 地震情報 / レーダー / ニュース /
// 設定)。取得は SwingWorker で非同期に行い、Timer で自動更新する。
// 設定は Preferences に保存する。データ層は画面に依存しないため、
// --cli と単体テストからも同じ経路を検証できる。
public final class Main {
// 🎨 テーマ (ダーク)
static final Color BG = new Color(0x0B1220);
static final Color CARD = new Color(0x1B2436);
static final Color TEXT = new Color(0xD8DEE9);
static final Color SUB = new Color(0x9AA7BD);
static final Font FONT = new Font(Font.DIALOG, Font.PLAIN, 13);
static final Font FONT_BOLD = new Font(Font.DIALOG, Font.BOLD, 14);
static final Font TITLE_FONT = new Font(Font.DIALOG, Font.BOLD, 17);

// 🔔 地震の通知条件 (震度値コードと表示ラベルの対応)
static final int[] NOTIFY_QUAKE_SCALES = {10, 20, 30, 40, 45};
static final String[] NOTIFY_QUAKE_LABELS = {"震度1以上", "震度2以上", "震度3以上", "震度4以上", "震度5弱以上"};

private final Preferences prefs = Preferences.userNodeForPackage(Main.class);
private JFrame frame;
private JTabbedPane tabs;
private JPanel briefingPanel;
private JPanel quakesPanel;
private JPanel newsPanel;
private JLabel statusLabel;
// ⚙️ 設定タブの部品
private JRadioButton modeDirect;
private JRadioButton modeServer;
private JTextField serverField;
private JTextField locNameField;
private JTextField locLatField;
private JTextField locLonField;
private JComboBox<String> locPrefCombo;
private JComboBox<String> locAreaCombo;
private JPanel customLocPanel;
private JCheckBox notifyCheck;
private JComboBox<String> notifyQuakeCombo;
private JRadioButton warnAlertOnlyRadio;
private JRadioButton warnAdvisoryRadio;
private JCheckBox trayMinCheck;
private JTextField a2sUrlField;
private JPasswordField a2sTokenField;
// 📡 レーダータブの部品
private JComboBox<String> radarLocCombo;
private JLabel radarImageLabel;
private JLabel radarInfoLabel;
private boolean radarLoaded = false;
private boolean newsLoaded = false;
// 🚨 地震モニター (タブ表示中の30秒更新) と EEW カウントダウン (1秒刻み)
private Timer monitorTimer;
private Timer eewTimer;
private volatile List<Models.EewArrival> eewArrivals;
private JPanel eewCard;
private JTextArea eewCardText;
// 🧪 テスト表示 (デモ)
private boolean demoMode = false;
// 🔔 通知・トレイ常駐
private TrayIcon trayIcon;
private boolean trayHintShown = false;
private final java.util.LinkedHashSet<String> notifySeen = new java.util.LinkedHashSet<>();
private boolean notifyPrimed = false; // 起動後の初回チェックは通知せず登録だけする (フラッド防止)
// 📡 A.A.S プッシュ受信時の再描画スロットル
private volatile long lastA2sRefreshMs;
// 🔄 古い取得結果が新しい画面を上書きしないための世代番号
private int briefingGen = 0;
private int quakesGen = 0;
private int radarGen = 0;

// ----------------------------------------
// 🚀 エントリポイント
// ----------------------------------------
public static void main(String[] args) {
// 🖥️ 文字化け防止: 標準出力を UTF-8 に固定する (--version / --cli 用。GUI には影響しない)
System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
java.nio.charset.StandardCharsets.UTF_8));
if (args.length > 0 && (args[0].equals("--version") || args[0].equals("version"))) {
System.out.println("AEGIS Windowsクライアント " + Models.VERSION);
return;
}
if (args.length > 0 && (args[0].equals("--cli") || args[0].equals("cli"))) {
// 📰 ヘッドレス確認用: ブリーフィングをテキストで標準出力する
for (Models.Section section: Briefing.briefingSections()) {
System.out.println("■ " + section.title());
System.out.println(section.body());
System.out.println();
}
return;
}
if (args.length > 0 && args[0].equals("--diag")) {
// 🔍 接続テスト (自己診断): 各データソースへの到達性を一覧表示する
Preferences preferences = Preferences.userNodeForPackage(Main.class);
String base = "server".equals(preferences.get("mode", "direct"))
? preferences.get("server_url", "http://127.0.0.1:8765"): "";
System.out.println(Http.formatDiag(Http.diagnose(base)));
return;
}
SwingUtilities.invokeLater(() -> new Main().createAndShow());
}

private boolean serverMode() {
return "server".equals(prefs.get("mode", "direct"));
}

private String serverUrl() {
return prefs.get("server_url", "http://127.0.0.1:8765");
}

// ----------------------------------------
// 💾 任意地点 (カスタム監視地点): Preferences にタブ区切りで保存
// ----------------------------------------
private void loadCustomLocations() {
List<Models.Location> locations = new ArrayList<>();
String raw = prefs.get("custom_locations", "");
if (!raw.isBlank()) {
for (String line: raw.split("\n")) {
String[] fields = line.split("\t", -1);
if (fields.length == 5) {
try {
locations.add(new Models.Location(fields[0], Double.parseDouble(fields[1]),
Double.parseDouble(fields[2]), fields[3], fields[4]));
} catch (NumberFormatException ignored) {}
}
}
}
Models.customLocations = locations;
}

private void saveCustomLocations() {
StringBuilder sb = new StringBuilder();
for (Models.Location loc: Models.customLocations) {
if (sb.length() > 0) sb.append("\n");
sb.append(loc.name()).append("\t").append(loc.lat()).append("\t").append(loc.lon())
.append("\t").append(loc.prefCode()).append("\t").append(loc.areaName());
}
prefs.put("custom_locations", sb.toString());
}

private JTextField addLocField(JPanel panel, String hint) {
JTextField field = new JTextField();
field.setFont(FONT);
field.setToolTipText(hint);
field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
field.setAlignmentX(Component.LEFT_ALIGNMENT);
JLabel label = new JLabel(hint);
label.setFont(FONT);
label.setForeground(SUB);
label.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(label);
panel.add(field);
return field;
}

private void renderCustomList() {
if (customLocPanel == null) return;
customLocPanel.removeAll();
List<Models.Location> locations = Models.customLocations;
if (locations.isEmpty()) {
JLabel none = new JLabel("追加された地点はありません（高崎・大宮は標準で監視しています）");
none.setFont(FONT);
none.setForeground(SUB);
none.setAlignmentX(Component.LEFT_ALIGNMENT);
customLocPanel.add(none);
}
for (int i = 0; i < locations.size(); i++) {
final int index = i;
Models.Location loc = locations.get(i);
JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
row.setBackground(BG);
row.setAlignmentX(Component.LEFT_ALIGNMENT);
JLabel label = new JLabel("📍 " + loc.name() + "（" + loc.lat() + ", " + loc.lon() + "）");
label.setFont(FONT);
label.setForeground(TEXT);
JButton delete = new JButton("削除");
delete.addActionListener(e -> {
// 🔄 追加側と同じく「コピー→参照差し替え」で更新する。
// 共有リストを直接変更すると、取得スレッドが走査中に競合するため
List<Models.Location> updated = new ArrayList<>(Models.customLocations);
if (index < updated.size()) updated.remove(index);
Models.customLocations = updated;
saveCustomLocations();
renderCustomList();
refreshRadarLocCombo();
customLocPanel.revalidate();
statusLabel.setText("地点を削除しました");
});
row.add(label);
row.add(delete);
customLocPanel.add(row);
}
customLocPanel.revalidate();
}

private void addCustomLocation() {
String name = locNameField.getText().trim();
int prefIndex = locPrefCombo.getSelectedIndex();
String prefCode = prefIndex >= 0? Models.PREFECTURES[prefIndex][1]: "";
Object areaObj = locAreaCombo.getEditor().getItem();
String area = areaObj == null? "": areaObj.toString().trim();
double lat;
double lon;
try {
lat = Double.parseDouble(locLatField.getText().trim());
lon = Double.parseDouble(locLonField.getText().trim());
} catch (Exception e) {
statusLabel.setText("緯度・経度は数値で入力してください");
return;
}
if (name.isEmpty() || prefCode.isEmpty() || area.isEmpty()) {
statusLabel.setText("地点名・都道府県コード・地域名をすべて入力してください");
return;
}
// ⚠️ 座標の範囲チェック＋日本域外・都道府県との簡易照合の警告
List<String> warnings = Geo.validateLocation(lat, lon, prefCode);
boolean outOfRange = warnings.stream().anyMatch(s -> s.contains("範囲外"));
if (outOfRange) {
statusLabel.setText(warnings.get(0));
return;
}
if (!warnings.isEmpty()) {
int answer = JOptionPane.showConfirmDialog(frame,
String.join("\n", warnings) + "\nこのまま地点を追加しますか？",
"座標の確認", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
if (answer!= JOptionPane.YES_OPTION) {
statusLabel.setText("地点の追加を中止しました");
return;
}
}
List<Models.Location> locations = new ArrayList<>(Models.customLocations);
locations.add(new Models.Location(name, lat, lon, prefCode, area));
Models.customLocations = locations;
saveCustomLocations();
renderCustomList();
refreshRadarLocCombo();
locNameField.setText("");
statusLabel.setText("地点「" + name + "」を追加しました。ブリーフィングに反映されます");
}

// ----------------------------------------
// 🖥️ 画面構築
// ----------------------------------------
private void createAndShow() {
loadCustomLocations();
loadNotifySeen();
A2s.prefUrl = prefs.get("a2s_url", "");
A2s.prefToken = prefs.get("a2s_token", "");
try {
UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
} catch (Exception ignored) {}
frame = new JFrame("🛡️ AEGIS — 地震・津波・気象");
// 🔔 閉じる動作は WindowListener で制御する (トレイ格納 or 終了)
frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
frame.addWindowListener(new WindowAdapter() {
@Override
public void windowClosing(WindowEvent e) {
if (trayMinimizeEnabled() && trayIcon!= null) hideToTray();
else System.exit(0);
}

@Override
public void windowIconified(WindowEvent e) {
if (trayMinimizeEnabled() && trayIcon!= null) hideToTray();
}
});
frame.setSize(760, 640);
frame.setMinimumSize(new Dimension(560, 420));
frame.getContentPane().setBackground(BG);
frame.setLayout(new BorderLayout());

// 📝 ヘッダー
JPanel header = new JPanel(new BorderLayout());
header.setBackground(BG);
JLabel title = new JLabel("🛡️ AEGIS — 地震・津波・気象");
title.setFont(TITLE_FONT);
title.setForeground(Color.WHITE);
title.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
header.add(title, BorderLayout.WEST);
JButton refresh = new JButton("↻ 更新");
refresh.addActionListener(e -> {
loadCurrent();
checkNotifications();
});
JPanel buttonWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT));
buttonWrap.setBackground(BG);
buttonWrap.add(refresh);
header.add(buttonWrap, BorderLayout.EAST);
frame.add(header, BorderLayout.NORTH);

// 🗂️ タブ
tabs = new JTabbedPane();
briefingPanel = newCardListPanel();
quakesPanel = newCardListPanel();
newsPanel = newCardListPanel();
tabs.addTab("ブリーフィング", new JScrollPane(briefingPanel));
tabs.addTab("地震情報", new JScrollPane(quakesPanel));
tabs.addTab("レーダー", buildRadarPanel());
tabs.addTab("ニュース", new JScrollPane(newsPanel));
tabs.addTab("設定", buildSettingsPanel());
frame.add(tabs, BorderLayout.CENTER);

// 🌏 地震モニター: 地震タブ表示中は30秒ごとに自動更新する (非表示時は止める)
monitorTimer = new Timer(30 * 1000, e -> {
if (!demoMode) loadQuakes();
});
monitorTimer.setRepeats(true);
// 🚨 EEW 到達カウントダウン: 1秒ごとに再描画する
eewTimer = new Timer(1000, e -> tickEewCountdown());
eewTimer.setRepeats(true);
tabs.addChangeListener(e -> {
int index = tabs.getSelectedIndex();
if (index == 1) {
monitorTimer.start();
if (eewArrivals!= null &&!eewArrivals.isEmpty()) eewTimer.start();
if (!demoMode) loadQuakes();
} else {
monitorTimer.stop();
eewTimer.stop();
}
if (index == 2 &&!radarLoaded) loadRadar();
if (index == 3 &&!newsLoaded) loadNews();
});

// 📝 ステータス
statusLabel = new JLabel("起動しました");
statusLabel.setForeground(SUB);
statusLabel.setFont(FONT);
statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
frame.add(statusLabel, BorderLayout.SOUTH);

frame.setLocationRelativeTo(null);
frame.setVisible(true);
setupTray();

// 🔔 5分ごとの自動更新 (あわせて新規イベントの通知チェックも行う)
new Timer(5 * 60 * 1000, e -> {
loadCurrent();
checkNotifications();
}).start();
loadBriefing();
checkNotifications(); // 初回は既存イベントを登録するだけ (通知しない)
syncA2s();
}

// ⚙️ A.A.S (SSE) の開始・停止を現在のモードと設定に合わせる
// (直接取得モードのみ。サーバーモードでは AEGISサーバー側の取得に任せる。
// URL・トークンが未指定なら A2s 側で無効となり何もしない)
private void syncA2s() {
A2s.stopStream(); // 設定 (URL・トークン) の変更を反映するため一旦止めてから開始し直す
if (!serverMode() && A2s.enabled()) {
A2s.startStream(event -> {
// 🔄 ここは SSE の受信スレッドから呼ばれるため、UI 更新は
// SwingUtilities.invokeLater 経由で行う。続報が短間隔で
// 届いても再描画が暴走しないよう5秒に絞る
long now = System.currentTimeMillis();
if (now - lastA2sRefreshMs < 5000) return;
lastA2sRefreshMs = now;
SwingUtilities.invokeLater(() -> {
if (!demoMode) loadQuakes();
checkNotifications();
});
});
}
}

// ----------------------------------------
// 🔔 システムトレイ常駐
// ----------------------------------------
private boolean trayMinimizeEnabled() {
return prefs.getBoolean("tray_minimize", false);
}

private void setupTray() {
if (!SystemTray.isSupported()) return;
try {
BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
Graphics2D graphics = image.createGraphics();
graphics.setColor(new Color(0x2B6CB0));
graphics.fillRoundRect(0, 0, 16, 16, 4, 4);
graphics.setColor(Color.WHITE);
graphics.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
graphics.drawString("A", 4, 12);
graphics.dispose();
PopupMenu menu = new PopupMenu();
MenuItem open = new MenuItem("開く");
open.addActionListener(e -> restoreFromTray());
MenuItem refreshItem = new MenuItem("今すぐ更新");
refreshItem.addActionListener(e -> {
restoreFromTray();
loadCurrent();
checkNotifications();
});
MenuItem exit = new MenuItem("終了");
exit.addActionListener(e -> System.exit(0));
menu.add(open);
menu.add(refreshItem);
menu.addSeparator();
menu.add(exit);
trayIcon = new TrayIcon(image, "AEGIS — 地震・津波・気象", menu);
trayIcon.setImageAutoSize(true);
trayIcon.addActionListener(e -> restoreFromTray()); // ダブルクリックで復帰
SystemTray.getSystemTray().add(trayIcon);
} catch (Exception ignored) {
trayIcon = null;
}
}

private void hideToTray() {
frame.setVisible(false);
if (!trayHintShown && trayIcon!= null) {
trayHintShown = true;
try {
trayIcon.displayMessage("AEGIS", "トレイに格納しました。アイコンから復帰・終了できます。",
TrayIcon.MessageType.INFO);
} catch (Exception ignored) {}
}
}

private void restoreFromTray() {
SwingUtilities.invokeLater(() -> {
frame.setVisible(true);
frame.setState(JFrame.NORMAL);
frame.toFront();
});
}

// ----------------------------------------
// 🔔 通知 (バルーン)
// ----------------------------------------
private void loadNotifySeen() {
notifySeen.clear();
notifySeen.addAll(Notify.parseSeenIds(prefs.get("notify_seen", "")));
}

private void saveNotifySeen() {
prefs.put("notify_seen", Notify.joinSeenIds(notifySeen));
}

private void showBalloon(String title, String body) {
if (trayIcon!= null) {
try {
trayIcon.displayMessage(title, body, TrayIcon.MessageType.WARNING);
return;
} catch (Exception ignored) {}
}
// 🔔 トレイが使えない環境ではステータス表示で代替する
statusLabel.setText("🔔 " + title + "：" + body);
}

// 🔔 自動更新にあわせて新規イベントを確認し、未通知かつ発生から
// 1時間以内なら通知する。起動後の初回は既存イベントを登録する
// だけで通知しない (フラッド防止)。通知は直接取得モードのみ
private void checkNotifications() {
if (serverMode()) return;
if (!prefs.getBoolean("notify_enabled", true)) return;
final Models.NotifyPrefs notifyPrefs = new Models.NotifyPrefs(
prefs.getInt("notify_quake_min", 30), prefs.getBoolean("notify_warn_advisory", false));
new SwingWorker<List<Models.NotifyEvent>, Void>() {
@Override
protected List<Models.NotifyEvent> doInBackground() {
return Notify.fetchEvents(notifyPrefs);
}

@Override
protected void done() {
try {
List<Models.NotifyEvent> fresh = Notify.filterNew(get(), notifySeen);
Notify.pruneSeenIds(notifySeen);
saveNotifySeen();
if (!notifyPrimed) {
notifyPrimed = true;
return;
}
long now = System.currentTimeMillis();
for (Models.NotifyEvent event: fresh) {
if (Notify.isFresh(event.occurredMs(), now)) showBalloon(event.title(), event.body());
}
} catch (Exception ignored) {}
}
}.execute();
}

// ----------------------------------------
// ⚙️ 設定タブ
// ----------------------------------------
private JPanel newCardListPanel() {
JPanel panel = new JPanel();
panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
panel.setBackground(BG);
panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
return panel;
}

private JLabel settingsHeading(JPanel panel, String text) {
JLabel heading = new JLabel(text);
heading.setFont(FONT_BOLD);
heading.setForeground(Color.WHITE);
heading.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(heading);
return heading;
}

private JLabel settingsHint(JPanel panel, String text) {
JLabel hint = new JLabel(text);
hint.setFont(FONT);
hint.setForeground(SUB);
hint.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(hint);
return hint;
}

private JPanel buildSettingsPanel() {
JPanel panel = new JPanel();
panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
panel.setBackground(BG);
panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

settingsHeading(panel, "⚙ 設定");
panel.add(Box.createVerticalStrut(10));

// 🔀 動作モード
modeDirect = new JRadioButton("直接取得（推奨）: 地震・津波・EEW・警報・台風は A.A.S から、天気予報などは気象庁・Open-Meteo から取得");
modeServer = new JRadioButton("AEGISサーバーから取得: serve の /api/briefing を表示");
ButtonGroup modeGroup = new ButtonGroup();
modeGroup.add(modeDirect);
modeGroup.add(modeServer);
for (JRadioButton radio: new JRadioButton[]{modeDirect, modeServer}) {
radio.setFont(FONT);
radio.setForeground(TEXT);
radio.setBackground(BG);
radio.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(radio);
}
(serverMode()? modeServer: modeDirect).setSelected(true);
panel.add(Box.createVerticalStrut(10));

settingsHint(panel, "サーバーURL（例: http://192.168.1.10:8765 / 同じPCなら http://127.0.0.1:8765）");
serverField = new JTextField(serverUrl());
serverField.setFont(FONT);
serverField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
serverField.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(serverField);
panel.add(Box.createVerticalStrut(10));

JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
buttons.setBackground(BG);
buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
JButton testButton = new JButton("サーバー接続テスト");
testButton.addActionListener(e -> testServer());
JButton diagButton = new JButton("接続テスト");
diagButton.setToolTipText("各データソースへの接続を自己診断します");
diagButton.addActionListener(e -> runDiagnose());
JButton saveButton = new JButton("設定を保存");
saveButton.addActionListener(e -> saveSettings());
buttons.add(testButton);
buttons.add(diagButton);
buttons.add(saveButton);
panel.add(buttons);
panel.add(Box.createVerticalStrut(12));

// 🧪 テスト表示 (デモ)
settingsHeading(panel, "🧪 テスト表示（デモ）");
settingsHint(panel, "サンプルの緊急地震速報（到達カウントダウン付き）・地震・警報をと明示して表示します。実データの更新で自動的に解除されます。");
JPanel demoButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
demoButtons.setBackground(BG);
demoButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
JButton demoStart = new JButton("テスト表示");
demoStart.addActionListener(e -> startDemo());
JButton demoEnd = new JButton("テスト表示を終了");
demoEnd.addActionListener(e -> endDemo());
demoButtons.add(demoStart);
demoButtons.add(demoEnd);
panel.add(demoButtons);
panel.add(Box.createVerticalStrut(12));

// 🔔 通知・トレイ常駐
settingsHeading(panel, "🔔 通知・トレイ常駐");
settingsHint(panel, "自動更新で新しい地震・津波・緊急地震速報・警報を検出したら、システムトレイのバルーンで通知します（直接取得モードのみ）。通知済みのイベントは保存され、再起動後に再通知されません。");
notifyCheck = new JCheckBox("新規イベントを通知する");
notifyCheck.setFont(FONT);
notifyCheck.setForeground(TEXT);
notifyCheck.setBackground(BG);
notifyCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
notifyCheck.setSelected(prefs.getBoolean("notify_enabled", true));
panel.add(notifyCheck);
JPanel notifyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
notifyRow.setBackground(BG);
notifyRow.setAlignmentX(Component.LEFT_ALIGNMENT);
JLabel quakeLabel = new JLabel("地震の通知条件");
quakeLabel.setFont(FONT);
quakeLabel.setForeground(SUB);
notifyRow.add(quakeLabel);
notifyQuakeCombo = new JComboBox<>(NOTIFY_QUAKE_LABELS);
notifyQuakeCombo.setFont(FONT);
int currentScale = prefs.getInt("notify_quake_min", 30);
for (int i = 0; i < NOTIFY_QUAKE_SCALES.length; i++) {
if (NOTIFY_QUAKE_SCALES[i] == currentScale) notifyQuakeCombo.setSelectedIndex(i);
}
notifyRow.add(notifyQuakeCombo);
JLabel eewNote = new JLabel("（緊急地震速報・津波は常時通知）");
eewNote.setFont(FONT);
eewNote.setForeground(SUB);
notifyRow.add(eewNote);
panel.add(notifyRow);
warnAlertOnlyRadio = new JRadioButton("警報は特別警報・警報のみ通知");
warnAdvisoryRadio = new JRadioButton("警報は注意報も含めて通知");
ButtonGroup warnGroup = new ButtonGroup();
warnGroup.add(warnAlertOnlyRadio);
warnGroup.add(warnAdvisoryRadio);
for (JRadioButton radio: new JRadioButton[]{warnAlertOnlyRadio, warnAdvisoryRadio}) {
radio.setFont(FONT);
radio.setForeground(TEXT);
radio.setBackground(BG);
radio.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(radio);
}
(prefs.getBoolean("notify_warn_advisory", false)? warnAdvisoryRadio: warnAlertOnlyRadio).setSelected(true);
trayMinCheck = new JCheckBox("閉じる/最小化でシステムトレイに格納する（終了はトレイのメニューから）");
trayMinCheck.setFont(FONT);
trayMinCheck.setForeground(TEXT);
trayMinCheck.setBackground(BG);
trayMinCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
trayMinCheck.setSelected(prefs.getBoolean("tray_minimize", false));
panel.add(trayMinCheck);
JPanel notifyButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
notifyButtons.setBackground(BG);
notifyButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
JButton notifyTest = new JButton("通知テスト");
notifyTest.setToolTipText("テスト用のバルーン通知を1件表示します");
notifyTest.addActionListener(e -> {
if (trayIcon == null) {
JOptionPane.showMessageDialog(frame,
"この環境ではシステムトレイが使えないため、バルーン通知を表示できませんでした。\n（Windows では通常、タスクトレイからバルーン通知が表示されます）",
"通知テスト", JOptionPane.WARNING_MESSAGE);
return;
}
showBalloon("AEGIS 通知テスト", "この通知が表示されれば、バルーン通知は正常に動作しています。");
statusLabel.setText("テスト通知を表示しました");
});
notifyButtons.add(notifyTest);
panel.add(notifyButtons);
panel.add(Box.createVerticalStrut(12));

// 📡 A.A.S (Aegis Alert System) 連携
settingsHeading(panel, "📡 A.A.S連携（Aegis Alert System）");
settingsHint(panel, "地震・津波・EEW・警報・台風の情報源は A.A.S のみです（完全一本化、P2P / Wolfx / 気象庁の直接取得とフォールバックはありません）。URL とトークンの両方を入力してください。未入力・到達不能のときは地震系と警報・台風の情報は表示されません（天気予報など他の機能はそのまま動作します）。環境変数 A2S_URL / A2S_TOKEN が設定されている場合はそちらが優先されます。");
settingsHint(panel, "A.A.S URL（例: https://a2s.example.net）");
a2sUrlField = new JTextField(prefs.get("a2s_url", ""));
a2sUrlField.setFont(FONT);
a2sUrlField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
a2sUrlField.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(a2sUrlField);
settingsHint(panel, "A.A.S トークン（提供者から個別に案内された専用トークン）");
a2sTokenField = new JPasswordField(prefs.get("a2s_token", ""));
a2sTokenField.setFont(FONT);
a2sTokenField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
a2sTokenField.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(a2sTokenField);
panel.add(Box.createVerticalStrut(12));

// 📍 任意地点の追加
settingsHeading(panel, "📍 監視地点の追加（任意地点）");
settingsHint(panel, "都道府県を選ぶと、気象庁の予報から地域区分（一次細分区域）の候補を取得します。取得できない場合は地域名を直接入力できます。");
locNameField = addLocField(panel, "地点名（例: 実家）");
JPanel latLon = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
latLon.setBackground(BG);
latLon.setAlignmentX(Component.LEFT_ALIGNMENT);
locLatField = new JTextField(10);
locLonField = new JTextField(10);
latLon.add(new JLabel("緯度"));
latLon.add(locLatField);
latLon.add(new JLabel("経度"));
latLon.add(locLonField);
panel.add(latLon);
JPanel prefArea = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
prefArea.setBackground(BG);
prefArea.setAlignmentX(Component.LEFT_ALIGNMENT);
locPrefCombo = new JComboBox<>();
for (String[] pref: Models.PREFECTURES) locPrefCombo.addItem(pref[0]);
locPrefCombo.setSelectedItem("群馬県");
locPrefCombo.setFont(FONT);
locAreaCombo = new JComboBox<>();
locAreaCombo.setEditable(true); // 候補取得に失敗しても自由入力できるようにする
locAreaCombo.setFont(FONT);
locAreaCombo.setPreferredSize(new Dimension(140, 28));
locPrefCombo.addActionListener(e -> loadAreaCandidates());
prefArea.add(new JLabel("都道府県"));
prefArea.add(locPrefCombo);
prefArea.add(new JLabel("地域区分"));
prefArea.add(locAreaCombo);
panel.add(prefArea);
loadAreaCandidates();
for (Component component: latLon.getComponents()) {
if (component instanceof JLabel label) {
label.setForeground(SUB);
label.setFont(FONT);
}
}
for (Component component: prefArea.getComponents()) {
if (component instanceof JLabel label) {
label.setForeground(SUB);
label.setFont(FONT);
}
}
JButton addLoc = new JButton("地点を追加");
addLoc.setAlignmentX(Component.LEFT_ALIGNMENT);
addLoc.addActionListener(e -> addCustomLocation());
panel.add(addLoc);
customLocPanel = new JPanel();
customLocPanel.setLayout(new BoxLayout(customLocPanel, BoxLayout.Y_AXIS));
customLocPanel.setBackground(BG);
customLocPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(customLocPanel);
renderCustomList();
panel.add(Box.createVerticalStrut(12));

JTextArea note = new JTextArea("表示中は5分ごとに自動更新されます。\nサーバーモードは、自宅LAN内での利用を想定しています。\n通知は直接取得モードのみです。「トレイに格納」を有効にした場合は、終了はトレイアイコンの右クリックメニューから行ってください。");
note.setFont(FONT);
note.setForeground(SUB);
note.setBackground(BG);
note.setEditable(false);
note.setAlignmentX(Component.LEFT_ALIGNMENT);
panel.add(note);
return panel;
}

private void saveSettings() {
prefs.put("mode", modeServer.isSelected()? "server": "direct");
prefs.put("server_url", serverField.getText().trim());
if (notifyCheck!= null) {
prefs.putBoolean("notify_enabled", notifyCheck.isSelected());
int quakeIndex = notifyQuakeCombo.getSelectedIndex();
if (quakeIndex >= 0) prefs.putInt("notify_quake_min", NOTIFY_QUAKE_SCALES[quakeIndex]);
prefs.putBoolean("notify_warn_advisory", warnAdvisoryRadio.isSelected());
}
if (trayMinCheck!= null) prefs.putBoolean("tray_minimize", trayMinCheck.isSelected());
if (a2sUrlField!= null) {
prefs.put("a2s_url", a2sUrlField.getText().trim());
prefs.put("a2s_token", new String(a2sTokenField.getPassword()).trim());
A2s.prefUrl = prefs.get("a2s_url", "");
A2s.prefToken = prefs.get("a2s_token", "");
syncA2s();
}
statusLabel.setText("設定を保存しました（モード: " + (modeServer.isSelected()? "AEGISサーバー": "直接取得") + "）");
tabs.setSelectedIndex(0);
loadCurrent();
}

// 🌐 選択中の都道府県の予報JSONから地域区分名の候補を取得して
// コンボに入れる (失敗時は自由入力のまま)
private void loadAreaCandidates() {
if (locPrefCombo == null || locAreaCombo == null) return;
int index = locPrefCombo.getSelectedIndex();
if (index < 0) return;
final String prefCode = Models.PREFECTURES[index][1];
new SwingWorker<List<String>, Void>() {
@Override
protected List<String> doInBackground() {
try {
Object root = Http.fetchJson("https://www.jma.go.jp/bosai/forecast/data/forecast/" + prefCode + ".json");
return Weather.forecastAreaNames(root);
} catch (Exception e) {
return List.of();
}
}

@Override
protected void done() {
try {
List<String> names = get();
if (names.isEmpty()) return; // 取得失敗: 自由入力フォールバック (コンボは編集可能)
Object previous = locAreaCombo.getEditor().getItem();
locAreaCombo.removeAllItems();
for (String name: names) locAreaCombo.addItem(name);
locAreaCombo.setSelectedItem(previous!= null && names.contains(previous.toString())? previous: names.get(0));
} catch (Exception ignored) {}
}
}.execute();
}

// 🔍 接続テスト (自己診断): 全データソースへの到達性を一覧ダイアログで表示する
private void runDiagnose() {
statusLabel.setText("接続テスト中…（全データソース）");
final String base = serverMode()? serverUrl(): "";
new SwingWorker<List<Models.DiagResult>, Void>() {
@Override
protected List<Models.DiagResult> doInBackground() {
return Http.diagnose(base);
}

@Override
protected void done() {
try {
List<Models.DiagResult> results = get();
long okCount = results.stream().filter(Models.DiagResult::ok).count();
statusLabel.setText("接続テスト完了： " + okCount + "/" + results.size() + " 件 OK");
JTextArea area = new JTextArea(Http.formatDiag(results));
area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
area.setEditable(false);
area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
JOptionPane.showMessageDialog(frame, new JScrollPane(area),
"接続テスト（自己診断） — " + okCount + "/" + results.size() + " 件 OK",
okCount == results.size()? JOptionPane.INFORMATION_MESSAGE: JOptionPane.WARNING_MESSAGE);
} catch (Exception ignored) {}
}
}.execute();
}

private void testServer() {
String base = serverField.getText().trim();
if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
final String url = base + "/api/history";
statusLabel.setText("接続テスト中… " + url);
new SwingWorker<String, Void>() {
@Override
protected String doInBackground() {
try {
int length = Http.fetchTextLength(url);
return "✅ 接続OK: " + url + "（応答 " + length + " 文字）";
} catch (Exception e) {
return "❌ 接続失敗: " + Http.friendlyError(e) + "（サーバー起動とURLを確認）";
}
}

@Override
protected void done() {
try {
statusLabel.setText(get());
} catch (Exception ignored) {}
}
}.execute();
}

// ----------------------------------------
// 🧪 テスト表示 (デモ)
// ----------------------------------------
private void startDemo() {
demoMode = true;
long now = System.currentTimeMillis();
// 🚨 サンプル EEW: 発生は現在時刻、主要動の到達は約30秒後 (カウントダウンが実際に動く)
List<Models.EewArrival> arrivals = new ArrayList<>();
for (Models.Location loc: Geo.effectiveLocations()) {
arrivals.add(new Models.EewArrival(loc.name(), now + 30_000, false));
}
eewArrivals = arrivals;
String nowText = new java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN).format(new java.util.Date(now));
Models.Quake sampleQuake = new Models.Quake("サンプル震源（架空）",
nowText + "発生 / M6.0 / 深さ約40km / 津波の心配なし（テスト）", "CRITICAL", 45, 0, 0, now);
// 🚨 ブリーフィング側: サンプル警報 + サンプル EEW + サンプル地震
List<Models.Section> demoBriefing = new ArrayList<>(List.of(
new Models.Section("📌 現在のまとめ",
"⚠ 発表中： 大雨警報（テスト）\n🌊 津波: なし\n🌏 最新の地震: 震度5弱 サンプル震源（架空）\n※ テスト表示中です。実データではありません。", "warn"),
new Models.Section("⚠ 気象警報・注意報",
"⚠ 発表中： 大雨警報（テスト）\n　テスト用のサンプル警報です。実際の警報ではありません。", "warn"),
new Models.Section("🚨 緊急地震速報",
"震源 サンプル震源（架空） / 推定最大震度 5弱\n強い揺れに備えてください。（テスト表示： 到達カウントダウンは地震タブで動いています）", "danger")));
renderSections(briefingPanel, demoBriefing);
// 🌏 地震タブ側: カウントダウン + サンプル地震を大きく表示
quakesPanel.removeAll();
updateEewCountdownCard();
quakesPanel.add(makeQuakeCard(sampleQuake, true));
quakesPanel.add(Box.createVerticalGlue());
quakesPanel.revalidate();
quakesPanel.repaint();
// 🌏 地震タブへ切り替えてからタイマーを開始する (開始はタブ表示中と結び付ける)
tabs.setSelectedIndex(1);
eewTimer.start();
statusLabel.setText("テスト表示中（実データではありません）。解除は「テスト表示を終了」または実データへの更新で");
}

private void endDemo() {
if (!demoMode && eewArrivals == null) return;
demoMode = false;
eewArrivals = null;
eewTimer.stop();
if (eewCard!= null && eewCard.getParent() == quakesPanel) quakesPanel.remove(eewCard);
eewCard = null;
statusLabel.setText("テスト表示を終了しました");
loadBriefing();
loadQuakes();
}

// ----------------------------------------
// 📰 ブリーフィングの読み込みとキャッシュ
// ----------------------------------------
// 📰 ブリーフィングの取得結果 (セクション一覧＋地点ごとの表表示用データ。表は直接取得モードのみ)
private record BriefingResult(List<Models.Section> sections, Map<String, Models.WeatherTables> tables) {}

private static final String CACHE_KEY = "cache_briefing";
private static final String CACHE_TS_KEY = "cache_briefing_ts";

// 🌐 取得失敗のセクションを含む場合は保存しない (失敗表示を
// 次回起動時のキャッシュとして残さないため)
private void saveBriefingCache(List<Models.Section> sections) {
for (Models.Section section: sections) {
if (section.body().contains("取得できませんでした") || section.title().contains("取得エラー")) return;
}
StringBuilder sb = new StringBuilder();
for (Models.Section section: sections) {
if (sb.length() > 0) sb.append("");
sb.append(section.title()).append("").append(section.body()).append("").append(section.level());
}
String serialized = sb.toString();
if (!UiFormat.briefingCacheFits(serialized)) return; // 上限を超える場合は保存しない
prefs.put(CACHE_KEY, serialized);
prefs.putLong(CACHE_TS_KEY, System.currentTimeMillis());
}

private List<Models.Section> loadBriefingCache() {
String raw = prefs.get(CACHE_KEY, "");
if (raw.isEmpty()) return null;
List<Models.Section> sections = new ArrayList<>();
for (String record: raw.split("")) {
String[] fields = record.split("", -1);
if (fields.length == 3) sections.add(new Models.Section(fields[0], fields[1], fields[2]));
}
return sections.isEmpty()? null: sections;
}

private void loadBriefing() {
final int gen = ++briefingGen;
List<Models.Section> cached = loadBriefingCache();
if (cached!= null) {
renderSections(briefingPanel, cached);
long timestamp = prefs.getLong(CACHE_TS_KEY, 0);
String when = timestamp > 0
? new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(new java.util.Date(timestamp)): "";
statusLabel.setText("キャッシュ" + (when.isEmpty()? "": "（" + when + "時点）") + "を表示中（更新中…）");
} else {
statusLabel.setText("取得中…");
}
final boolean server = serverMode();
final String url = serverUrl();
new SwingWorker<BriefingResult, Void>() {
@Override
protected BriefingResult doInBackground() {
try {
if (server) return new BriefingResult(Briefing.serverSections(url), Map.of());
List<Models.Section> sections = Briefing.briefingSections();
// 🌐 表表示用に、地点ごとの時間別・週間予報 (構造化データ) も並列取得する
Map<String, Models.WeatherTables> tables = new LinkedHashMap<>();
Models.Location[] locations = Geo.effectiveLocations();
List<Future<Models.WeatherTables>> futures = new ArrayList<>();
for (Models.Location loc: locations) {
futures.add(Http.FETCH_POOL.submit(() -> Weather.openMeteoTables(loc)));
}
for (int i = 0; i < locations.length; i++) {
try {
tables.put(locations[i].name(),
futures.get(i).get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
} catch (Exception ignored) {}
}
return new BriefingResult(sections, tables);
} catch (Exception e) {
List<Models.Section> fallback = new ArrayList<>();
fallback.add(new Models.Section("⚠ 取得エラー",
"取得できませんでした：" + Http.friendlyError(e) + "\n通信状態と設定を確認してください。", "warn"));
return new BriefingResult(fallback, Map.of());
}
}

@Override
protected void done() {
if (gen!= briefingGen) return; // 既に新しい読み込みが始まっている
try {
BriefingResult result = get();
List<Models.Section> sections = result.sections();
if (result.tables().isEmpty()) renderSections(briefingPanel, sections);
else renderBriefing(briefingPanel, sections, result.tables());
// 🌏 実データで上書きされたらテスト表示は解除する (地震タブ側も実データに戻す)
if (demoMode) {
demoMode = false;
loadQuakes();
}
saveBriefingCache(sections);
boolean allFailed =!sections.isEmpty();
for (Models.Section section: sections) {
if (!section.body().contains("取得できませんでした") &&!section.title().contains("取得エラー")) {
allFailed = false;
break;
}
}
statusLabel.setText(allFailed? "⚠ 取得に失敗しました（通信状態を確認してください）"
: "最終更新: " + nowTime() + "（" + (server? "サーバー": "直接取得") + "）");
} catch (Exception ignored) {}
}
}.execute();
}

// ----------------------------------------
// 🌏 地震タブの読み込み
// ----------------------------------------
private void loadQuakes() {
final int gen = ++quakesGen;
statusLabel.setText("取得中…");
final boolean server = serverMode();
final String url = serverUrl();
new SwingWorker<Object[], Void>() {
@Override
protected Object[] doInBackground() {
if (server) {
try {
return new Object[]{Briefing.serverSections(url), List.of(), null};
} catch (Exception e) {
return new Object[]{List.of(new Models.Section("⚠ 取得エラー",
"取得できませんでした：" + Http.friendlyError(e), "warn")), List.of(), null};
}
}
List<Models.Section> sections = Briefing.quakesSections();
Models.EewInfo eew = Eew.eewInfo();
List<Models.Quake> quakes;
try {
quakes = Quakes.quakeItems(15);
} catch (Exception e) {
sections.add(new Models.Section("🌏 地震情報", "取得できませんでした：" + Http.friendlyError(e), "warn"));
quakes = List.of();
}
if (quakes.isEmpty()) {
sections.add(new Models.Section("🌏 地震情報", "最近の地震情報はありません。", "ok"));
}
return new Object[]{sections, quakes, eew};
}

@Override
protected void done() {
if (gen!= quakesGen) return; // 既に新しい読み込みが始まっている
// 🧪 実データで上書きされたらテスト表示は解除する
// (到達予想をクリアする経路ではタイマー停止もセットにする)
if (demoMode) {
demoMode = false;
eewArrivals = null;
eewTimer.stop();
}
try {
Object[] result = get();
@SuppressWarnings("unchecked") List<Models.Section> sections = (List<Models.Section>) result[0];
@SuppressWarnings("unchecked") List<Models.Quake> quakes = (List<Models.Quake>) result[1];
Models.EewInfo eew = (Models.EewInfo) result[2];
renderSections(quakesPanel, sections);
// 🚨 EEW カウントダウン: 新鮮な到達予想があれば最上部に固定表示する
if (eew!= null &&!eew.arrivals().isEmpty()) {
eewArrivals = eew.arrivals();
// 🌏 タイマーの開始は地震タブ表示中と結び付ける
if (tabs.getSelectedIndex() == 1) eewTimer.start();
} else if (eewArrivals!= null &&!demoMode) {
// 📝 到達予想をクリアする経路ではタイマー停止もセットにする
eewArrivals = null;
eewTimer.stop();
}
updateEewCountdownCard();
// 🌏 モニター表示: 最新の地震は大きく表示する
for (int i = 0; i < quakes.size(); i++) quakesPanel.add(makeQuakeCard(quakes.get(i), i == 0));
quakesPanel.add(Box.createVerticalGlue());
quakesPanel.revalidate();
quakesPanel.repaint();
statusLabel.setText("最終更新: " + nowTime() + "（" + (server? "サーバー": "直接取得") + "）");
} catch (Exception ignored) {}
}
}.execute();
}

// ----------------------------------------
// 🚨 EEW 到達カウントダウンカード (地震タブ最上部に固定)
// ----------------------------------------
private void updateEewCountdownCard() {
if (eewCard!= null && eewCard.getParent() == quakesPanel) quakesPanel.remove(eewCard);
eewCard = null;
if (eewArrivals == null || eewArrivals.isEmpty()) return;
JPanel card = new JPanel(new BorderLayout(8, 4));
card.setBackground(CARD);
card.setBorder(BorderFactory.createCompoundBorder(
BorderFactory.createMatteBorder(0, 4, 0, 0, UiFormat.levelColor("danger")),
BorderFactory.createEmptyBorder(10, 12, 10, 12)));
card.setAlignmentX(Component.LEFT_ALIGNMENT);
JLabel title = new JLabel("● 🚨 揺れの到達カウントダウン" + (demoMode? "": ""));
title.setFont(FONT_BOLD);
title.setForeground(Color.WHITE);
card.add(title, BorderLayout.NORTH);
eewCardText = new JTextArea();
eewCardText.setFont(new Font(Font.DIALOG, Font.BOLD, 16));
eewCardText.setForeground(TEXT);
eewCardText.setBackground(CARD);
eewCardText.setEditable(false);
eewCardText.setLineWrap(true);
card.add(eewCardText, BorderLayout.CENTER);
JPanel wrap = new JPanel(new BorderLayout());
wrap.setBackground(BG);
wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
wrap.add(card, BorderLayout.CENTER);
eewCard = wrap;
quakesPanel.add(eewCard, 0);
tickEewCountdown();
quakesPanel.revalidate();
quakesPanel.repaint();
}

private void tickEewCountdown() {
if (eewArrivals == null || eewCardText == null) return;
long now = System.currentTimeMillis();
StringBuilder sb = new StringBuilder();
boolean anyLive = false;
for (Models.EewArrival arrival: eewArrivals) {
long remainMs = arrival.arrivalMs() - now;
if (sb.length() > 0) sb.append("\n");
if (remainMs > 0) {
sb.append(arrival.locName()).append("： あと約").append((remainMs + 999) / 1000).append("秒");
if (arrival.estimated()) sb.append("（推定）");
anyLive = true;
} else if (now - arrival.arrivalMs() <= 30_000) {
sb.append(arrival.locName()).append("： 到達した可能性が高いです");
anyLive = true;
} else {
sb.append(arrival.locName()).append("： 到達予想時刻を過ぎました");
}
}
eewCardText.setText(sb.toString());
if (!anyLive) {
// 🖥️ 全地点で到達後30秒を過ぎたら通常表示に戻す
eewArrivals = null;
eewTimer.stop();
if (eewCard!= null && eewCard.getParent() == quakesPanel) {
quakesPanel.remove(eewCard);
quakesPanel.revalidate();
quakesPanel.repaint();
}
eewCard = null;
}
}

// ----------------------------------------
// 📡 レーダータブ
// ----------------------------------------
private JPanel buildRadarPanel() {
JPanel panel = new JPanel(new BorderLayout());
panel.setBackground(BG);
JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
controls.setBackground(BG);
radarLocCombo = new JComboBox<>();
refreshRadarLocCombo();
radarLocCombo.setFont(FONT);
radarLocCombo.addActionListener(e -> {
if (radarLoaded) loadRadar();
});
JButton reload = new JButton("↻ レーダーを更新");
reload.addActionListener(e -> loadRadar());
JLabel locLabel = new JLabel("地点：");
locLabel.setForeground(SUB);
locLabel.setFont(FONT);
controls.add(locLabel);
controls.add(radarLocCombo);
controls.add(reload);
panel.add(controls, BorderLayout.NORTH);
radarImageLabel = new JLabel("レーダーを読み込んでいません", JLabel.CENTER);
radarImageLabel.setForeground(SUB);
radarImageLabel.setFont(FONT);
panel.add(radarImageLabel, BorderLayout.CENTER);
radarInfoLabel = new JLabel("レーダー: RainViewer＋気象庁ナウキャスト併用 / 地図: 国土地理院");
radarInfoLabel.setForeground(SUB);
radarInfoLabel.setFont(FONT);
radarInfoLabel.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
panel.add(radarInfoLabel, BorderLayout.SOUTH);
return panel;
}

private void refreshRadarLocCombo() {
if (radarLocCombo == null) return;
String previous = (String) radarLocCombo.getSelectedItem();
radarLocCombo.removeAllItems();
for (Models.Location loc: Geo.effectiveLocations()) radarLocCombo.addItem(loc.name());
if (previous!= null) radarLocCombo.setSelectedItem(previous);
}

private void loadRadar() {
final int gen = ++radarGen;
radarLoaded = true;
Models.Location[] locations = Geo.effectiveLocations();
int index = radarLocCombo.getSelectedIndex();
final Models.Location loc = locations[Math.max(0, Math.min(index, locations.length - 1))];
radarImageLabel.setIcon(null);
radarImageLabel.setText("レーダーを取得中…（" + loc.name() + "）");
statusLabel.setText("レーダーを取得中…");
new SwingWorker<BufferedImage, Void>() {
private Models.RadarFrame frame;
private Models.JmaNowcastFrame jmaFrame;
private String error;
private String note = "";

@Override
protected BufferedImage doInBackground() {
// 🌦️ RainViewer を主ソース、気象庁ナウキャストを第2
// ソース (解像度補完＋フォールバック) として併用する。
// 失敗は明示する
try {
frame = Radar.frame();
} catch (Exception e) {
error = Http.friendlyError(e);
}
try {
jmaFrame = Radar.jmaNowcastFrame();
} catch (Exception ignored) {}
if (frame == null && jmaFrame == null) {
error = "RainViewer・気象庁ナウキャストの両方で取得できませんでした"
+ (error!= null? "：" + error: "");
return null;
}
int zoom = 8;
int[] center = Geo.tileXY(loc.lat(), loc.lon(), zoom);
int originX = center[0] - 1;
int originY = center[1] - 1;
BufferedImage image = new BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB);
Graphics2D graphics = image.createGraphics();
graphics.setColor(new Color(0x22304A));
graphics.fillRect(0, 0, 768, 768);
// 📝 ベース地図 (地理院タイル z8) を 3×3 合成する (枚数を数える)
int gotBase = 0;
for (int dy = -1; dy <= 1; dy++) {
for (int dx = -1; dx <= 1; dx++) {
byte[] bytes = Http.fetchBytes(Radar.gsiTileUrl(zoom, center[0] + dx, center[1] + dy));
if (bytes == null) continue;
try {
BufferedImage tile = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
if (tile!= null) {
graphics.drawImage(tile, (dx + 1) * 256, (dy + 1) * 256, null);
gotBase++;
}
} catch (Exception ignored) {}
}
}
StringBuilder notes = new StringBuilder();
String baseNote = Radar.tileCoverageNote("地図", 9, gotBase);
if (!baseNote.isEmpty()) notes.append(baseNote);
// 🌦️ 気象庁ナウキャスト (z8 ネイティブ) を先に描き、解像度を補完する
if (jmaFrame!= null) {
int gotJma = 0;
for (int dy = -1; dy <= 1; dy++) {
for (int dx = -1; dx <= 1; dx++) {
byte[] bytes = Http.fetchBytes(Radar.jmaNowcastTileUrl(
jmaFrame.basetime(), jmaFrame.validtime(), zoom, center[0] + dx, center[1] + dy));
if (bytes == null) continue;
try {
BufferedImage tile = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
if (tile!= null) {
graphics.drawImage(tile, (dx + 1) * 256, (dy + 1) * 256, null);
gotJma++;
}
} catch (Exception ignored) {}
}
}
String jmaNote = Radar.tileCoverageNote("気象庁ナウキャスト", 9, gotJma);
if (!jmaNote.isEmpty()) {
if (notes.length() > 0) notes.append(" / ");
notes.append(jmaNote);
}
}
// 📡 レーダー (RainViewer) は z7 までしかタイルが無い
// ため、z7 タイルを 512×512 に拡大して重ねる (z8 で
// 取るとバナー画像が返ってくる)
if (frame!= null) {
graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
List<int[]> cover = Radar.coverTiles(originX, originY);
int gotRain = 0;
for (int[] tileSpec: cover) {
byte[] bytes = Http.fetchBytes(Radar.tileUrl(frame.path(), 7, tileSpec[0], tileSpec[1]));
if (bytes == null) continue;
try {
BufferedImage tile = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
if (tile!= null) {
graphics.drawImage(tile, tileSpec[2], tileSpec[3], 512, 512, null);
gotRain++;
}
} catch (Exception ignored) {}
}
String rainNote = Radar.tileCoverageNote("レーダー", cover.size(), gotRain);
if (!rainNote.isEmpty()) {
if (notes.length() > 0) notes.append(" / ");
notes.append(rainNote);
}
}
note = notes.toString();
// 📍 地点マーカー: 小数タイル座標から算出した実位置に
// 描く (タイル中心固定だと地点がタイル端寄りの場合に
// 最大半タイルずれる)
int[] marker = Geo.markerPixel(loc.lat(), loc.lon(), zoom, originX, originY);
int markerX = marker[0];
int markerY = marker[1];
graphics.setColor(Color.WHITE);
graphics.fillOval(markerX - 8, markerY - 8, 16, 16);
graphics.setColor(new Color(0xD42B2B));
graphics.fillOval(markerX - 6, markerY - 6, 12, 12);
graphics.drawLine(markerX, markerY - 16, markerX, markerY + 16);
graphics.drawLine(markerX - 16, markerY, markerX + 16, markerY);
graphics.dispose();
return image;
}

@Override
protected void done() {
if (gen!= radarGen) return; // 既に新しい読み込みが始まっている
try {
BufferedImage image = get();
if (image == null) {
// 📝 両ソースとも失敗した場合は無言で消さず明示する
radarImageLabel.setText("レーダー画像を取得できませんでした" + (error!= null? "：" + error: ""));
radarInfoLabel.setText("レーダー取得失敗: " + (error!= null? error: "不明なエラー"));
statusLabel.setText("レーダーの取得に失敗しました");
return;
}
radarImageLabel.setIcon(new ImageIcon(image.getScaledInstance(720, 720, java.awt.Image.SCALE_SMOOTH)));
radarImageLabel.setText("");
java.text.SimpleDateFormat whenFormat = new java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.JAPAN);
whenFormat.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
StringBuilder info = new StringBuilder();
if (frame!= null) {
String when = whenFormat.format(new java.util.Date(frame.timeEpochSec() * 1000));
// 🕐 フレームの相対時刻 (約N分前の観測。古い場合は注記つき) を併記する
String ageNote = Radar.frameAgeNote(frame.timeEpochSec(), System.currentTimeMillis());
info.append("レーダー時刻： ").append(when)
.append(ageNote.isEmpty()? "": "（" + ageNote + "）");
info.append(" / レーダー: RainViewer＋気象庁ナウキャスト併用（")
.append(Radar.RAINVIEWER_RESOLUTION_NOTE).append("／")
.append(Radar.JMA_NOWCAST_NOTE).append("）");
} else {
// 🌦️ フォールバック: 気象庁ナウキャストのみで表示している旨を明示する
String validtime = jmaFrame.validtime();
String when = validtime;
try {
java.util.Date parsed = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.JAPAN).parse(validtime);
if (parsed!= null) when = whenFormat.format(parsed);
} catch (Exception ignored) {}
info.append("レーダー時刻： ").append(when)
.append(" / レーダー: 気象庁ナウキャストのみ（RainViewerを取得できなかったためフォールバック表示中")
.append(error!= null? "：" + error: "").append("／")
.append(Radar.JMA_NOWCAST_NOTE).append("）");
}
info.append("（").append(loc.name()).append("付近） / 地図: 国土地理院");
if (!note.isEmpty()) info.append(" / ⚠ ").append(note);
radarInfoLabel.setText(info.toString());
statusLabel.setText("最終更新: " + nowTime() + "（レーダー）");
} catch (Exception ignored) {}
}
}.execute();
}

// ----------------------------------------
// 📰 ニュースタブ
// ----------------------------------------
private void loadNews() {
newsLoaded = true;
statusLabel.setText("ニュースを取得中…");
new SwingWorker<List<Models.NewsItem>, Void>() {
@Override
protected List<Models.NewsItem> doInBackground() throws Exception {
return News.newsItems();
}

@Override
protected void done() {
try {
List<Models.NewsItem> items = get();
newsPanel.removeAll();
if (items.isEmpty()) {
newsPanel.add(makeCard(new Models.Section("📰 防災ニュース", "ニュースがありません。", "info")));
}
for (Models.NewsItem item: items) newsPanel.add(makeNewsCard(item));
newsPanel.add(Box.createVerticalGlue());
newsPanel.revalidate();
newsPanel.repaint();
statusLabel.setText("最終更新: " + nowTime() + "（ニュース）");
} catch (Exception e) {
newsPanel.removeAll();
newsPanel.add(makeCard(new Models.Section("📰 防災ニュース",
"取得できませんでした：" + Http.friendlyError(e), "warn")));
newsPanel.add(Box.createVerticalGlue());
newsPanel.revalidate();
newsPanel.repaint();
statusLabel.setText("ニュースの取得に失敗しました");
}
}
}.execute();
}

private JPanel makeNewsCard(Models.NewsItem item) {
JPanel card = new JPanel(new BorderLayout(8, 4));
card.setBackground(CARD);
card.setBorder(BorderFactory.createCompoundBorder(
BorderFactory.createMatteBorder(0, 4, 0, 0,
UiFormat.levelColor(News.isDisasterNews(item.title())? "warn": "info")),
BorderFactory.createEmptyBorder(10, 12, 10, 12)));
card.setAlignmentX(Component.LEFT_ALIGNMENT);
JLabel title = new JLabel("<html><u>" + UiFormat.escapeHtml(item.title()) + "</u></html>");
title.setFont(FONT_BOLD);
title.setForeground(new Color(0x9CC3FF));
title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
title.setToolTipText("クリックでブラウザで開く");
title.addMouseListener(new MouseAdapter() {
@Override
public void mouseClicked(MouseEvent e) {
try {
java.awt.Desktop.getDesktop().browse(new java.net.URI(item.link()));
} catch (Exception ex) {
statusLabel.setText("リンクを開けませんでした");
}
}
});
card.add(title, BorderLayout.NORTH);
JTextArea body = new JTextArea((item.time().isEmpty()? "": item.time() + " — ") + item.summary());
body.setFont(FONT);
body.setForeground(TEXT);
body.setBackground(CARD);
body.setEditable(false);
body.setLineWrap(true);
body.setWrapStyleWord(false);
card.add(body, BorderLayout.CENTER);
JPanel wrap = new JPanel(new BorderLayout());
wrap.setBackground(BG);
wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
wrap.add(card, BorderLayout.CENTER);
return wrap;
}

// ----------------------------------------
// 🎴 カード描画
// ----------------------------------------
private void renderSections(JPanel panel, List<Models.Section> sections) {
panel.removeAll();
for (Models.Section section: sections) panel.add(makeCard(section));
panel.add(Box.createVerticalGlue());
panel.revalidate();
panel.repaint();
}

// 🌦️ ブリーフィング表示 (直接取得モード): 天気カードの時間別・
// 週間予報はテキスト羅列ではなく表 (JTable) で示す。表データの
// ある地点はカード本文から該当行を取り除き、カード直下に表を置く
private void renderBriefing(JPanel panel, List<Models.Section> sections, Map<String, Models.WeatherTables> tables) {
panel.removeAll();
for (Models.Section section: sections) {
Models.WeatherTables table = null;
if (section.title().startsWith("☀ 天気予報（") && section.title().endsWith("）")) {
String locName = section.title().substring("☀ 天気予報（".length(), section.title().length() - 1);
Models.WeatherTables candidate = tables.get(locName);
if (candidate!= null && (!candidate.hourly().isEmpty() ||!candidate.weekly().isEmpty())) table = candidate;
}
if (table == null) {
panel.add(makeCard(section));
} else {
panel.add(makeCard(new Models.Section(section.title(), UiFormat.stripTableLines(section.body()), section.level())));
panel.add(makeWeatherTablesPanel(table));
}
}
panel.add(Box.createVerticalGlue());
panel.revalidate();
panel.repaint();
}

private JPanel makeWeatherTablesPanel(Models.WeatherTables tables) {
JPanel panel = new JPanel();
panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
panel.setBackground(BG);
panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
panel.setAlignmentX(Component.LEFT_ALIGNMENT);
if (!tables.hourly().isEmpty()) {
panel.add(tableHeading("🕒 時間別予報"));
String[][] rows = new String[tables.hourly().size()][5];
for (int i = 0; i < rows.length; i++) {
Models.HourlyEntry entry = tables.hourly().get(i);
rows[i][0] = entry.dateLabel() + " " + entry.hour() + "時";
rows[i][1] = entry.emoji() + " " + entry.weather();
rows[i][2] = entry.tempC() == null? "—": Math.round(entry.tempC()) + "℃";
// 📝 区間最大がサンプリング値より高い場合は併記する
rows[i][3] = entry.popPct() < 0? "—": entry.popPct() + "%"
+ (entry.popIntervalMax() > Math.max(entry.popPct(), 0)? "（最大" + entry.popIntervalMax() + "）": "");
rows[i][4] = entry.precipMm() == null? "—": Weather.fmtMm(entry.precipMm()) + "mm";
}
panel.add(makeTable(new String[]{"時刻", "天気", "気温", "降水確率", "降水量"}, rows,
new int[]{110, 160, 80, 110, 80}));
}
if (!tables.weekly().isEmpty()) {
panel.add(tableHeading("📅 週間予報"));
String[][] rows = new String[tables.weekly().size()][5];
for (int i = 0; i < rows.length; i++) {
Models.DailyEntry entry = tables.weekly().get(i);
rows[i][0] = entry.dateLabel();
rows[i][1] = entry.emoji() + " " + entry.weather();
rows[i][2] = (entry.minC() == null? "—": String.valueOf(Math.round(entry.minC())))
+ "〜" + (entry.maxC() == null? "—": String.valueOf(Math.round(entry.maxC()))) + "℃";
rows[i][3] = entry.popPct() < 0? "—": entry.popPct() + "%";
rows[i][4] = entry.precipMm() == null? "—": Weather.fmtMm(entry.precipMm()) + "mm";
}
panel.add(makeTable(new String[]{"日付", "天気", "気温（最低〜最高）", "降水確率", "降水量"}, rows,
new int[]{90, 160, 170, 90, 80}));
}
return panel;
}

private JLabel tableHeading(String text) {
JLabel heading = new JLabel(text);
heading.setFont(FONT_BOLD);
heading.setForeground(TEXT);
heading.setAlignmentX(Component.LEFT_ALIGNMENT);
heading.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
return heading;
}

// 📝 ダークテーマの読み取り専用テーブル (ヘッダー付きでパネルに埋め込む)
private JPanel makeTable(String[] columns, String[][] rows, int[] widths) {
DefaultTableModel model = new DefaultTableModel(rows, columns) {
@Override
public boolean isCellEditable(int row, int col) {
return false;
}
};
JTable table = new JTable(model);
table.setFont(FONT);
table.setRowHeight(22);
table.setBackground(CARD);
table.setForeground(TEXT);
table.setGridColor(new Color(0x33415C));
table.setRowSelectionAllowed(false);
table.getTableHeader().setFont(FONT_BOLD);
table.getTableHeader().setBackground(new Color(0x141C2E));
table.getTableHeader().setForeground(SUB);
table.getTableHeader().setReorderingAllowed(false);
for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
}
JPanel wrap = new JPanel(new BorderLayout());
wrap.setBackground(CARD);
wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
wrap.add(table.getTableHeader(), BorderLayout.NORTH);
wrap.add(table, BorderLayout.CENTER);
int height = 28 + rows.length * 22 + 2;
wrap.setPreferredSize(new Dimension(600, height));
wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
return wrap;
}

private JPanel makeCard(Models.Section section) {
JPanel card = new JPanel(new BorderLayout(8, 4));
card.setBackground(CARD);
card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
card.setAlignmentX(Component.LEFT_ALIGNMENT);
JLabel title = new JLabel("● " + section.title());
title.setFont(FONT_BOLD);
title.setForeground(Color.WHITE);
// 🎨 ドットの色はタイトル先頭の●だけには付けられないため、左ボーダーで重要度を表す
card.setBorder(BorderFactory.createCompoundBorder(
BorderFactory.createMatteBorder(0, 4, 0, 0, UiFormat.levelColor(section.level())),
BorderFactory.createEmptyBorder(10, 12, 10, 12)));
card.add(title, BorderLayout.NORTH);
JTextArea body = new JTextArea(section.body());
body.setFont(FONT);
body.setForeground(TEXT);
body.setBackground(CARD);
body.setEditable(false);
body.setLineWrap(true);
body.setWrapStyleWord(false);
card.add(body, BorderLayout.CENTER);
JPanel wrap = new JPanel(new BorderLayout());
wrap.setBackground(BG);
wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
wrap.add(card, BorderLayout.CENTER);
return wrap;
}

private JPanel makeQuakeCard(Models.Quake quake, boolean large) {
JPanel card = new JPanel(new BorderLayout(10, 0));
card.setBackground(CARD);
card.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
card.setAlignmentX(Component.LEFT_ALIGNMENT);
JLabel badge = new JLabel("震度" + Quakes.scaleToStr(quake.maxScale()), JLabel.CENTER);
badge.setFont(large? new Font(Font.DIALOG, Font.BOLD, 20): FONT_BOLD);
badge.setForeground(Color.WHITE);
badge.setOpaque(true);
badge.setBackground(UiFormat.scaleColor(quake.maxScale()));
badge.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
card.add(badge, BorderLayout.WEST);
JPanel texts = new JPanel();
texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
texts.setBackground(CARD);
JLabel place = new JLabel((large? "🌏 最新の地震： ": "") + quake.place());
place.setFont(large? new Font(Font.DIALOG, Font.BOLD, 17): FONT_BOLD);
place.setForeground(Color.WHITE);
String detailText = quake.detail();
if (large && quake.epochMs() > 0) {
long minutes = Math.max(0, (System.currentTimeMillis() - quake.epochMs()) / 60000);
detailText += " / " + (minutes < 60? minutes + "分前": (minutes / 60) + "時間" + (minutes % 60) + "分前");
}
JLabel detail = new JLabel(detailText);
detail.setFont(large? new Font(Font.DIALOG, Font.PLAIN, 14): FONT);
detail.setForeground(SUB);
texts.add(place);
texts.add(detail);
card.add(texts, BorderLayout.CENTER);
if (quake.lat()!= 0 || quake.lon()!= 0) {
JButton mapButton = new JButton("🗺 地図");
mapButton.setFont(FONT);
mapButton.addActionListener(e -> {
try {
java.awt.Desktop.getDesktop().browse(new java.net.URI(
"https://www.openstreetmap.org/?mlat=" + quake.lat() + "&mlon=" + quake.lon()
+ "#map=8/" + quake.lat() + "/" + quake.lon()));
} catch (Exception ex) {
statusLabel.setText("地図を開けませんでした");
}
});
card.add(mapButton, BorderLayout.EAST);
}
JPanel wrap = new JPanel(new BorderLayout());
wrap.setBackground(BG);
wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, large? 120: 80));
wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
wrap.add(card, BorderLayout.CENTER);
return wrap;
}

private void loadCurrent() {
int index = tabs.getSelectedIndex();
if (index == 1) loadQuakes();
else if (index == 2) loadRadar();
else if (index == 3) loadNews();
else if (index == 0) loadBriefing();
}

private String nowTime() {
return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
}
}
