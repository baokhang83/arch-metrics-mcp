package com.github.baokhang83.analysis;

/**
 * Classifies a package into a Main Sequence zone based on its Abstractness (A)
 * and Instability (I) values, per Robert C. Martin's Zone model.
 *
 * <ul>
 *   <li><b>Zone of Pain</b>:        A &lt; 0.5 AND I &lt; 0.5 — concrete &amp; stable,
 *       hard to change without breaking callers.</li>
 *   <li><b>Zone of Uselessness</b>: A &gt; 0.5 AND I &gt; 0.5 — abstract &amp; unstable,
 *       no one depends on it so the abstraction is pointless.</li>
 *   <li><b>Main Sequence</b>:       everything else — on or near the ideal A + I = 1 line.</li>
 * </ul>
 */
public final class ZoneClassifier {

    private ZoneClassifier() {}

    public enum Zone {
        MAIN_SEQUENCE,
        ZONE_OF_PAIN,
        ZONE_OF_USELESSNESS
    }

    /**
     * Returns the zone for a package with the given Abstractness {@code a}
     * and Instability {@code i}.
     */
    public static Zone classify(double a, double i) {
        if (a < 0.5 && i < 0.5) return Zone.ZONE_OF_PAIN;
        if (a > 0.5 && i > 0.5) return Zone.ZONE_OF_USELESSNESS;
        return Zone.MAIN_SEQUENCE;
    }

    /**
     * Returns an emoji-prefixed label string suitable for table output, e.g.
     * {@code "✅ Main Sequence"}, {@code "🔴 Zone of Pain"}.
     *
     * @param a  abstractness
     * @param i  instability
     * @param d  distance from main sequence (|A + I − 1|)
     */
    public static String label(double a, double i, double d) {
        return switch (classify(a, i)) {
            case ZONE_OF_PAIN        -> "🔴 Zone of Pain";
            case ZONE_OF_USELESSNESS -> "🔴 Zone of Uselessness";
            case MAIN_SEQUENCE       -> (d <= 0.3 ? "✅" : d <= 0.5 ? "🟡" : "🟠") + " Main Sequence";
        };
    }
}
