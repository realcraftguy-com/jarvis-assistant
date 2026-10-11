package de.felix.jarvis;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny view helpers so the UI needs no XML layouts or support libraries. */
final class Ui {
    static final int BG = 0xFF0E1116, CARD = 0xFF171C24, TEXT = 0xFFE8ECF1, MUTED = 0xFF8A94A3,
            ACCENT = 0xFF4FC3F7, OK = 0xFF66BB6A, WARN = 0xFFFFB74D, BAD = 0xFFEF5350;

    private Ui() {}

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = text(c, s, 13, MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, dp(c, 18), 0, dp(c, 6));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(c, 14));
        l.setBackground(g);
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 8);
        l.setLayoutParams(lp);
        return l;
    }

    /** "● Label   value" status row. */
    static LinearLayout row(Context c, String label, String value, int dotColor) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(c, 3), 0, dp(c, 3));
        TextView dot = text(c, "●", 12, dotColor);
        dot.setPadding(0, dp(c, 1), dp(c, 8), 0);
        TextView l = text(c, label, 15, TEXT);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        TextView v = text(c, value, 14, MUTED);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(l);
        col.addView(v);
        r.addView(dot);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    static Button button(Context c, String s, int color, View.OnClickListener on) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFF0E1116);
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, 12));
        b.setBackground(g);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setOnClickListener(on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(c, 8);
        lp.topMargin = dp(c, 6);
        b.setLayoutParams(lp);
        return b;
    }

    static EditText input(Context c, String hint, String value, boolean secret) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(15);
        e.setSingleLine(!hint.startsWith("Nachricht"));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static String ago(long ms) {
        if (ms <= 0) return "noch nie";
        long s = (System.currentTimeMillis() - ms) / 1000;
        if (s < 60) return "vor " + s + " s";
        if (s < 3600) return "vor " + s / 60 + " min";
        if (s < 86400) return "vor " + s / 3600 + " h";
        return Brain.fmt(ms);
    }
}
