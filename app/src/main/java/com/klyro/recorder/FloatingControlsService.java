package com.klyro.recorder;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingControlsService extends Service {
    private WindowManager wm;
    private LinearLayout panel;
    private TextView timer;
    private TextView pause;
    private WindowManager.LayoutParams params;
    private boolean expanded = true;
    private boolean isPaused;
    private float downX, downY, viewX, viewY;
    private long downTime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable fade = () -> { if (panel != null) panel.animate().alpha(0.10f).setDuration(350).start(); };
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (RecordingService.ACTION_TICK.equals(i.getAction())) {
                long ms = i.getLongExtra("elapsed", 0);
                isPaused = i.getBooleanExtra("paused", false);
                if (timer != null) timer.setText(format(ms));
                if (pause != null) pause.setText(isPaused ? "▶" : "Ⅱ");
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(this)) { stopSelf(); return; }
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        createPanel();
        IntentFilter f = new IntentFilter(RecordingService.ACTION_TICK);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, f);
        handler.postDelayed(fade, 3000);
    }
    private void createPanel() {
        panel = new LinearLayout(this);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(10), dp(7), dp(10), dp(7));
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackground(round(Color.rgb(20,24,34), dp(28)));
        timer = new TextView(this);
        timer.setText("00:00");
        timer.setTextColor(Color.WHITE);
        timer.setTextSize(14);
        timer.setTypeface(Typeface.DEFAULT_BOLD);
        timer.setPadding(dp(4),0,dp(8),0);
        pause = action("Ⅱ");
        TextView stop = action("■");
        pause.setOnClickListener(v -> {
            Intent i = new Intent(this, RecordingService.class);
            i.setAction(isPaused ? RecordingService.ACTION_RESUME : RecordingService.ACTION_PAUSE);
            startService(i);
            isPaused = !isPaused;
            pause.setText(isPaused ? "▶" : "Ⅱ");
            wake();
        });
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, RecordingService.class);
            i.setAction(RecordingService.ACTION_STOP);
            startService(i);
            stopSelf();
        });
        panel.addView(timer);
        panel.addView(pause);
        panel.addView(stop);
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = dp(16);
        params.y = dp(120);
        panel.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    viewX = params.x; viewY = params.y; downTime = System.currentTimeMillis();
                    wake(); return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    params.x = (int)(viewX + dx); params.y = (int)(viewY + dy);
                    try { wm.updateViewLayout(panel, params); } catch (Exception ignored) {}
                    wake(); return true;
                case MotionEvent.ACTION_UP:
                    float moved = Math.abs(event.getRawX()-downX) + Math.abs(event.getRawY()-downY);
                    if (moved < dp(12) && System.currentTimeMillis()-downTime < 300) {
                        expanded = !expanded;
                        pause.setVisibility(expanded ? View.VISIBLE : View.GONE);
                    }
                    snapToCorner(); wake(); return true;
            }
            return false;
        });
        try { wm.addView(panel, params); } catch (Exception e) { stopSelf(); }
    }
    private TextView action(String glyph) {
        TextView t = new TextView(this);
        t.setText(glyph); t.setTextColor(Color.WHITE); t.setTextSize(17);
        t.setGravity(Gravity.CENTER); t.setBackground(round(Color.rgb(47,56,75),dp(22)));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(34),dp(34)));
        return t;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    private void wake() {
        handler.removeCallbacks(fade);
        if (panel != null) panel.animate().alpha(1f).setDuration(120).start();
        handler.postDelayed(fade, 3000);
    }
    private void snapToCorner() {
        if (panel == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int w = panel.getWidth(), h = panel.getHeight();
        boolean right = params.x + w/2 > sw/2;
        boolean bottom = params.y + h/2 > sh/2;
        params.x = right ? Math.max(0, sw-w-dp(8)) : dp(8);
        params.y = bottom ? Math.max(0, sh-h-dp(30)) : dp(35);
        try { wm.updateViewLayout(panel, params); } catch (Exception ignored) {}
    }
    private int dp(float v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    private String format(long ms) {
        long sec=ms/1000; return String.format(java.util.Locale.US,"%02d:%02d",sec/60,sec%60);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(updates); } catch (Exception ignored) {}
        if (wm != null && panel != null) try { wm.removeView(panel); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
