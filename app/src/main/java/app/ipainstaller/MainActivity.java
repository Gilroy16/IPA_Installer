package app.ipainstaller;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.ipainstaller.lockdown.PairRecord;
import app.ipainstaller.usbmux.AndroidUsbPipe;
import app.ipainstaller.usbmux.UsbMux;
import app.ipainstaller.util.I18n;

import static app.ipainstaller.util.I18n.t;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "app.ipainstaller.USB_PERMISSION";
    private static final int REQ_PICK = 1;
    private static final String PREFS = "files";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<IpaFile> files = new ArrayList<IpaFile>();

    private UsbManager usb;
    private TextView deviceView, statusView, logView;
    private LinearLayout filesView;
    private ProgressBar progress;
    private Button pickButton, installButton;
    private ScrollView logScroll;

    private volatile IpaInstaller running;
    private View mainRoot;
    private static final long SPLASH_MS = 1200;

    private static final class IpaFile implements IpaInstaller.IpaSource {
        final Uri uri;
        final String name;
        final long size;
        final Context ctx;

        IpaFile(Context ctx, Uri uri, String name, long size) {
            this.ctx = ctx;
            this.uri = uri;
            this.name = name;
            this.size = size;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public InputStream open() throws IOException {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new FileNotFoundException(name);
            return in;
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log(t(granted ? "usb_granted" : "usb_denied"));
                if (granted) startInstall();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && AndroidUsbPipe.isApple(d)) log(t("iphone_detached"));
            }
            refreshDevice();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        String lang = getSharedPreferences(PREFS, MODE_PRIVATE).getString("lang", null);
        if (lang != null) I18n.setLang(lang);
        buildUi();
        showSplash();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }

        restoreFiles();
        handleIntent(getIntent());
        refreshDevice();
        log(t("ready"));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        refreshDevice();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevice();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        IpaInstaller r = running;
        if (r != null) r.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Store logo on black, the same picture the window shows while starting, then the main screen. */
    private void showSplash() {
        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.BLACK);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.splash_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER);
        splash.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(splash);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
                setContentView(mainRoot);
            }
        }, SPLASH_MS);
    }

    private void showLanguageMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, t("lang_ru")).setCheckable(true).setChecked(I18n.RU.equals(I18n.lang()));
        menu.getMenu().add(0, 2, 1, t("lang_en")).setCheckable(true).setChecked(I18n.EN.equals(I18n.lang()));
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                setLanguage(item.getItemId() == 1 ? I18n.RU : I18n.EN);
                return true;
            }
        });
        menu.show();
    }

    private void setLanguage(String lang) {
        if (lang.equals(I18n.lang())) return;
        I18n.setLang(lang);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("lang", lang).apply();
        CharSequence oldLog = logView.getText();
        CharSequence oldStatus = statusView.getText();
        int progressVisibility = progress.getVisibility();
        buildUi();
        logView.setText(oldLog);
        statusView.setText(oldStatus);
        progress.setVisibility(progressVisibility);
        setContentView(mainRoot);
        refreshDevice();
        setBusy(running != null);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText(t("title"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button menuButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        menuButton.setText("⋮");   // vertical ellipsis
        menuButton.setTextSize(22);
        menuButton.setContentDescription(t("language"));
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLanguageMenu(v);
            }
        });
        header.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        deviceView = new TextView(this);
        deviceView.setTextSize(15);
        deviceView.setPadding(0, dp(8), 0, dp(8));
        root.addView(deviceView);

        pickButton = new Button(this);
        pickButton.setText(t("pick"));
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFiles();
            }
        });
        root.addView(pickButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        filesView = new LinearLayout(this);
        filesView.setOrientation(LinearLayout.VERTICAL);
        filesView.setPadding(0, dp(4), 0, dp(4));
        root.addView(filesView);

        installButton = new Button(this);
        installButton.setText(t("install"));
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running != null) {
                    running.cancel();
                    log(t("cancelling"));
                } else {
                    startInstall();
                }
            }
        });
        root.addView(installButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.BLACK);
        statusView.setPadding(0, dp(12), 0, dp(4));
        root.addView(statusView);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView logTitle = new TextView(this);
        logTitle.setText(t("log"));
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(logTitle);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setTextColor(Color.DKGRAY);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mainRoot = root;
        renderFiles();
    }

    private void renderFiles() {
        filesView.removeAllViews();
        if (files.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(t("no_files"));
            t.setTextColor(Color.GRAY);
            t.setPadding(0, dp(6), 0, dp(6));
            filesView.addView(t);
        }
        for (final IpaFile f : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(f.name + (f.size > 0 ? "  (" + IpaInstaller.human(f.size) + ")" : ""));
            t.setTextColor(Color.BLACK);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            t.setSingleLine(true);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = new Button(this);
            remove.setText("✕");
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (running != null) return;
                    files.remove(f);
                    saveFiles();
                    renderFiles();
                }
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
            filesView.addView(row);
        }
    }

    private void refreshDevice() {
        UsbDevice d = findIphone();
        if (d == null) {
            deviceView.setText(t("no_iphone"));
            deviceView.setTextColor(Color.rgb(0xB0, 0x40, 0x30));
        } else {
            String name = d.getProductName() != null ? d.getProductName() : "iPhone";
            deviceView.setText(t("connected", name) + (usb.hasPermission(d) ? "" : t("needs_permission")));
            deviceView.setTextColor(Color.rgb(0x20, 0x80, 0x40));
        }
    }

    private void setBusy(boolean busy) {
        installButton.setText(t(busy ? "cancel" : "install"));
        pickButton.setEnabled(!busy);
        if (busy) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void log(final String line) {
        final String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        ui.post(new Runnable() {
            @Override
            public void run() {
                logView.append(ts + "  " + line + "\n");
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void status(final String text, final int percent) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText(text);
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(percent < 0);
                if (percent >= 0) progress.setProgress(percent);
            }
        });
    }

    // ------------------------------------------------------------------ files

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // provider does not offer persistable grants; works until the app restarts
            }
            addFile(u);
        }
        saveFiles();
        renderFiles();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            log(t("iphone_attached"));
        }
        for (Uri u : uris) addFile(u);
        if (!uris.isEmpty()) {
            saveFiles();
            renderFiles();
        }
    }

    private void addFile(Uri uri) {
        for (IpaFile f : files) {
            if (f.uri.equals(uri)) return;
        }
        String name = null;
        long size = -1;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception e) {
            log(t("file_info_failed", e.getMessage()));
        } finally {
            if (c != null) c.close();
        }
        if (name == null) name = uri.getLastPathSegment() != null ? new File(uri.getLastPathSegment()).getName() : "app.ipa";
        if (!name.toLowerCase(Locale.US).endsWith(".ipa")) log(t("not_ipa", name));
        files.add(new IpaFile(getApplicationContext(), uri, name, size));
    }

    private void saveFiles() {
        StringBuilder sb = new StringBuilder();
        for (IpaFile f : files) sb.append(f.uri.toString()).append('\n');
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("uris", sb.toString()).apply();
    }

    private void restoreFiles() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = p.getString("uris", "");
        for (String line : s.split("\n")) {
            if (line.trim().isEmpty()) continue;
            Uri u = Uri.parse(line.trim());
            try {
                InputStream in = getContentResolver().openInputStream(u);
                if (in != null) in.close();
                addFile(u);
            } catch (Exception e) {
                // file gone or permission lost
            }
        }
        saveFiles();
    }

    // ------------------------------------------------------------------ install

    private UsbDevice findIphone() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (AndroidUsbPipe.isApple(d)) return d;
        }
        return null;
    }

    private void startInstall() {
        if (running != null) return;
        if (files.isEmpty()) {
            log(t("choose_files_first"));
            return;
        }
        final UsbDevice device = findIphone();
        if (device == null) {
            log(t("iphone_not_found"));
            return;
        }
        if (!usb.hasPermission(device)) {
            Intent i = new Intent(ACTION_USB_PERMISSION);
            i.setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, i, flags));
            log(t("permission_requested"));
            return;
        }

        final List<IpaInstaller.IpaSource> sources = new ArrayList<IpaInstaller.IpaSource>(files);
        final IpaInstaller installer = new IpaInstaller(
                new PairRecord.Store(new File(getFilesDir(), "pairing")),
                new IpaInstaller.Listener() {
                    @Override
                    public void log(String line) {
                        MainActivity.this.log(line);
                    }

                    @Override
                    public void status(String text, int percent) {
                        MainActivity.this.status(text, percent);
                    }
                });
        running = installer;
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    AndroidUsbPipe pipe = AndroidUsbPipe.open(usb, device, new UsbMux.Logger() {
                        @Override
                        public void log(String msg) {
                            MainActivity.this.log(msg);
                        }
                    });
                    int ok = installer.installAll(pipe, sources);
                    result = ok == sources.size()
                            ? t("done_all", ok, sources.size())
                            : t("done_partial", ok, sources.size());
                } catch (Exception e) {
                    result = t("error", e.getMessage() != null ? e.getMessage() : e.toString());
                }
                final String r = result;
                log(r);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        running = null;
                        setBusy(false);
                        statusView.setText(r);
                        progress.setVisibility(View.INVISIBLE);
                    }
                });
            }
        }, "installer").start();
    }
}
