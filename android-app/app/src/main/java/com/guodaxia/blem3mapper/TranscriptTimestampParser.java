package com.guodaxia.blem3mapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a timestamp at the start of a transcript row's text/accessibility description. */
final class TranscriptTimestampParser {
    private static final String SPACE = "[\\s\\p{Z}]*";
    private static final String START = "^" + SPACE + "(?=[0-9])";
    private static final String SEPARATOR = SPACE + "(?:[,，]" + SPACE + ")?";
    // A colon or decimal point is deliberately not a boundary: malformed/partial
    // clocks such as 1:23:456 and 1:23.5 must not become a plausible 1:23 row.
    private static final String END = "(?=$|[\\s\\p{Z},，、;；。!！?？])";
    private static final Pattern CLOCK = Pattern.compile(
            START + "([0-9]+):([0-9]{2})(?::([0-9]{2}))?" + END);
    private static final Pattern CHINESE = Pattern.compile(
            START + "(?:([0-9]+)" + SPACE + "小时)?"
                    + "(?:" + SEPARATOR + "([0-9]+)" + SPACE + "分钟)?"
                    + "(?:" + SEPARATOR + "([0-9]+)" + SPACE + "秒(?:钟)?)?" + END);
    private static final String ENGLISH_SEPARATOR = SEPARATOR + "(?:and[\\s\\p{Z}]+)?";
    private static final Pattern ENGLISH = Pattern.compile(
            START + "(?:([0-9]+)" + SPACE + "hours?)?"
                    + "(?:" + ENGLISH_SEPARATOR + "([0-9]+)" + SPACE + "minutes?)?"
                    + "(?:" + ENGLISH_SEPARATOR + "([0-9]+)" + SPACE + "seconds?)?" + END,
            Pattern.CASE_INSENSITIVE);

    private TranscriptTimestampParser() { }

    static long parsePrefix(String value) {
        if (value == null || value.isEmpty()) return -1;
        try {
            Matcher clock = CLOCK.matcher(value);
            if (clock.find()) {
                long first = Long.parseLong(clock.group(1));
                long second = Long.parseLong(clock.group(2));
                if (clock.group(3) == null) return second < 60 ? milliseconds(0, first, second) : -1;
                long third = Long.parseLong(clock.group(3));
                return second < 60 && third < 60 ? milliseconds(first, second, third) : -1;
            }
            Matcher units = CHINESE.matcher(value);
            if (!units.find()) {
                units = ENGLISH.matcher(value);
                if (!units.find()) return -1;
            }
            // The patterns allow omitted components, including whole minutes/hours.
            if (units.group(1) == null && units.group(2) == null && units.group(3) == null) return -1;
            return milliseconds(part(units.group(1)), part(units.group(2)), part(units.group(3)));
        } catch (NumberFormatException | ArithmeticException invalid) {
            return -1;
        }
    }

    private static long part(String value) { return value == null ? 0 : Long.parseLong(value); }

    private static long milliseconds(long hours, long minutes, long seconds) {
        long totalMinutes = Math.addExact(Math.multiplyExact(hours, 60), minutes);
        long totalSeconds = Math.addExact(Math.multiplyExact(totalMinutes, 60), seconds);
        return Math.multiplyExact(totalSeconds, 1000);
    }
}
