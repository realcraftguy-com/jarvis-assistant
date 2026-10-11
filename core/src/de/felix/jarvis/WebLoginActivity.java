package de.felix.jarvis;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Any school platform: the user logs in on the platform's own page (Jarvis never sees the password, the browser
 * keeps the session), opens the page with news/dates and taps "Diese Seite beobachten". Jarvis then checks
 * that the page is readable.
 */
public class WebLoginActivity extends Activity {
    private WebView w;
    private TextView hint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url = getIntent().getStringExtra("url");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Ui.BG);
        hint = Ui.text(this, "1. Melde dich an (Jarvis sieht dein Passwort nicht).\n2. Öffne die Seite mit Neuigkeiten, Terminen oder Hausaufgaben.\n3. Tippe auf „Diese Seite beobachten“.", 14, Ui.MUTED);
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 8));
        root.addView(hint);
        w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        w.setWebViewClient(new WebViewClient());
        root.addView(w, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = Ui.hbox(this);
        bar.addView(Ui.button(this, "Diese Seite beobachten", Ui.ACCENT, v -> watch()));
        bar.addView(Ui.button(this, "Fertig", Ui.MUTED, v -> finish()));
        root.addView(bar);
        setContentView(root);
        w.loadUrl(url == null ? "about:blank" : url);
    }

    private void watch() {
        String url = w.getUrl();
        if (url == null || !url.startsWith("http")) return;
        CookieManager.getInstance().flush();
        WebSchool.addPage(this, url);
        Cfg.set(this, "school_enabled", "1");
        Db.get(this).log("I", "schule-web", "Beobachte " + WebSchool.host(url));
        hint.setText("Prüfe, ob Jarvis die Seite lesen kann …");
        new Thread(() -> {
            String r = WebSchool.test(this, url);
            runOnUiThread(() -> hint.setText(r));
        }).start();
    }

    @Override public void onBackPressed() {
        if (w.canGoBack()) w.goBack(); else super.onBackPressed();
    }
}
