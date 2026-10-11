package de.felix.jarvis;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Boss logs in on EduPage's own login page. Jarvis never sees the password; it only keeps the
 * resulting session cookie (encrypted) to read the timeline with the Boss' own permissions.
 */
public class EduLoginActivity extends Activity {
    private TextView hint;
    private boolean done;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String sub = Cfg.get(this, "edu_subdomain");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        hint = Ui.text(this, "Melde dich mit deinem EduPage-Konto an. Jarvis speichert nur die Sitzung, nicht dein Passwort.", 14, Ui.MUTED);
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        root.setBackgroundColor(Ui.BG);
        root.addView(hint);
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { check(url); }
            @Override public void onPageFinished(WebView v, String url) { check(url); }
        });
        root.addView(w, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        w.loadUrl("https://" + sub + ".edupage.org/login/");
    }

    private void check(String url) {
        if (done || url == null) return;
        String sub = Cfg.get(this, "edu_subdomain");
        // after a successful login EduPage redirects to /user/ (or the dashboard)
        if (url.startsWith("https://" + sub + ".edupage.org/user") || url.startsWith("https://" + sub + ".edupage.org/dashboard")) {
            CookieManager.getInstance().flush();
            String cookies = CookieManager.getInstance().getCookie("https://" + sub + ".edupage.org/");
            if (cookies != null && cookies.contains("PHPSESSID")) {
                done = true;
                Secrets.put(this, Secrets.EDU_COOKIES, cookies);
                Db.get(this).kvSet("edu_login_at", System.currentTimeMillis());
                Db.get(this).kvSet("edu_session_state", "ok");
                Db.get(this).log("I", "edupage", "EduPage-Anmeldung gespeichert (Sitzung, kein Passwort)");
                hint.setText("Angemeldet. Jarvis prüft EduPage jetzt.");
                JarvisService.pokeSchool(this);
                hint.postDelayed(this::finish, 1200);
            }
        }
    }
}
