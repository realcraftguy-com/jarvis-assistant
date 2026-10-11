package de.felix.jarvis.call;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The call screen: ringing (answer/decline), connecting, and the active call. */
public class InCallActivity extends Activity implements CallSession.Listener {
    private CallSession s;
    private TextView name, sub, timer, transcript;
    private ImageView avatar;
    private LinearLayout controls, bottom;
    private ScrollView tScroll;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateTimer(); h.postDelayed(this, 1000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        s = CallSession.get(this);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0B1A24, 0xFF070B10});
        root.setBackground(bg);
        root.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, dp(64), 0, 0);
        TextView label = text("Jarvis-Anruf", 14, 0xFF8FB8CC);
        name = text("Jarvis", 38, 0xFFFFFFFF);
        name.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        sub = text("", 16, 0xFFB0BEC5);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(32), dp(4), dp(32), 0);
        timer = text("", 16, 0xFF8FB8CC);
        avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.avatar);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(150), dp(150));
        ap.topMargin = dp(36);
        col.addView(label);
        col.addView(name);
        col.addView(timer);
        col.addView(sub);
        col.addView(avatar, ap);

        tScroll = new ScrollView(this);
        transcript = text("", 15, 0xFFCFD8DC);
        transcript.setGravity(Gravity.START);
        transcript.setPadding(dp(28), dp(16), dp(28), dp(16));
        tScroll.addView(transcript);
        col.addView(tScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(0, 0, 0, dp(48));
        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        bottom.addView(controls);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottom, bp);
        setContentView(root);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    private void handleIntent(Intent i) {
        if (i != null && "bootstrap".equals(i.getAction())) {
            // only here to make the microphone usable; the phone app shows the call
            LinkService ls = LinkService.get();
            if (ls != null) ls.inCall(true);
            Vault.log(this, "Mikrofon für Telefon-App-Anruf freigegeben (" + (ls != null && ls.micReady()) + ")");
            h.postDelayed(this::finish, 600);
            return;
        }
        if (i != null && "answer".equals(i.getAction())) answer();
        if (i != null && "dial".equals(i.getAction())) dial();
    }

    @Override protected void onResume() {
        super.onResume();
        s.listen(this);
        render();
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        s.unlisten(this);
        h.removeCallbacks(tick);
    }

    @Override public void onCallChanged() { render(); }

    private void dial() {
        if (!micOk()) return;
        LinkService.start(this);
        LinkService ls = LinkService.get();
        if (ls != null) ls.inCall(true);
        s.startOutgoing();
    }

    private void answer() {
        if (!micOk()) return;
        LinkService ls = LinkService.get();
        if (ls != null) ls.inCall(true);
        s.answer();
    }

    private boolean micOk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
        return false;
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            if (s.state == CallSession.State.RINGING) answer(); else if (s.state == CallSession.State.IDLE) dial();
        }
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        controls.removeAllViews();
        avatar.clearAnimation();
        switch (s.state) {
            case RINGING:
                sub.setText(s.reason == null || s.reason.isEmpty() ? "ruft an …" : s.reason);
                timer.setText("ruft an …");
                pulse();
                controls.addView(round(R.drawable.ic_end, 0xFFE53935, "Ablehnen", v -> s.decline()));
                controls.addView(spacer(dp(96)));
                controls.addView(round(R.drawable.ic_call, 0xFF43A047, "Annehmen", v -> answer()));
                break;
            case DIALING:
                sub.setText(s.status);
                timer.setText("wählt …");
                pulse();
                controls.addView(round(R.drawable.ic_end, 0xFFE53935, "Auflegen", v -> s.hangup()));
                break;
            case ACTIVE:
                sub.setText(s.status);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER);
                row.addView(toggle(R.drawable.ic_mic, s.muted ? "Stumm ✓" : "Stumm", s.muted, v -> {
                    s.setMuted(!s.muted);
                    render();
                }));
                row.addView(spacer(dp(20)));
                row.addView(toggle(R.drawable.ic_speaker, "Laut", s.speaker, v -> s.route(!s.speaker)));
                row.addView(spacer(dp(20)));
                row.addView(toggle(R.drawable.ic_text, "Text", s.showText, v -> { s.showText = !s.showText; render(); }));
                bottom.removeView(row);
                controls.setOrientation(LinearLayout.VERTICAL);
                controls.addView(row);
                LinearLayout end = new LinearLayout(this);
                end.setGravity(Gravity.CENTER);
                end.setPadding(0, dp(28), 0, 0);
                end.addView(round(R.drawable.ic_end, 0xFFE53935, "Auflegen", v -> s.hangup()));
                controls.addView(end);
                break;
            case ENDED:
                sub.setText("Anruf beendet");
                timer.setText("");
                h.postDelayed(this::finish, 1200);
                break;
            default:
                finish();
                return;
        }
        if (s.state != CallSession.State.ACTIVE) controls.setOrientation(LinearLayout.HORIZONTAL);
        avatar.setVisibility(s.showText && s.state == CallSession.State.ACTIVE ? View.GONE : View.VISIBLE);
        tScroll.setVisibility(s.showText && s.state == CallSession.State.ACTIVE ? View.VISIBLE : View.GONE);
        if (s.showText) {
            StringBuilder t = new StringBuilder();
            synchronized (s.transcript) { for (String[] l : s.transcript) t.append(l[0]).append(": ").append(l[1]).append("\n\n"); }
            transcript.setText(t.toString().trim());
            tScroll.post(() -> tScroll.fullScroll(View.FOCUS_DOWN));
        }
        updateTimer();
    }

    private void updateTimer() {
        if (s.state == CallSession.State.ACTIVE && s.activeAt > 0) {
            long d = (System.currentTimeMillis() - s.activeAt) / 1000;
            timer.setText(String.format("%d:%02d", d / 60, d % 60));
        }
    }

    private void pulse() {
        AlphaAnimation a = new AlphaAnimation(1f, 0.45f);
        a.setDuration(900);
        a.setRepeatMode(Animation.REVERSE);
        a.setRepeatCount(Animation.INFINITE);
        avatar.startAnimation(a);
    }

    private LinearLayout round(int icon, int color, String label, View.OnClickListener on) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        b.setBackground(g);
        b.setOnClickListener(on);
        b.setContentDescription(label);
        l.addView(b, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView t = text(label, 13, 0xFFB0BEC5);
        t.setPadding(0, dp(8), 0, 0);
        l.addView(t);
        return l;
    }

    private LinearLayout toggle(int icon, String label, boolean on, View.OnClickListener l) {
        LinearLayout r = round(icon, on ? 0xFFECEFF1 : 0x33FFFFFF, label, l);
        if (on) ((ImageView) r.getChildAt(0)).setColorFilter(0xFF0B1A24);
        ((ImageView) r.getChildAt(0)).setLayoutParams(new LinearLayout.LayoutParams(dp(60), dp(60)));
        return r;
    }

    private View spacer(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
