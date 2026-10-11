package de.felix.jarvis;

/** Short spoken names for EduPage subjects. */
final class SchoolParserFriendly {
    private SchoolParserFriendly() {}

    static String name(String s) {
        if (s == null) return "?";
        String n = s.trim();
        if (n.equalsIgnoreCase("Mathematik")) return "Mathe";
        if (n.startsWith("Sport")) return "Sport";
        if (n.startsWith("Religion")) return "Religion";
        return n;
    }
}
