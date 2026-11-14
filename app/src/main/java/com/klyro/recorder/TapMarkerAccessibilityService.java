package com.klyro.recorder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class TapMarkerAccessibilityService extends AccessibilityService {
    private WindowManager wm;
    private View marker;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap customBitmap;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 60;
            setServiceInfo(info);
        }
        loadBitmap();
    }

    private void loadBitmap() {
        try {
            SharedPreferences p = getSharedPreferences("klyro", MODE_PRIVATE);
            String s = p.getString("tap_image", "");
            if (!s.isEmpty()) {
                try (java.io.InputStream in = getContentResolver().openInputStream(Uri.parse(s))) {
                    customBitmap = BitmapFactory.decodeStream(in);
                }
            }
        } catch (Exception ignored) { customBitmap = null; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        SharedPreferences p = getSharedPreferences("klyro", MODE_PRIVATE);
        if (!p.getBoolean("tap_enabled", false)) return;
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) return;
        Rect bounds = new Rect();
        try { node.getBoundsInScreen(bounds); } catch (Exception ignored) {}
        try { node.recycle(); } catch (Exception ignored) {}
        if (bounds.isEmpty()) return;
        showAt(bounds.centerX(), bounds.centerY(), p);
    }

    private void showAt(int cx, int cy, SharedPreferences p) {
        handler.post(() -> {
            try {
                if (marker != null) { wm.removeView(marker); marker = null; }
                int size = Math.max(18, p.getInt("tap_size", 34));
                int opacity = Math.min(100, Math.max(5, p.getInt("tap_opacity", 90)));
                int color;
                try { color = Color.parseColor(p.getString("tap_color", "#FFFFFF")); }
                catch (Exception e) { color = Color.WHITE; }
                String effect = p.getString("tap_effect", "wave");
                marker = new MarkerView(this, color, opacity/100f, effect, customBitmap);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        size*2, size*2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                lp.x = Math.max(0,cx-size); lp.y = Math.max(0,cy-size);
                wm.addView(marker, lp);
                handler.postDelayed(() -> {
                    if (marker != null) {
                        try { wm.removeView(marker); } catch (Exception ignored) {}
                        marker = null;
                    }
                }, 650);
            } catch (Exception ignored) {}
        });
    }

    private static class MarkerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final float alpha;
        private final String effect;
        private final Bitmap bitmap;
        private final long start = SystemClock.elapsedRealtime();
        MarkerView(Context c, int color, float alpha, String effect, Bitmap bitmap) {
            super(c); this.color=color; this.alpha=alpha; this.effect=effect; this.bitmap=bitmap;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float phase = ((SystemClock.elapsedRealtime()-start)%650)/650f;
            float cx=getWidth()/2f, cy=getHeight()/2f, r=Math.min(getWidth(),getHeight())*.23f;
            if (bitmap != null) {
                paint.setAlpha((int)(255*alpha)); paint.setStyle(Paint.Style.FILL);
                RectF dst = new RectF(cx-r,cy-r,cx+r,cy+r);
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(cx,cy,r,android.graphics.Path.Direction.CW);
                canvas.save(); canvas.clipPath(clip);
                canvas.drawBitmap(bitmap,null,dst,paint); canvas.restore();
            } else {
                paint.setColor(color); paint.setAlpha((int)(255*alpha)); paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx,cy,r,paint);
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(2f,r*.12f));
            if ("zoom".equals(effect)) {
                float scale=1f+phase*.85f;
                paint.setAlpha((int)(150*alpha*(1-phase)));
                canvas.drawCircle(cx,cy,r*scale,paint);
            } else {
                for (int j=0;j<2;j++) {
                    float p=(phase+j*.42f)%1f;
                    paint.setAlpha((int)(150*alpha*(1-p)));
                    canvas.drawCircle(cx,cy,r*(1.25f+p*1.7f),paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
            if (phase < .95f) postInvalidateDelayed(24);
        }
    }

    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (marker != null && wm != null) try { wm.removeView(marker); } catch (Exception ignored) {}
        if (customBitmap != null && !customBitmap.isRecycled()) customBitmap.recycle();
        super.onDestroy();
    }
}
