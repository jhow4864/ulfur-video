package com.jamh.videoplayer;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 1.7.0: the Cast receiver only takes WebVTT; SubRip (.srt) is converted on the fly. */
final class SubtitleConvert {

    private static final Pattern SRT_TIME = Pattern.compile(
            "(\\d{1,2}:\\d{2}:\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2})[,.](\\d{1,3})");

    private SubtitleConvert() {
    }

    static boolean supported(@Nullable String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".srt") || n.endsWith(".vtt") || n.isEmpty();
    }

    /** WebVTT text for an .srt or .vtt file's contents. */
    static String toVtt(String text) {
        if (text == null) text = "";
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.trim().startsWith("WEBVTT")) return text;
        Matcher m = SRT_TIME.matcher(text);
        StringBuffer sb = new StringBuffer("WEBVTT\n\n");
        while (m.find()) {
            m.appendReplacement(sb, pad(m.group(1)) + "." + ms(m.group(2)) + " --> " + pad(m.group(3)) + "." + ms(m.group(4)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String pad(String hms) {
        return hms.length() == 7 ? "0" + hms : hms;
    }

    private static String ms(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 3) b.append('0');
        return b.toString();
    }
}
