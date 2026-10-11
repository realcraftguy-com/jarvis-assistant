import de.felix.jarvis.shared.SchoolParser;
import de.felix.jarvis.shared.SchoolParser.*;
import java.time.LocalDate;
import java.time.LocalTime;

/** Plain-JVM tests for the school parser: java tests/src/ParserTest.java (see tools/test.sh). */
public class ParserTest {
    static int fail = 0, ok = 0;

    static void check(String name, boolean cond, Object info) {
        if (cond) ok++; else { fail++; System.out.println("FAIL " + name + " -> " + info); }
    }

    static Result a(String text, String pub) { return SchoolParser.analyze(text, LocalDate.parse(pub), null); }

    public static void main(String[] args) {
        // 1. event vs. separate deadline (spec example)
        Result r = a("Liebe Eltern, am 20. Oktober machen wir einen Ausflug in den Tierpark Hellabrunn. Bitte den unterschriebenen Zettel bis zum 14. Oktober abgeben.", "2026-10-08");
        check("1 category", r.category == Category.EXCURSION, r.category);
        check("1 event", r.firstEvent() != null && r.firstEvent().date.equals(LocalDate.of(2026, 10, 20)), r.dates);
        check("1 deadline", r.deadlines().size() == 1 && r.deadlines().get(0).date.equals(LocalDate.of(2026, 10, 14)), r.dates);
        check("1 deadline label", r.deadlines().size() == 1 && r.deadlines().get(0).roleCue.equals("Zettelabgabe"), r.dates);
        check("1 location", "Tierpark Hellabrunn".equals(r.location), r.location);
        check("1 no problems", r.problems.isEmpty(), r.problems);

        // 2. relative date relative to the original message
        r = a("Morgen schreiben wir in Mathe einen LN über lineare Funktionen.", "2026-10-21");
        check("2 exam", r.category == Category.EXAM && "Mathe".equals(r.subject) && "Leistungsnachweis".equals(r.kindLabel), r.category + " " + r.subject + " " + r.kindLabel);
        check("2 date", r.firstEvent() != null && r.firstEvent().date.equals(LocalDate.of(2026, 10, 22)), r.dates);

        // 3. vague -> problem, no event
        r = a("Der Englisch-Test findet nächste Woche statt.", "2026-10-09");
        check("3 vague problem", !r.problems.isEmpty() && r.firstEvent() == null, r.problems + " " + r.dates);

        // 4. contradicting weekday -> problem
        r = a("Die Schulaufgabe in Deutsch ist am Montag, 20.10.", "2026-10-09");
        check("4 weekday conflict", !r.problems.isEmpty() && r.firstEvent() == null, r.problems + " " + r.dates);

        // 5. multi-day trip
        r = a("Das Schullandheim findet vom 12. bis 16. April 2027 in Wartaweil statt.", "2026-10-09");
        check("5 trip", r.category == Category.TRIP && r.firstEvent() != null
                && r.firstEvent().date.equals(LocalDate.of(2027, 4, 12)) && r.firstEvent().endDate.equals(LocalDate.of(2027, 4, 16)), r.dates);

        // 6. cancellation
        r = a("Die Schulaufgabe in Englisch am 05.11. entfällt.", "2026-10-09");
        check("6 cancel", r.cancelled && r.category == Category.CANCELLATION, r.category);
        check("6 date", r.dates.size() == 1 && r.dates.get(0).date.equals(LocalDate.of(2026, 11, 5)), r.dates);

        // 7. moved
        r = a("Der Wandertag wird auf den 23.10. verschoben.", "2026-10-09");
        check("7 moved", r.moved && r.firstEvent() != null && r.firstEvent().date.equals(LocalDate.of(2026, 10, 23)), r.dates);

        // 8. time + location
        r = a("Elternabend am 3.11. um 19:00 Uhr in der Aula.", "2026-10-09");
        check("8 time", r.firstEvent() != null && LocalTime.of(19, 0).equals(r.firstEvent().start), r.dates);
        check("8 location", "Aula".equals(r.location), r.location);

        // 9. school period
        r = a("LN in Physik am Donnerstag, 22.10.2026 in der 3. Stunde.", "2026-10-09");
        check("9 period", r.firstEvent() != null && LocalTime.of(9, 45).equals(r.firstEvent().start) && LocalTime.of(10, 30).equals(r.firstEvent().end), r.dates);

        // 10. publication date is not an event; "Guten Morgen" is not a date
        r = a("Guten Morgen! Bitte denkt an eure Sportsachen.", "2026-10-09");
        check("10 no dates", r.dates.isEmpty() && r.category == Category.INFO, r.dates);

        // 11. year rollover: January message published in December
        r = a("Die Schulaufgabe in Mathe ist am 14.01.", "2026-12-15");
        check("11 rollover", r.firstEvent() != null && r.firstEvent().date.equals(LocalDate.of(2027, 1, 14)), r.dates);

        // 12. two unrelated dates in an exam message -> ask
        r = a("Mathe-Schulaufgabe: 12.11. oder 19.11., wird noch festgelegt.", "2026-10-09");
        check("12 ambiguous", r.firstEvent() == null || !r.problems.isEmpty(), r.dates + " " + r.problems);

        // 13. registration deadline only
        r = a("Anmeldung für die Theater-AG bis spätestens 30.10.", "2026-10-09");
        check("13 deadline", r.deadlines().size() == 1 && r.deadlines().get(0).roleCue.equals("Anmeldung"), r.dates);

        // 14. "nächsten Mittwoch" said on Monday -> ambiguous
        r = a("Den Vokabeltest schreiben wir nächsten Mittwoch.", "2026-10-12");
        check("14 next wd ambiguous", r.firstEvent() == null && !r.problems.isEmpty(), r.dates + " " + r.problems);

        // 15. "am Freitag" on a Monday -> coming Friday
        r = a("Am Freitag schreiben wir einen Test in Biologie.", "2026-10-12");
        check("15 weekday", r.firstEvent() != null && r.firstEvent().date.equals(LocalDate.of(2026, 10, 16)) && "Biologie".equals(r.subject), r.dates + " " + r.subject);

        // 16. time range
        r = a("Exkursion ins Deutsche Museum am 6.11. von 8:00 bis 13:30 Uhr, Treffpunkt Ostbahnhof.", "2026-10-09");
        check("16 range", r.firstEvent() != null && LocalTime.of(8, 0).equals(r.firstEvent().start) && LocalTime.of(13, 30).equals(r.firstEvent().end), r.dates);

        // 17. wrong calendar date
        r = a("Test am 31.11.", "2026-10-09");
        check("17 invalid date", r.firstEvent() == null && !r.problems.isEmpty(), r.dates);

        // 18. moved with old and new date
        r = a("Der Wandertag wird vom 20.10. auf den 23.10. verschoben.", "2026-10-09");
        check("18 moved new date", r.moved && r.firstEvent() != null && r.firstEvent().date.equals(LocalDate.of(2026, 10, 23))
                && r.previousDate() != null && r.previousDate().date.equals(LocalDate.of(2026, 10, 20)) && r.problems.isEmpty(), r.dates + " " + r.problems);

        // 19. trip with deadline for payment and registration
        r = a("Klassenfahrt nach Berlin vom 15.03.2027 bis 19.03.2027. Anmeldung bis 01.11.2026, die Anzahlung von 50 Euro bitte bis 15.11. überweisen.", "2026-10-09");
        check("19 trip", r.category == Category.TRIP && r.firstEvent() != null && r.firstEvent().endDate != null, r.dates);
        check("19 two deadlines", r.deadlines().size() == 2, r.dates);

        System.out.println("Parser tests: " + ok + " ok, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
