package de.felix.jarvis.call;

import android.app.Application;

/** Remembers the cause of a crash and sends it to Jarvis (Jarvis phone), so it can be fixed without a cable. */
public class CallApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String trace = "Thread " + t.getName() + ": " + sw;
                getSharedPreferences("call", MODE_PRIVATE).edit().putString("last_crash", trace.substring(0, Math.min(2400, trace.length())))
                        .putString("last_crash_at", String.valueOf(System.currentTimeMillis())).commit();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
