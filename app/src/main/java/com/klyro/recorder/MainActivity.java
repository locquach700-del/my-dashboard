package com.klyro.recorder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.Settings;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.content.ContentValues;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 410;
    private static final int REQ_OVERLAY = 411;
    private static final int REQ_AUDIO = 412;
    private static final int REQ_IMAGE = 413;
    private static final String PREFS = "klyro";

    private SharedPreferences prefs;
    private LinearLayout pageHost;
    private TextView statusText;
    private TextView timerText;
    private TextView[] navItems = new TextView[4];
    private TextView[] navLabels = new TextView[4];
    private LinearLayout[] navContainers = new LinearLayout[4];
    private int page = 0;
    private String recordMode = "video";
    private String pendingMode = "video";
    private boolean waitingForOverlay;
    private boolean waitingForAudio;
    private boolean pendingVideoStart;
    private int countdownLeft;
    private final Handler handler = new Handler();

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (RecordingService.ACTION_TICK.equals(intent.getAction())) {
                long ms = intent.getLongExtra("elapsed", 0);
                if (timerText != null) timerText.setText(formatDuration(ms));
                String state = intent.getStringExtra("state");
                if (statusText != null) {
                    if ("paused".equals(state) || intent.getBooleanExtra("paused", false)) statusText.setText("Đã tạm dừng");
                    else if ("recording".equals(state)) statusText.setText("Đang ghi");
                }
            } else if (RecordingService.ACTION_SAVED.equals(intent.getAction())) {
                Toast.makeText(MainActivity.this, I18n.t(MainActivity.this,"saved"), Toast.LENGTH_LONG).show();
                if (page == 1) showPage(1);
                if (statusText != null) statusText.setText("Đã lưu vào thư viện");
            } else if (RecordingService.ACTION_ERROR.equals(intent.getAction())) {
                Toast.makeText(MainActivity.this, intent.getStringExtra("message"), Toast.LENGTH_LONG).show();
                if (statusText != null) statusText.setText("Sẵn sàng");
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Window w = getWindow();
        w.setStatusBarColor(Color.WHITE);
        w.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildShell();
        showPage(0);
        new Thread(this::recoverPendingRecordings,"Klyro-Recovery").start();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 50);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(RecordingService.ACTION_TICK);
        f.addAction(RecordingService.ACTION_SAVED);
        f.addAction(RecordingService.ACTION_ERROR);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(recordingReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(recordingReceiver, f);
    }
    @Override protected void onStop() {
        try { unregisterReceiver(recordingReceiver); } catch (Exception ignored) {}
        super.onStop();
    }
    private void buildShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(246,248,252));
        shell.setPadding(dp(18), dp(10), dp(18), 0);
        setContentView(shell);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(14));
        TextView brand = text("Klyro", 25, Color.rgb(25,31,45), true);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView version = text("SCREEN STUDIO  •  1.2.0", 10, Color.rgb(126,137,157), true);
        header.addView(version);
        shell.addView(header);

        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        shell.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(8), 0, dp(14));
        nav.setBackground(round(Color.WHITE, dp(20)));
        String[] icons = {"⌂","◷","⚙","◎"};
        String[] keys = {"home","history","settings","touch"};
        for (int i=0;i<4;i++) {
            final int target=i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            TextView icon = text(icons[i], 21, Color.rgb(110,120,140), true);
            TextView label = text(I18n.t(this,keys[i]), 10, Color.rgb(110,120,140), true);
            item.addView(icon);
            item.addView(label);
            navItems[i] = icon;
            navLabels[i] = label;
            navContainers[i] = item;
            item.setPadding(dp(10),dp(4),dp(10),dp(4));
            item.setOnClickListener(v -> showPage(target));
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-1, dp(60));
        navLp.setMargins(0,dp(8),0,dp(10));
        shell.addView(nav,navLp);
    }

    private void showPage(int which) {
        page = which;
        pageHost.removeAllViews();
        if (which == 0) buildHome();
        else if (which == 1) buildHistory();
        else if (which == 2) buildSettings();
        else buildTap();
        updateNavigationSelection();
    }

    private void updateNavigationSelection() {
        for (int i = 0; i < navContainers.length; i++) {
            boolean selected = i == page;
            int color = selected ? Color.rgb(42,105,236) : Color.rgb(110,120,140);
            navContainers[i].setBackground(round(selected ? Color.rgb(232,240,255) : Color.TRANSPARENT, dp(14)));
            navItems[i].setTextColor(color);
            navLabels[i].setTextColor(color);
            navContainers[i].setAlpha(selected ? 1f : 0.82f);
        }
    }

    private ScrollView scroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        return s;
    }

    private void buildHome() {
        ScrollView sc = scroll();
        LinearLayout body = column();
        sc.addView(body);
        pageHost.addView(sc, new LinearLayout.LayoutParams(-1,-1));

        TextView title = text(I18n.t(this,"ready"),23,Color.rgb(25,31,45),true);
        body.addView(title, margin(0,dp(4),0,dp(3)));
        body.addView(text("Gọn nhẹ • Không watermark • Lưu trực tiếp trên máy",13,Color.rgb(120,130,150),false), margin(0,0,0,dp(16)));

        LinearLayout status = card();
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(dp(16),dp(15),dp(16),dp(15));
        LinearLayout top = row();
        TextView dot = text("●",16,Color.rgb(35,190,120),true);
        top.addView(dot);
        TextView label = text("  KLYRO RECORDER",11,Color.rgb(112,123,144),true);
        top.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView local = text("LOCAL",10,Color.rgb(39,122,233),true);
        top.addView(local);
        status.addView(top);
        statusText = text("Sẵn sàng",20,Color.rgb(25,31,45),true);
        status.addView(statusText,margin(0,dp(12),0,dp(4)));
        TextView hint = text("Video MP4 kèm tiếng ứng dụng hoặc ghi âm riêng",12,Color.rgb(124,134,153),false);
        status.addView(hint);
        body.addView(status,margin(0,0,0,dp(14)));

        body.addView(text("CHẾ ĐỘ GHI",11,Color.rgb(105,117,139),true),margin(2,0,0,dp(8)));
        LinearLayout modes = row();
        View video = modeCard("▣",I18n.t(this,"video"),"MP4 • Màn hình", "video".equals(recordMode), "video");
        View audio = modeCard("♫",I18n.t(this,"audio_only"),"M4A • Chọn nguồn âm thanh", "audio".equals(recordMode), "audio");
        modes.addView(video,new LinearLayout.LayoutParams(0,dp(92),1));
        modes.addView(space(dp(10)));
        modes.addView(audio,new LinearLayout.LayoutParams(0,dp(92),1));
        body.addView(modes,margin(0,0,0,dp(13)));

        if ("audio".equals(recordMode)) {
            body.addView(text("NGUỒN ÂM THANH",11,Color.rgb(105,117,139),true),margin(2,0,0,dp(8)));
            LinearLayout sources = row();
            View appAudio = audioSourceCard("♫",I18n.t(this,"audio_app"),"Không thu micro", "app");
            View microphoneAudio = audioSourceCard("◉",I18n.t(this,"audio_mic"),"Thu âm thanh bên ngoài", "mic");
            sources.addView(appAudio,new LinearLayout.LayoutParams(0,dp(88),1));
            sources.addView(space(dp(10)));
            sources.addView(microphoneAudio,new LinearLayout.LayoutParams(0,dp(88),1));
            body.addView(sources,margin(0,0,0,dp(10)));
            body.addView(text(I18n.t(this,"audio_capture_note"),11,Color.rgb(115,126,145),false),margin(2,0,2,dp(12)));
        }

        LinearLayout opts = card();
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setPadding(dp(14),dp(8),dp(14),dp(8));
        Switch appAudio = switcher(I18n.t(this,"app_audio_video"),prefs.getBoolean("app_audio_video",true));
        Switch mic = switcher(I18n.t(this,"microphone"),prefs.getBoolean("mic",false));
        appAudio.setOnCheckedChangeListener((b,v)->{
            prefs.edit().putBoolean("app_audio_video",v).putBoolean("mic",v ? false : prefs.getBoolean("mic",false)).apply();
            if (v) mic.setChecked(false);
        });
        mic.setOnCheckedChangeListener((b,v)->{
            prefs.edit().putBoolean("mic",v).putBoolean("app_audio_video",v ? false : prefs.getBoolean("app_audio_video",true)).apply();
            if (v) appAudio.setChecked(false);
        });
        appAudio.setEnabled("video".equals(recordMode));
        mic.setEnabled("video".equals(recordMode));
        opts.addView(appAudio);
        opts.addView(mic);
        Switch countdown = switcher(I18n.t(this,"countdown"),prefs.getBoolean("countdown",true));
        countdown.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("countdown",v).apply());
        opts.addView(countdown);
        Switch floating = switcher(I18n.t(this,"floating"),prefs.getBoolean("floating",true));
        floating.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean("floating",v).apply());
        opts.addView(floating);
        body.addView(opts,margin(0,0,0,dp(14)));

        LinearLayout preview = card();
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(14),dp(12),dp(14),dp(12));
        TextView settingsSummary = text("CHẤT LƯỢNG ĐANG CHỌN",10,Color.rgb(111,122,143),true);
        preview.addView(settingsSummary);
        String resolution = resolutionLabel(prefs.getInt("resolution",2));
        String fps = fpsLabel(prefs.getInt("fps",2));
        String br = bitrateLabel(prefs.getInt("bitrate",1));
        preview.addView(text(resolution+"   •   "+fps+" FPS   •   "+br,14,Color.rgb(38,48,68),true),margin(0,dp(7),0,dp(4)));
        long mb = (long)(prefs.getInt("bitrate",1)==0 ? 1.0 : prefs.getInt("bitrate",1)==1 ? 2.0 : prefs.getInt("bitrate",1)==2 ? 4.0 : prefs.getInt("bitrate",1)==3 ? 8.0 : 12.0) * 1000000L;
        long size30 = (mb+128000L)*1800L/8L;
        preview.addView(text("Ước tính 30 phút: khoảng "+formatBytes(size30)+" (phụ thuộc nội dung và thiết bị).",11,Color.rgb(123,133,151),false));
        body.addView(preview,margin(0,0,0,dp(16)));

        Button start = button(I18n.t(this,"start"),true);
        start.setOnClickListener(v->attemptStart());
        body.addView(start,margin(0,0,0,dp(18)));
        timerText = text("00:00",12,Color.rgb(125,135,154),false);
        timerText.setGravity(Gravity.CENTER);
        body.addView(timerText);
        body.addView(text(I18n.t(this,"video_audio_note"),11,Color.rgb(125,135,154),false),margin(0,dp(10),0,dp(18)));
    }

    private View modeCard(String glyph,String title,String sub,boolean selected,String mode) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12),dp(10),dp(12),dp(10));
        c.setBackground(round(selected?Color.rgb(232,240,255):Color.WHITE,dp(16)));
        TextView i = text(glyph,20,selected?Color.rgb(41,103,235):Color.rgb(105,117,139),true);
        c.addView(i);
        c.addView(text(title,13,Color.rgb(28,37,54),true),margin(0,dp(4),0,0));
        c.addView(text(sub,10,Color.rgb(125,135,154),false));
        c.setOnClickListener(v->{ recordMode = mode; showPage(0); });
        return c;
    }

    private View audioSourceCard(String glyph,String title,String sub,String source) {
        boolean selected = source.equals(prefs.getString("audio_source","app"));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12),dp(10),dp(12),dp(10));
        card.setBackground(round(selected ? Color.rgb(232,240,255) : Color.WHITE,dp(15)));
        TextView icon = text(glyph,19,selected ? Color.rgb(42,105,236) : Color.rgb(105,117,139),true);
        card.addView(icon);
        card.addView(text(title,12,Color.rgb(31,41,60),true),margin(0,dp(3),0,0));
        card.addView(text(sub,10,Color.rgb(125,135,154),false));
        card.setOnClickListener(v -> {
            prefs.edit().putString("audio_source",source).apply();
            showPage(0);
        });
        return card;
    }

    private void attemptStart() {
        if ("audio".equals(recordMode)) {
            pendingMode = "app".equals(prefs.getString("audio_source","app")) ? "internal_audio" : "audio";
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                waitingForAudio = true;
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},REQ_AUDIO);
                return;
            }
            startCountdownForPendingMode();
            return;
        }

        boolean appAudio = prefs.getBoolean("app_audio_video",true);
        pendingMode = appAudio ? "video_app_audio" : "video";
        boolean needAudioPermission = appAudio || prefs.getBoolean("mic",false);
        if (needAudioPermission && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waitingForAudio = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},REQ_AUDIO);
            return;
        }
        startCountdownForPendingMode();
    }

    private void startCountdownForPendingMode() {
        if (prefs.getBoolean("countdown",true)) {
            countdownLeft = 3;
            if (statusText != null) statusText.setText("Bắt đầu sau 3…");
            handler.removeCallbacks(countdownTick);
            handler.post(countdownTick);
        } else {
            launchPendingMode();
        }
    }

    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (countdownLeft <= 0) {
                launchPendingMode();
                return;
            }
            if (statusText != null) statusText.setText("Bắt đầu sau "+countdownLeft+"…");
            countdownLeft--;
            handler.postDelayed(this,1000);
        }
    };

    private void launchPendingMode() {
        if ("video".equals(pendingMode) || "video_app_audio".equals(pendingMode) || "internal_audio".equals(pendingMode)) {
            requestProjection();
        } else {
            startAudioOnly();
        }
    }

    private void requestProjection() {
        try {
            MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this,"Không thể mở quyền thu âm thanh/quay màn hình",Toast.LENGTH_LONG).show();
        }
    }

    private void startAudioOnly() {
        Intent service = new Intent(this,RecordingService.class);
        service.setAction(RecordingService.ACTION_START);
        service.putExtra(RecordingService.EXTRA_MODE,"audio");
        service.putExtra("mic",true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        if (statusText != null) statusText.setText("Đang ghi âm micro");
    }

    private void startInternalAudioService(int resultCode,Intent data) {
        Intent service = new Intent(this,RecordingService.class);
        service.setAction(RecordingService.ACTION_START);
        service.putExtra(RecordingService.EXTRA_MODE,"internal_audio");
        service.putExtra(RecordingService.EXTRA_RESULT,resultCode);
        service.putExtra(RecordingService.EXTRA_DATA,data);
        service.putExtra("mic",false);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        if (statusText != null) statusText.setText("Đang thu âm thanh ứng dụng (không dùng micro)");
    }

    private void startVideoService(int resultCode,Intent data) {
        int[] size = selectedOutputSize();
        Intent service = new Intent(this,RecordingService.class);
        service.setAction(RecordingService.ACTION_START);
        service.putExtra(RecordingService.EXTRA_MODE,"video");
        service.putExtra(RecordingService.EXTRA_RESULT,resultCode);
        service.putExtra(RecordingService.EXTRA_DATA,data);
        service.putExtra("width",size[0]);
        service.putExtra("height",size[1]);
        service.putExtra("fps",selectedFps());
        service.putExtra("bitrate",selectedBitrate());
        service.putExtra("mic",prefs.getBoolean("mic",false));
        service.putExtra("app_audio",prefs.getBoolean("app_audio_video",true));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        if (statusText != null) statusText.setText(prefs.getBoolean("app_audio_video",true)
                ? "Đang quay màn hình + âm thanh ứng dụng" : "Đang ghi màn hình");
    }

    private int[] selectedOutputSize() {
        int index = prefs.getInt("resolution",2);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int nativeW = dm.widthPixels, nativeH = dm.heightPixels;
        int longSide = Math.max(nativeW,nativeH);
        int shortSide = Math.min(nativeW,nativeH);
        int targetShort = shortSide;
        if (index==1) targetShort=480;
        else if (index==2) targetShort=720;
        else if (index==3) targetShort=1080;
        else if (index==4) targetShort=1440;
        else if (index==5) targetShort=2160;
        if (index==0) targetShort=shortSide;
        float ratio=(float)longSide/(float)shortSide;
        if (targetShort==2160 && ratio>1.7778f) targetShort=(int)(3840f/ratio);
        int w=(int)(targetShort*ratio);
        int h=targetShort;
        if (nativeW<nativeH) { int t=w; w=h; h=t; }
        w=Math.max(2,w-(w%2)); h=Math.max(2,h-(h%2));
        return new int[]{w,h};
    }
    private int selectedFps() {
        int[] values={30,24,30,60,90,120};
        return values[Math.min(values.length-1,prefs.getInt("fps",2))];
    }
    private int selectedBitrate() {
        int[] values={1000000,2000000,4000000,8000000,12000000};
        return values[Math.min(values.length-1,prefs.getInt("bitrate",1))];
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode==REQ_CAPTURE) {
            if (resultCode==RESULT_OK && data!=null) {
                if ("internal_audio".equals(pendingMode)) startInternalAudioService(resultCode,data);
                else startVideoService(resultCode,data);
            } else {
                if (statusText!=null) statusText.setText("Bạn đã huỷ quyền thu âm thanh/quay màn hình");
            }
        } else if (requestCode==REQ_IMAGE) {
            if (resultCode==RESULT_OK && data!=null && data.getData()!=null) {
                Uri uri=data.getData();
                try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                prefs.edit().putString("tap_image",uri.toString()).apply();
                Toast.makeText(this,"Đã chọn ảnh dấu chạm",Toast.LENGTH_SHORT).show();
                showPage(3);
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if (requestCode==REQ_AUDIO) {
            if (results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) {
                waitingForAudio=false;
                startCountdownForPendingMode();
            } else {
                waitingForAudio=false;
                Toast.makeText(this,"Cần quyền âm thanh của Android để ghi micro hoặc thu âm thanh ứng dụng.",Toast.LENGTH_LONG).show();
            }
        }
    }

    private void recoverPendingRecordings() {
        SharedPreferences state = getSharedPreferences(PREFS, MODE_PRIVATE);
        long heartbeat = state.getLong("recording_heartbeat", 0L);
        boolean active = state.getBoolean("recording_active", false);
        long now = System.currentTimeMillis();
        if (active && heartbeat > 0L && now - heartbeat < 15000L) return;
        if (active) state.edit().putBoolean("recording_active", false).apply();

        int recovered = 0;
        File[] roots = {
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        };
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            File[] files = root.listFiles();
            if (files == null) continue;
            for (File source : files) {
                if (!source.isFile() || !source.getName().startsWith("Klyro_")) continue;
                if (source.length() < 2048L || now - source.lastModified() < 10000L) continue;
                boolean audio = source.getName().toLowerCase(Locale.ROOT).endsWith(".m4a");
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                boolean playable = false;
                try {
                    metadata.setDataSource(source.getAbsolutePath());
                    String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    playable = duration != null && Long.parseLong(duration) >= 1000L;
                } catch (Exception ignored) {
                    playable = false;
                } finally {
                    try { metadata.release(); } catch (Exception ignored) {}
                }
                if (!playable) continue;

                android.content.ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, audio ? "audio/mp4" : "video/mp4");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        audio ? Environment.DIRECTORY_MUSIC + "/Klyro/" : Environment.DIRECTORY_MOVIES + "/Klyro/");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri collection = audio
                        ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri target = null;
                try {
                    target = resolver.insert(collection, values);
                    if (target == null) continue;
                    try (InputStream in = new FileInputStream(source);
                         OutputStream out = resolver.openOutputStream(target, "w")) {
                        if (out == null) throw new IllegalStateException("Cannot open recovery output");
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                        out.flush();
                    }
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(target, ready, null, null);
                    if (source.delete()) recovered++;
                } catch (Exception e) {
                    if (target != null) {
                        try { resolver.delete(target, null, null); } catch (Exception ignored) {}
                    }
                }
            }
        }
        if (recovered > 0) {
            final int count = recovered;
            runOnUiThread(() -> Toast.makeText(this,
                    "Klyro đã khôi phục " + count + " bản ghi vào thư viện.",
                    Toast.LENGTH_LONG).show());
        }
    }

    private void buildSettings() {
        ScrollView sc=scroll();
        LinearLayout body=column(); sc.addView(body);
        pageHost.addView(sc,new LinearLayout.LayoutParams(-1,-1));
        body.addView(text(I18n.t(this,"settings"),23,Color.rgb(25,31,45),true),margin(0,dp(4),0,dp(4)));
        body.addView(text("Điều chỉnh chất lượng để cân bằng độ nét, FPS và dung lượng.",12,Color.rgb(122,132,151),false),margin(0,0,0,dp(14)));

        LinearLayout quality=card(); quality.setPadding(dp(14),dp(10),dp(14),dp(10)); quality.setOrientation(LinearLayout.VERTICAL);
        addSpinnerRow(quality,I18n.t(this,"resolution"),new String[]{"Tự động","480p","720p","1080p","1440p","4K"}, "resolution",2);
        addSpinnerRow(quality,I18n.t(this,"fps"),new String[]{"Tự động (30)","24 FPS","30 FPS","60 FPS","90 FPS","120 FPS"},"fps",2);
        addSpinnerRow(quality,I18n.t(this,"bitrate"),new String[]{"Tiết kiệm • 1 Mbps","Nhẹ • 2 Mbps","Cân bằng • 4 Mbps","Rõ nét • 8 Mbps","4K • 12 Mbps"},"bitrate",1);
        body.addView(quality,margin(0,0,0,dp(14)));

        LinearLayout general=card(); general.setPadding(dp(14),dp(8),dp(14),dp(8)); general.setOrientation(LinearLayout.VERTICAL);
        addSwitchRow(general,I18n.t(this,"countdown"),"countdown",true);
        addSwitchRow(general,I18n.t(this,"floating"),"floating",true);
        addSwitchRow(general,"Ghi kèm micro khi quay video","mic",false);
        body.addView(general,margin(0,0,0,dp(14)));

        LinearLayout language=card(); language.setPadding(dp(14),dp(12),dp(14),dp(12)); language.setOrientation(LinearLayout.VERTICAL);
        language.addView(text(I18n.t(this,"language"),14,Color.rgb(32,42,61),true));
        Spinner lang=spinner(I18n.languageLabels());
        String current=prefs.getString("language","system");
        String[] codes=I18n.languageCodes();
        int select=0; for(int i=0;i<codes.length;i++) if(codes[i].equals(current)) select=i;
        lang.setSelection(select);
        lang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            boolean first=true;
            @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){
                if(first){first=false;return;}
                String old=prefs.getString("language","system");
                prefs.edit().putString("language",codes[pos]).apply();
                if(!old.equals(codes[pos])) recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        language.addView(lang,margin(0,dp(8),0,0));
        body.addView(language,margin(0,0,0,dp(14)));

        LinearLayout storage=card(); storage.setPadding(dp(14),dp(12),dp(14),dp(12)); storage.setOrientation(LinearLayout.VERTICAL);
        storage.addView(text("NƠI LƯU TỆP",10,Color.rgb(112,122,142),true));
        storage.addView(text("Video: Movies/Klyro\nÂm thanh: Music/Klyro",14,Color.rgb(35,45,64),true),margin(0,dp(7),0,0));
        body.addView(storage,margin(0,0,0,dp(18)));
    }

    private void addSpinnerRow(LinearLayout parent,String title,String[] items,String key,int def) {
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0,dp(7),0,dp(7));
        row.addView(text(title,13,Color.rgb(44,54,72),true));
        Spinner sp=spinner(items);
        sp.setSelection(Math.min(items.length-1,prefs.getInt(key,def)));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt(key,pos).apply();}
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        row.addView(sp,margin(0,dp(5),0,0));
        parent.addView(row);
    }
    private void addSwitchRow(LinearLayout parent,String title,String key,boolean def) {
        Switch s=switcher(title,prefs.getBoolean(key,def));
        s.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean(key,v).apply());
        parent.addView(s);
    }

    private void buildTap() {
        ScrollView sc=scroll(); LinearLayout body=column(); sc.addView(body);
        pageHost.addView(sc,new LinearLayout.LayoutParams(-1,-1));
        body.addView(text(I18n.t(this,"touch"),23,Color.rgb(25,31,45),true),margin(0,dp(4),0,dp(4)));
        body.addView(text("Tuỳ chỉnh dấu chạm và xem thử trước khi dùng.",12,Color.rgb(122,132,151),false),margin(0,0,0,dp(13)));

        LinearLayout preview=card(); preview.setPadding(dp(14),dp(12),dp(14),dp(12)); preview.setOrientation(LinearLayout.VERTICAL);
        preview.addView(text(I18n.t(this,"preview"),12,Color.rgb(107,119,140),true));
        TapPreview pv=new TapPreview(this);
        preview.addView(pv,new LinearLayout.LayoutParams(-1,dp(150)));
        body.addView(preview,margin(0,0,0,dp(13)));

        LinearLayout controls=card(); controls.setPadding(dp(14),dp(10),dp(14),dp(10)); controls.setOrientation(LinearLayout.VERTICAL);
        addSwitchRow(controls,I18n.t(this,"tap_enable"),"tap_enabled",false);
        Button access=button(I18n.t(this,"accessibility"),false);
        access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        controls.addView(access,margin(0,dp(6),0,dp(8)));
        Button system=button(I18n.t(this,"developer_taps"),false);
        system.setOnClickListener(v->{
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}
        });
        controls.addView(system,margin(0,0,0,dp(10)));

        controls.addView(text(I18n.t(this,"size"),12,Color.rgb(45,55,73),true));
        SeekBar size=new SeekBar(this); size.setMax(60); size.setProgress(prefs.getInt("tap_size",34)-18);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int v,boolean u){prefs.edit().putInt("tap_size",v+18).apply();pv.invalidate();}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        controls.addView(size);
        controls.addView(text(I18n.t(this,"opacity"),12,Color.rgb(45,55,73),true),margin(0,dp(7),0,0));
        SeekBar opacity=new SeekBar(this); opacity.setMax(100); opacity.setProgress(prefs.getInt("tap_opacity",90));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int v,boolean u){prefs.edit().putInt("tap_opacity",Math.max(5,v)).apply();pv.invalidate();}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        controls.addView(opacity);
        controls.addView(text("MÀU DẤU CHẠM",12,Color.rgb(45,55,73),true),margin(0,dp(8),0,dp(4)));
        LinearLayout colors=row();
        String[] hex={"#FFFFFF","#2F80FF","#FF5C70","#1ACB8A","#111827"};
        for(String c:hex){
            View swatch=new View(this); swatch.setBackground(round(Color.parseColor(c),dp(24)));
            swatch.setOnClickListener(v->{prefs.edit().putString("tap_color",c).apply();pv.invalidate();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(34),dp(34)); lp.setMargins(0,0,dp(10),0); colors.addView(swatch,lp);
        }
        controls.addView(colors);
        controls.addView(text(I18n.t(this,"effect"),12,Color.rgb(45,55,73),true),margin(0,dp(9),0,0));
        Spinner effect=spinner(new String[]{"Sóng tròn","Phóng to","Gợn sóng"});
        String current=prefs.getString("tap_effect","wave");
        effect.setSelection("zoom".equals(current)?1:"ripple".equals(current)?2:0);
        effect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){prefs.edit().putString("tap_effect",pos==1?"zoom":pos==2?"ripple":"wave").apply();pv.invalidate();}
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        controls.addView(effect,margin(0,dp(5),0,dp(8)));
        Button image=button(I18n.t(this,"image"),false);
        image.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,REQ_IMAGE);});
        controls.addView(image);
        body.addView(controls,margin(0,0,0,dp(13)));
        body.addView(text("Lưu ý: dấu chạm tuỳ chỉnh dùng sự kiện nhấn trên thành phần giao diện và chỉ hiển thị gần tâm vùng được nhấn. Một số trò chơi, canvas, video hoặc ứng dụng bảo mật không phát sự kiện này. Để có chấm chính xác ở mọi nơi, bật “Show taps” trong Tuỳ chọn nhà phát triển của Android.",11,Color.rgb(122,132,151),false),margin(2,0,2,dp(18)));
    }

    private void buildHistory() {
        ScrollView sc=scroll(); LinearLayout body=column(); sc.addView(body);
        pageHost.addView(sc,new LinearLayout.LayoutParams(-1,-1));
        body.addView(text(I18n.t(this,"history"),23,Color.rgb(25,31,45),true),margin(0,dp(4),0,dp(4)));
        body.addView(text("Video và âm thanh mới nhất được hiển thị trước.",12,Color.rgb(122,132,151),false),margin(0,0,0,dp(14)));
        int count=addMediaHistory(body,false)+addMediaHistory(body,true);
        if(count==0){
            LinearLayout empty=card(); empty.setPadding(dp(16),dp(24),dp(16),dp(24)); empty.setGravity(Gravity.CENTER);
            empty.addView(text("◷",30,Color.rgb(170,180,197),true));
            empty.addView(text(I18n.t(this,"empty_history"),14,Color.rgb(95,107,128),true),margin(0,dp(6),0,0));
            body.addView(empty);
        }
    }

    private int addMediaHistory(LinearLayout body,boolean audio) {
        Uri collection=audio?MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            :MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DURATION,MediaStore.MediaColumns.SIZE};
        String path=audio?Environment.DIRECTORY_MUSIC+"/Klyro/":Environment.DIRECTORY_MOVIES+"/Klyro/";
        int count=0;
        try(Cursor c=getContentResolver().query(collection,projection,MediaStore.MediaColumns.RELATIVE_PATH+"=?",new String[]{path},MediaStore.MediaColumns.DATE_ADDED+" DESC")){
            if(c==null)return 0;
            while(c.moveToNext()){
                long id=c.getLong(0); String name=c.getString(1); long date=c.getLong(2)*1000L;
                long duration=c.isNull(3)?0:c.getLong(3); long bytes=c.isNull(4)?0:c.getLong(4);
                Uri uri=ContentUris.withAppendedId(collection,id);
                LinearLayout card=card(); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12),dp(12),dp(12),dp(10));
                LinearLayout top=row();
                TextView icon=text(audio?"♫":"▣",20,Color.rgb(42,105,236),true);
                top.addView(icon);
                LinearLayout details=column();
                String dt=new SimpleDateFormat("dd/MM/yyyy  •  HH:mm",Locale.getDefault()).format(new Date(date));
                details.addView(text(dt,13,Color.rgb(31,41,60),true));
                details.addView(text(name,10,Color.rgb(125,135,154),false),margin(0,dp(3),0,0));
                
                top.addView(details,new LinearLayout.LayoutParams(0,-2,1));
                top.addView(text(formatBytes(bytes),10,Color.rgb(99,110,131),true));
                card.addView(top);
                card.addView(text(formatDuration(duration)+"   •   "+(audio?"Âm thanh":"Video"),10,Color.rgb(125,135,154),false),margin(0,dp(7),0,dp(6)));
                LinearLayout actions=row();
                Button play=smallButton("▶  Phát");
                play.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uri,audio?"audio/mp4":"video/mp4");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(i);}catch(Exception e){Toast.makeText(this,"Không tìm thấy trình phát phù hợp",Toast.LENGTH_SHORT).show();}});
                Button share=smallButton("↗  Chia sẻ");
                share.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType(audio?"audio/mp4":"video/mp4");i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Chia sẻ bản ghi"));});
                Button delete=smallButton("×");
                delete.setOnClickListener(v->{new android.app.AlertDialog.Builder(this).setMessage("Xoá bản ghi này?").setNegativeButton("Huỷ",null).setPositiveButton("Xoá",(d,w)->{getContentResolver().delete(uri,null,null);showPage(1);}).show();});
                actions.addView(play,new LinearLayout.LayoutParams(0,dp(36),1)); actions.addView(space(dp(6)));
                actions.addView(share,new LinearLayout.LayoutParams(0,dp(36),1)); actions.addView(space(dp(6)));
                actions.addView(delete,new LinearLayout.LayoutParams(dp(42),dp(36)));
                card.addView(actions);
                body.addView(card,margin(0,0,0,dp(9)));
                count++;
            }
        }catch(Exception ignored){}
        return count;
    }

    private class TapPreview extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler h=new Handler();
        private float phase;
        private Bitmap customBitmap;
        TapPreview(Context c){
            super(c);
            try {
                String selected=prefs.getString("tap_image", "");
                if(!selected.isEmpty()) try(java.io.InputStream in=getContentResolver().openInputStream(Uri.parse(selected))) { customBitmap=BitmapFactory.decodeStream(in); }
            } catch(Exception ignored) { customBitmap=null; }
            post(new Runnable(){public void run(){phase=(phase+.045f)%1f;invalidate();h.postDelayed(this,32);}});
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            paint.setColor(Color.rgb(247,249,253)); paint.setStyle(Paint.Style.FILL); c.drawRoundRect(new RectF(0,0,getWidth(),getHeight()),dp(12),dp(12),paint);
            int color; try{color=Color.parseColor(prefs.getString("tap_color","#FFFFFF"));}catch(Exception e){color=Color.WHITE;}
            int alpha=Math.max(12,Math.min(255,prefs.getInt("tap_opacity",90)*255/100));
            float r=dp(prefs.getInt("tap_size",34))*.62f;
            paint.setColor(color); paint.setAlpha(alpha); paint.setStyle(Paint.Style.FILL);
            if(customBitmap!=null){
                Path clip=new Path(); clip.addCircle(cx,cy,r,Path.Direction.CW);
                c.save(); c.clipPath(clip); c.drawBitmap(customBitmap,null,new RectF(cx-r,cy-r,cx+r,cy+r),paint); c.restore();
            } else c.drawCircle(cx,cy,r,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setAlpha((int)(alpha*(1-phase)));
            String eff=prefs.getString("tap_effect","wave");
            float radius="zoom".equals(eff)?r*(1+phase):r*(1.2f+phase*2.0f);
            c.drawCircle(cx,cy,radius,paint);
            if(!"zoom".equals(eff)){paint.setAlpha((int)(alpha*(.6f-phase*.5f)));c.drawCircle(cx,cy,r*(1.8f+phase),paint);}
        }
    }

    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(){LinearLayout l=column();l.setBackground(round(Color.WHITE,dp(17)));l.setElevation(dp(1));return l;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private LinearLayout.LayoutParams margin(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(l,t,r,b);return p;}
    private View space(int width){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(width,1));return v;}
    private Switch switcher(String title,boolean checked){Switch s=new Switch(this);s.setText(title);s.setTextSize(13);s.setTextColor(Color.rgb(39,49,68));s.setChecked(checked);s.setPadding(0,dp(9),0,dp(9));return s;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?Color.WHITE:Color.rgb(42,101,218));b.setBackground(round(primary?Color.rgb(42,105,236):Color.rgb(235,242,255),dp(14)));b.setPadding(dp(14),dp(7),dp(14),dp(7));return b;}
    private Button smallButton(String label){Button b=new Button(this);b.setText(label);b.setTextSize(10);b.setAllCaps(false);b.setMinHeight(0);b.setMinimumHeight(0);b.setTextColor(Color.rgb(46,98,190));b.setBackground(round(Color.rgb(239,244,252),dp(10)));b.setPadding(dp(5),0,dp(5),0);return b;}
    private Spinner spinner(String[] items){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,items);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);return s;}
    private String resolutionLabel(int index){String[] a={"Tự động","480p","720p","1080p","1440p","4K"};return a[Math.min(a.length-1,index)];}
    private String fpsLabel(int index){int[] a={30,24,30,60,90,120};return String.valueOf(a[Math.min(a.length-1,index)]);}
    private String bitrateLabel(int index){String[] a={"1 Mbps","2 Mbps","4 Mbps","8 Mbps","12 Mbps"};return a[Math.min(a.length-1,index)];}
    private String formatDuration(long ms){long s=Math.max(0,ms/1000);return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60);}
    private String formatBytes(long bytes){if(bytes<1024)return bytes+" B";double mb=bytes/1048576.0;if(mb<1024)return String.format(Locale.getDefault(),"%.0f MB",mb);return String.format(Locale.getDefault(),"%.2f GB",mb/1024.0);}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
