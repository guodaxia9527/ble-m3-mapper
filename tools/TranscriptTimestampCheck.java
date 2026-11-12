package com.guodaxia.blem3mapper;

/** Host-only regression examples; does not connect to or interact with a phone. */
public final class TranscriptTimestampCheck {
    private static int checks;

    private static void expect(String text, long expectedMs) {
        long actual = TranscriptTimestampParser.parsePrefix(text);
        if (actual != expectedMs) {
            throw new AssertionError("Text=" + text + " expected=" + expectedMs + " actual=" + actual);
        }
        checks++;
    }

    public static void main(String[] args) {
        expect("3分钟5秒钟，字幕内容", 185000);
        expect("3分钟5秒，字幕内容", 185000);
        expect("3分钟，整分钟字幕", 180000);
        expect("3分钟", 180000);
        expect("1小时，整小时字幕", 3600000);
        expect("1小时", 3600000);
        expect("1小时2分钟3秒钟，字幕", 3723000);
        expect("1 小时 2 分钟 3 秒钟\n字幕", 3723000);
        expect("1小时，2分钟，3秒钟，字幕", 3723000);
        expect("1小时3秒钟，字幕", 3603000);
        expect("1小时2分钟，字幕", 3720000);
        expect("0秒钟，第一句", 0);
        expect("5秒", 5000);
        expect("90分钟，长视频", 5400000);
        expect("\u00a0 3分钟\u00a05秒钟，字幕", 185000);
        expect("1 hour, 2 minutes, 3 seconds, Caption", 3723000);
        expect("1 hour 2 minutes and 3 seconds, Caption", 3723000);
        expect("2 HOURS, 1 MINUTE, 1 SECOND", 7261000);
        expect("3 minutes, Caption", 180000);
        expect("1 hour, Caption", 3600000);
        expect("0 seconds, First line", 0);
        expect("1 minute 5 seconds\nCaption", 65000);
        expect("0:00", 0);
        expect("3:05 Caption", 185000);
        expect("3:00，整分钟字幕", 180000);
        expect("1:02:03, Caption", 3723000);
        expect("1:00:00", 3600000);
        expect("125:59", 7559000);
        expect("  03:05\nCaption", 185000);

        for (String invalid : new String[] {
                null, "", "  ", "123", "2026年是一个…", "3分钟后会出现…", "5秒钟内…",
                "1小时候…", "字幕 3:05", "at 3:05", "1:2", "1:60", "1:60:00", "1:02:60",
                "1:23:456", "1:23:45:00", "1:23.5", "1:23字幕", "-1:23", "-3分钟",
                "3.5秒", "2minutesLater", "3 secondsLater", ",3分钟", "and 3 seconds",
                "999999999999999999999999999分钟", "9223372036854775807小时", "9223372036854775807:00"
        }) expect(invalid, -1);
        System.out.println("TRANSCRIPT_TIMESTAMP_OK checks=" + checks);
    }
}
