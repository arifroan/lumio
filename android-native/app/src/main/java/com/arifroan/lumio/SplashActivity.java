package com.arifroan.lumio;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueAnimator pulse;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8,11,24));
        getWindow().setNavigationBarColor(Color.rgb(8,11,24));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setBackgroundColor(Color.rgb(8,11,24));

        LogoView logo = new LogoView();
        screen.addView(logo, new LinearLayout.LayoutParams(dp(156), dp(156)));

        TextView name = new TextView(this);
        name.setText("Lumio");
        name.setTextColor(Color.WHITE);
        name.setTextSize(38);
        name.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        name.setGravity(Gravity.CENTER);
        name.setLetterSpacing(-0.025f);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
        nameParams.topMargin = dp(18);
        screen.addView(name, nameParams);

        TextView tagline = new TextView(this);
        tagline.setText("YOUR MEDIA. YOUR SPACE.");
        tagline.setTextColor(Color.rgb(151,163,194));
        tagline.setTextSize(11);
        tagline.setLetterSpacing(0.20f);
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
        tagParams.topMargin = dp(8);
        screen.addView(tagline, tagParams);
        setContentView(screen);

        pulse = ValueAnimator.ofFloat(0.88f, 1.04f, 0.88f);
        pulse.setDuration(1350);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            logo.setScaleX(s); logo.setScaleY(s);
            logo.setGlow(0.65f + (s - 0.88f) * 2.2f);
        });
        pulse.start();
        handler.postDelayed(() -> {
            if (pulse != null) pulse.cancel();
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 1900);
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private final class LogoView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float glow = 1f;
        LogoView() { super(SplashActivity.this); }
        void setGlow(float value) { glow = value; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight(), cx=w/2f, cy=h/2f;
            p.setShader(new RadialGradient(cx,cy,w*0.48f,
                    new int[]{Color.argb((int)(70*glow),102,50,255),Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,w*0.49f,p); p.setShader(null);
            c.save(); c.translate(cx-w*0.34f,cy-h*0.38f);
            float u=w/108f; c.scale(u,u);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(20,20,88,88,
                    new int[]{Color.rgb(21,211,255),Color.rgb(133,48,255),Color.rgb(255,62,164),Color.rgb(255,183,42)},
                    null,Shader.TileMode.CLAMP));
            android.graphics.Path outer=new android.graphics.Path();
            outer.moveTo(27,10); outer.cubicTo(20,7,14,12,14,22); outer.lineTo(14,84);
            outer.cubicTo(14,94,21,98,30,92); outer.lineTo(88,60);
            outer.cubicTo(97,55,97,49,88,44); outer.close(); c.drawPath(outer,p);
            p.setShader(null); p.setColor(Color.rgb(40,22,184));
            android.graphics.Path inner=new android.graphics.Path();
            inner.moveTo(39,31); inner.cubicTo(35,27,32,31,32,37); inner.lineTo(32,70);
            inner.cubicTo(32,76,36,78,41,74); inner.lineTo(65,58);
            inner.cubicTo(70,55,70,51,65,48); inner.close(); c.drawPath(inner,p);
            c.restore();
        }
    }
}