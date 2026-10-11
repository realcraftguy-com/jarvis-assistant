package de.felix.jarvis.shared;

import java.time.LocalTime;

/** Default school period times (read from an EduPage timetable); replaced by the real ones once the timetable was loaded. */
public final class BellTimes {
    private static String[][] periods = {
            {"08:00", "08:45"}, {"08:45", "09:30"}, {"09:45", "10:30"}, {"10:30", "11:15"}, {"11:30", "12:15"},
            {"12:15", "13:00"}, null /* 7th: not in the timetable (lunch break) */, {"14:00", "14:45"}, {"14:45", "15:30"}};

    private BellTimes() {}

    public static synchronized void set(String[][] p) { if (p != null && p.length > 0) periods = p; }

    /** 1-based period number -> {start, end} or null. */
    public static synchronized LocalTime[] period(int n) {
        if (n < 1 || n > periods.length || periods[n - 1] == null) return null;
        return new LocalTime[]{LocalTime.parse(periods[n - 1][0]), LocalTime.parse(periods[n - 1][1])};
    }
}
