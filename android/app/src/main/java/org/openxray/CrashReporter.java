package org.openxray;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Detects game crashes from a previous run and builds anonymous reports.
 *
 * <p>How it works: {@link org.openxray.OpenXRayActivity} writes a small marker
 * file when the game starts and deletes it in {@code onDestroy()}. A native
 * crash (SIGSEGV/SIGABRT in libxrCore and friends) or a Java uncaught
 * exception kills the process instantly, so {@code onDestroy()} never runs and
 * the marker survives. On the next launch {@link org.openxray.LauncherActivity}
 * calls {@link #checkPendingCrash(Context)}: a surviving marker (or a FATAL
 * keyword in the rotated {@code *.bkp} logs) means the previous session
 * crashed and a dialog should be shown.</p>
 *
 * <p>Sending is intentionally browser-based: gitreports.com has no upload API
 * and requires solving a CAPTCHA, so the app opens
 * {@code https://gitreports.com/issue/OWNER/REPO?details=...} with a truncated
 * report (engine log tail + logcat tail). Full logs go through the Android
 * share sheet / clipboard.</p>
 */
public final class CrashReporter {

    public static final String GITREPORTS_OWNER = "Eternal-Entropia";
    public static final String GITREPORTS_REPO = "openxray-android";
    public static final String GITREPORTS_BASE_URL =
            "https://gitreports.com/issue/" + GITREPORTS_OWNER + "/" + GITREPORTS_REPO;

    private static final String PREFS_NAME = "openxray_launcher_prefs";
    private static final String MARKER_NAME = "openxray_session.marker";

    /** Prefill budget for the gitreports ?details= URL parameter. */
    private static final int PREFILL_MAX_CHARS = 5000;
    /** Budget for the full text shared via ACTION_SEND / clipboard. */
    private static final int FULL_REPORT_MAX_CHARS = 60000;

    private CrashReporter() {}

    /** Crash evidence collected for the previous session. */
    public static final class CrashInfo {
        /** True when the session marker survived (= process died w/o onDestroy). */
        public boolean sessionMarker;
        public File logDir;
        /** "native" | "java" | "engine" | "unknown". */
        public String crashKind = "unknown";
        /** First crash-looking line found, may be null. */
        public String crashLine;
        /**
         * Multi-line X-Ray assert block (FATAL ERROR + [error] Function/File/
         * Description/Arguments) lifted out of the log, so the cause survives
         * even when the log itself turns out to be stale or missing.
         */
        public String assertText;
        // Current session files. The marker that proves the crash was written by
        // the session that also opened these, so they are the ones that hold the
        // crash; AppLog.init() rotated the *previous* run into the .bkp files.
        public File appLog;
        public File logcat;
        // Previous session, kept as a fallback when the current ones are empty.
        public File appLogBkp;
        public File logcatBkp;
        public File engineLog;
        public File engineLogBkp;
        public long markerTime;
    }

    // ------------------------------------------------------------------ //
    // Marker handling (called from OpenXRayActivity).
    // ------------------------------------------------------------------ //

    /** Writes the "game is running" marker. Call right after AppLog.init(). */
    public static void markGameStarted(Context context, String gamePath) {
        try {
            File dir = resolveDir(context, gamePath);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File marker = new File(dir, MARKER_NAME);
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String mode = gameMode(context);
            try (FileWriter w = new FileWriter(marker, false)) {
                w.write(stamp + " mode=" + mode + " pid=" + android.os.Process.myPid() + "\n");
            }
        } catch (Exception ignored) {}
    }

    /** Deletes the marker on a clean exit (OpenXRayActivity.onDestroy). */
    public static void markGameStoppedCleanly(Context context) {
        try {
            for (File dir : candidateDirs(context)) {
                File marker = new File(dir, MARKER_NAME);
                if (marker.exists()) {
                    try { marker.delete(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /** Deletes all markers so the crash dialog is shown only once. */
    public static void consumePendingCrash(Context context) {
        markGameStoppedCleanly(context);
    }

    // ------------------------------------------------------------------ //
    // Crash detection (called from LauncherActivity.onCreate).
    // ------------------------------------------------------------------ //

    /**
     * Returns crash evidence for the previous session, or null when the
     * previous session looks clean.
     */
    public static CrashInfo checkPendingCrash(Context context) {
        try {
            List<File> dirs = candidateDirs(context);

            // 1. Newest surviving session marker wins — the process died.
            File markerDir = null;
            long markerTime = 0;
            for (File dir : dirs) {
                File m = new File(dir, MARKER_NAME);
                if (m.exists() && m.lastModified() >= markerTime) {
                    markerTime = m.lastModified();
                    markerDir = dir;
                }
            }
            if (markerDir != null) {
                CrashInfo info = collectLogs(markerDir);
                info.sessionMarker = true;
                info.markerTime = markerTime;
                findCrashLine(info);
                return info;
            }

            // 2. No marker, but the rotated logs may still contain a FATAL
            //    from a Java crash in the launcher itself or elsewhere.
            for (File dir : dirs) {
                CrashInfo info = collectLogs(dir);
                if (findCrashLine(info)) {
                    info.sessionMarker = false;
                    return info;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static CrashInfo collectLogs(File dir) {
        CrashInfo info = new CrashInfo();
        info.logDir = dir;
        info.appLog = new File(dir, "openxray_app.log");
        info.logcat = new File(dir, "openxray_logcat.log");
        info.appLogBkp = new File(dir, "openxray_app.log.bkp");
        info.logcatBkp = new File(dir, "openxray_logcat.log.bkp");
        try {
            info.engineLog = AppLog.findEngineLog(dir);
            if (info.engineLog != null) {
                File bkp = new File(info.engineLog.getParentFile(),
                        info.engineLog.getName().replaceAll("\\.log$", ".bkp"));
                if (bkp.exists() && bkp.length() > 0) info.engineLogBkp = bkp;
            }
        } catch (Exception ignored) {}
        return info;
    }

    /**
     * Builds a report for the session that is running *now*, for manual
     * "something is broken but it did not crash" uploads from the logs dialog.
     * The result is not a real crash: {@code crashKind} is "diagnostics" and no
     * marker is consumed by sending it.
     */
    public static CrashInfo forCurrentSession(Context context) {
        CrashInfo info = null;
        File cur = currentLogDir();
        if (cur != null && cur.isDirectory()) info = collectLogs(cur);
        if (info == null) {
            List<File> dirs = candidateDirs(context);
            if (!dirs.isEmpty()) info = collectLogs(dirs.get(0));
        }
        if (info == null) info = new CrashInfo();
        info.crashKind = "diagnostics";
        info.crashLine = "no crash detected - manual log upload";
        return info;
    }

    /**
     * Scans previous-session logs for crash keywords. Fills
     * {@link CrashInfo#crashKind} / {@link CrashInfo#crashLine}.
     */
    private static boolean findCrashLine(CrashInfo info) {
        // Scan the session the marker came from first; only then the one before
        // it. Looking at the rotated logs first used to report "unknown" for a
        // perfectly diagnosable crash whenever the previous run exited cleanly.
        File logcat = pickNewer(info.logcat, info.logcatBkp, info.markerTime);
        File appLog = pickNewer(info.appLog, info.appLogBkp, info.markerTime);

        // The engine's assert block is the single most informative thing in the
        // log. Pull it out here, while the matching file is known, so it is still
        // in the report if the log turns out to be truncated or missing later.
        if (logcat != null) info.assertText = extractAssertBlock(logcat);
        if (info.assertText == null && appLog != null) info.assertText = extractAssertBlock(appLog);

        // Native handler in entry_point.cpp logs "*** OpenXRay NATIVE CRASH...".
        String line = scanFileFor(logcat,
                "*** OpenXRay NATIVE CRASH", "Fatal signal", "FATAL EXCEPTION",
                "Build fingerprint", "signal ", "abort");
        if (line != null) {
            info.crashKind = "native";
            info.crashLine = line;
            return true;
        }
        line = scanFileFor(appLog,
                "FATAL UNCAUGHT EXCEPTION", "NATIVE CRASH", "FATAL", "AndroidRuntime");
        if (line != null) {
            info.crashKind = "java";
            info.crashLine = line;
            return true;
        }
        // Engine FATALs (R_ASSERT / CHECK_OR_EXIT) live only in its own log.
        File engineFile = pickNewer(info.engineLog, info.engineLogBkp, info.markerTime);
        if (info.assertText == null && engineFile != null) {
            info.assertText = extractAssertBlock(engineFile);
        }
        String engineLine = engineFile != null
            ? scanFileFor(engineFile,
                    "FATAL ERROR", "stack overflow", "segmentation fault",
                    "application is aborting", "cannot open file")
            : null;
        if (engineLine != null) {
            info.crashKind = "engine";
            info.crashLine = engineLine;
            return true;
        }
        return false;
    }

    /**
     * Lifts X-Ray's multi-line assert report out of a log. The engine prints
     * "FATAL ERROR" followed by labelled [error] lines (Expression, Function,
     * File, Description, Arguments) — that block is the cause, and it is printed
     * several milliseconds before Android's own "Fatal signal" header, so it is
     * easy to lose to a naive tail.
     */
    private static String extractAssertBlock(File file) {
        List<String> tail = readTailLines(file, 4000, 400000);
        int start = -1;
        for (int i = tail.size() - 1; i >= 0; i--) {
            String l = tail.get(i);
            if (l != null && (l.contains("FATAL ERROR") || l.contains("*** OpenXRay NATIVE CRASH"))) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;

        StringBuilder sb = new StringBuilder();
        int end = Math.min(tail.size(), start + 40);
        for (int i = start; i < end; i++) {
            String l = tail.get(i);
            if (l == null) continue;
            // Keep only the engine's own labelled lines; drop logcat noise.
            if (l.contains("[error]") || l.contains("FATAL") || l.contains("NATIVE CRASH")
                || l.contains("stack trace") || l.contains("backtrace")
                || l.contains("Assert") || l.contains("assert")) {
                String cleaned = l.replaceAll("^.*?OpenXRay:\\s*", "");
                if (cleaned.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(cleaned.trim());
            }
        }
        return sb.length() > 0 ? sb.toString() : tail.get(start).trim();
    }

    private static String scanFileFor(File file, String... keywords) {
        if (file == null || !file.exists() || file.length() == 0) return null;
        // Scan from the tail: the crash is always at the end of the session.
        List<String> tail = readTailLines(file, 120, 30000);
        String fallback = null;
        for (String l : tail) {
            for (String kw : keywords) {
                if (l.contains(kw)) {
                    // Prefer the strongest signals, keep scanning for them.
                    if (kw.startsWith("*** OpenXRay") || kw.contains("FATAL")
                            || kw.contains("Fatal signal") || kw.contains("FATAL EXCEPTION")) {
                        return l.trim();
                    }
                    if (fallback == null) fallback = l.trim();
                }
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------ //
    // Report building.
    // ------------------------------------------------------------------ //

    /**
     * The report body, in two parts:
     *
     * <ol>
     *   <li>a header with the things that actually explain most failures —
     *       device, build, resolution, which game files are on disk, and which
     *       storage permissions the app was granted;</li>
     *   <li>the tail of each log, compressed down to the last
     *       {@link #TAIL_LINES} lines.</li>
     * </ol>
     *
     * <p>Compression strips timestamps, thread/process ids and indentation and
     * collapses runs of identical lines, which typically removes 50-80% of the
     * text. GitHub caps an issue body at 65536 characters, so this buys more
     * meaningful tail instead of spending the budget on noise.</p>
     */
    public static String buildReport(CrashInfo info, Context context) {
        StringBuilder sb = new StringBuilder();

        sb.append("oxr-fp: ").append(fingerprint(info, context)).append('\n');
        sb.append('\n').append("=== ENVIRONMENT ===\n");
        appendEnvironment(sb, context);
        sb.append('\n').append("=== GAME FILES ===\n");
        appendFileInventory(sb, info.logDir);
        sb.append('\n').append("=== PERMISSIONS ===\n");
        appendPermissions(sb, context, info.logDir);
        sb.append('\n').append("=== CRASH ===\n");
        sb.append("kind: ").append(info.crashKind).append('\n');
        if (info.markerTime > 0) {
            sb.append("session started: ")
              .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(info.markerTime))).append('\n');
        }
        sb.append("line: ").append(info.crashLine != null ? info.crashLine : "(not found)").append('\n');
        // The assert text only ever reaches logcat, and logcat is the file most
        // likely to be missing or stale. Repeat what we managed to scrape so a
        // report still names the cause when the log itself is gone.
        sb.append("assert: ").append(info.assertText != null ? info.assertText : "(not captured)").append('\n');

        sb.append("\n=== LOG TIMES ===\n");
        sb.append("session marker: ").append(markerStamp(info)).append('\n');
        sb.append("report built  : ").append(stamp(System.currentTimeMillis())).append('\n');
        for (File f : new File[]{info.appLog, info.appLogBkp, info.logcat, info.logcatBkp}) {
            if (f == null || !f.exists()) continue;
            String verdict;
            if (info.markerTime <= 0) {
                verdict = "  <- UNKNOWN session (no marker, cannot verify)";
            } else if (belongsToSession(f, info.markerTime)) {
                verdict = "  <- crashed session";
            } else {
                verdict = "  <- STALE, not this run";
            }
            sb.append(f.getName()).append(": last written ")
              .append(stamp(f.lastModified()))
              .append(f.length() > 0 ? " (" + f.length() + " bytes)" : " (empty)")
              .append(verdict).append('\n');
        }

        // Logcat only. AppLog duplicates everything into logcat via Log.i/e, and the
        // engine's Msg() stream lands there under the "OpenXRay" tag, so a single
        // section carries the engine trace plus the native crash/tombstone lines
        // that only logcat has.
        File logcatFile = pickNewer(info.logcat, info.logcatBkp, info.markerTime);
        sb.append("\n===== LOGCAT");
        if (logcatFile != null) sb.append(" (").append(logcatFile.getName()).append(")");
        sb.append(" =====\n");
        if (logcatFile != null) {
            sb.append(readFromFatal(logcatFile));
        } else {
            // Never silently substitute an unrelated older log: a report that
            // looks complete but describes a different run is worse than one
            // that admits the gap.
            sb.append("(NO logcat belongs to the crashed session).\n");
            sb.append("session started: ").append(markerStamp(info)).append('\n');
            for (File candidate : new File[]{info.logcat, info.logcatBkp}) {
                if (candidate != null && candidate.exists()) {
                    sb.append("available but stale: ").append(candidate.getName())
                      .append(" last written ").append(stamp(candidate.lastModified())).append('\n');
                }
            }
        }

        // The main logcat buffer only gets the signal notification; the symbolicated
        // native backtrace lands in the crash buffer, which is a different ring
        // buffer and is NOT part of openxray_logcat.log.
        String tombstone = readCrashBuffer(CRASH_BUFFER_LINES);
        if (tombstone != null && !tombstone.isEmpty()) {
            sb.append("\n===== TOMBSTONE (logcat -b crash, last ")
              .append(CRASH_BUFFER_LINES).append(" lines) =====\n");
            sb.append(tombstone).append('\n');
        }

        // readFromFatal() already respects the budget; this is the final guard
        // covering the header sections added above it.
        String out = sb.toString();
        if (out.length() > FULL_REPORT_MAX_CHARS) {
            out = out.substring(0, FULL_REPORT_MAX_CHARS) + "\n...[TRUNCATED]...";
        }
        return out;
    }

    /** Fallback tail when no fatal marker is present. */
    private static final int TAIL_LINES = 100;
    /** How far back to search for the fatal marker before giving up. */
    private static final int SCAN_LINES = 4000;
    /** Lines pulled from the native crash buffer (the tombstone). */
    private static final int CRASH_BUFFER_LINES = 120;
    /**
     * Tolerance for filesystem timestamp granularity. Android's FAT timestamps
     * on emulated storage can lag a write by a second or two, and the marker is
     * written before the log is touched.
     */
    private static final long STALE_SLACK_MS = 5000L;

    /**
     * Reads the crash ring buffer, which holds the platform tombstone with the
     * symbolicated native backtrace. Returns null when the platform denies access
     * (modern Android gates this behind READ_LOGS), never throws.
     */
    private static String readCrashBuffer(int maxLines) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "logcat", "-b", "crash", "-d", "-v", "threadtime", "-t", String.valueOf(maxLines));
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                    p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                BufferedReader br = new BufferedReader(reader);
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Markers that start the interesting part of the trace, strongest first.
     *
     * <p>Order matters and is not cosmetic. X-Ray's own {@code FATAL ERROR}
     * block carries the actual cause — expression, function, file, description
     * and arguments — and it is printed a few milliseconds <em>before</em>
     * Android's generic {@code Fatal signal} header. Anchoring on the latter
     * cuts the useful half off, which is exactly what happened in the field.</p>
     */
    private static final String[] FATAL_MARKERS = {
        "FATAL ERROR",
        "*** OpenXRay NATIVE CRASH",
        "FATAL UNCAUGHT EXCEPTION",
        "FATAL EXCEPTION",
        "Fatal signal",
        "CheckForErrors",
    };

    /**
     * The logcat from the fatal marker to the end of the file, verbatim.
     *
     * <p>Nothing is rewritten here: no timestamp/thread stripping, no collapsing
     * of repeated lines. A native crash trace is only useful exactly as the
     * platform printed it, and the tombstone lines are the whole point.</p>
     *
     * <p>The only limit is GitHub's 65536-character issue body. When the trace
     * does not fit, the cut is reported explicitly so a truncated report is never
     * mistaken for a complete one.</p>
     */
    private static String readFromFatal(File file) {
        List<String> window = readTailLines(file, SCAN_LINES, 400000);
        if (window.isEmpty()) return "(empty)\n";

        int start = -1;
        // Strongest marker that occurs at all wins, anchored at its LAST
        // occurrence. Taking the latest match of *any* marker would be wrong:
        // Android's generic "Fatal signal" header comes after our own
        // symbolicated "*** OpenXRay NATIVE CRASH" handler and would cut the
        // useful half of the trace off.
        for (String marker : FATAL_MARKERS) {
            for (int i = window.size() - 1; i >= 0; i--) {
                String line = window.get(i);
                if (line != null && line.contains(marker)) {
                    start = i;
                    break;
                }
            }
            if (start >= 0) break;
        }

        List<String> slice;
        String what;
        if (start >= 0) {
            slice = window.subList(start, window.size());
            what = "full logcat from the fatal marker (line " + (start + 1)
                 + " of " + window.size() + ")";
        } else {
            int from = Math.max(0, window.size() - TAIL_LINES);
            slice = window.subList(from, window.size());
            what = "no fatal marker found, last " + slice.size() + " lines";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("(").append(what).append(")\n");
        int emitted = 0;
        for (String line : slice) {
            String text = line == null ? "" : line;
            if (sb.length() + text.length() + 1 > FULL_REPORT_MAX_CHARS) break;
            sb.append(text).append('\n');
            emitted++;
        }
        if (emitted < slice.size()) {
            sb.append("...[TRUNCATED: ").append(slice.size() - emitted)
              .append(" of ").append(slice.size())
              .append(" lines did not fit the GitHub 65536-char body limit]...\n");
        }
        return sb.toString();
    }

    private static void appendEnvironment(StringBuilder sb, Context context) {
        sb.append("app: ").append(context != null ? context.getPackageName() : "?").append('\n');
        try {
            if (context != null) {
                android.content.pm.PackageInfo p =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                sb.append("version: ").append(p.versionName)
                  .append(" (").append(p.versionCode).append(")\n");
            }
        } catch (Exception ignored) {}
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append(" (").append(android.os.Build.DEVICE).append(")\n");
        sb.append("android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("abis: ").append(Arrays_toString(android.os.Build.SUPPORTED_ABIS)).append('\n');
        sb.append("screen: ").append(screenSize(context)).append('\n');
    }

    private static String screenSize(Context context) {
        try {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            return dm.widthPixels + "x" + dm.heightPixels + " @" + dm.density + "dpi";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * Which game data actually made it to disk. A missing fsgame.ltx, an absent
     * $arch_dir$ marker or no .db archives is the single most common cause of
     * "the engine never started", and it is invisible in a log tail.
     */
    private static void appendFileInventory(StringBuilder sb, File dir) {
        if (dir == null || !dir.isDirectory()) {
            sb.append("log dir: not found\n");
            return;
        }
        sb.append("log dir: ").append(dir.getAbsolutePath()).append('\n');

        File fsgame = new File(dir, "fsgame.ltx");
        if (fsgame.exists()) {
            String text = AppLog.readTextFile(fsgame);
            boolean hasArch = text != null && text.contains("$arch_dir$");
            sb.append("fsgame.ltx: ").append(fsgame.length()).append(" bytes")
              .append(hasArch ? " (archives declared)" : " (NO $arch_dir$ - archives unmounted)").append('\n');
        } else {
            sb.append("fsgame.ltx: MISSING\n");
        }

        appendEntry(sb, new File(dir, "gamedata"), "gamedata/");
        appendEntry(sb, new File(dir, "gamedata/scripts"), "gamedata/scripts/");
        appendScriptList(sb, new File(dir, "gamedata/scripts"));
        appendEntry(sb, new File(dir, "gamedata/config"), "gamedata/config/");
        appendEntry(sb, new File(dir, "gamedata/configs"), "gamedata/configs/");
        appendEntry(sb, new File(dir, "_appdata_"), "_appdata_/");
        appendEntry(sb, new File(dir, "_appdata_/user.ltx"), "_appdata_/user.ltx");

        File[] dbs = AppLog.collectDatabaseArchives(dir);
        if (dbs == null || dbs.length == 0) {
            sb.append("archives: NONE FOUND\n");
        } else {
            sb.append("archives (").append(dbs.length).append("): ");
            for (int i = 0; i < dbs.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(dbs[i].getName());
            }
            sb.append('\n');
        }
    }

    /**
     * Names the loose .script files actually sitting in gamedata/scripts.
     *
     * <p>The entry count above cannot answer the question that matters when a
     * script misbehaves: whether a given script came from the overlay or out of
     * the .db archives. A loose file shadows the same-named archive entry, and
     * an install whose archives hold the wrong game's scripts produces the same
     * file count as a correct one. Listing the names settles it -- a script
     * nobody shipped in res/&lt;mode&gt;/ is coming from the game data.</p>
     */
    private static void appendScriptList(StringBuilder sb, File dir) {
        File[] scripts = dir.listFiles();
        if (scripts == null || scripts.length == 0) {
            return;
        }
        java.util.Arrays.sort(scripts, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        sb.append("  loose scripts: ");
        int shown = 0;
        for (File s : scripts) {
            if (!s.isFile() || !s.getName().endsWith(".script")) {
                continue;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(s.getName());
            if (++shown == 60) {
                sb.append(", ...");
                break;
            }
        }
        sb.append('\n');
    }

    private static void appendEntry(StringBuilder sb, File f, String label) {
        if (f.isDirectory()) {
            String[] kids = f.list();
            sb.append(label).append(" present (").append(kids != null ? kids.length : 0)
              .append(" entries)\n");
        } else if (f.isFile()) {
            sb.append(label).append(" present (").append(f.length()).append(" bytes)\n");
        } else {
            sb.append(label).append(" MISSING\n");
        }
    }

    /**
     * Picks the log that belongs to the crashed session.
     *
     * <p>The marker that proves the crash was written by the session that opened
     * the <em>current</em> log files, so on paper the current file is always the
     * right one. In practice it is not: the launcher and the game log into
     * different directories, and a log file that was never re-created after the
     * session started is simply an old leftover. Such a file is non-empty and
     * "newer" by name only, and shipping it silently hid a two-hour-old crash
     * in a fresh report.</p>
     *
     * <p>So the choice is made on wall-clock instead: a log written during or
     * after the session started belongs to it. When nothing matches, the caller
     * gets null and says so in the report rather than sending stale evidence.</p>
     */
    private static File pickNewer(File current, File previous, long markerTime) {
        if (belongsToSession(current, markerTime)) return current;
        if (belongsToSession(previous, markerTime)) return previous;
        return null;
    }

    private static File pickNewer(File current, File previous) {
        return pickNewer(current, previous, 0);
    }

    /**
     * Non-empty, and written after the session began.
     *
     * <p>When the marker is missing there is no session boundary to compare
     * against, and the crash was only detected by a keyword scan. Returning true
     * there labelled a two-hour-old log as "crashed session" -- exactly the lie
     * this check exists to prevent -- so an unknown boundary rejects
     * everything and the caller reports the gap instead.</p>
     */
    private static boolean belongsToSession(File file, long markerTime) {
        if (file == null || !file.exists() || file.length() == 0) return false;
        if (markerTime <= 0) return false;
        return file.lastModified() + STALE_SLACK_MS >= markerTime;
    }

    /** Storage access is the #1 cause of "logs and archives cannot be written". */
    private static void appendPermissions(StringBuilder sb, Context context, File dir) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            boolean allFiles;
            try {
                allFiles = Environment.isExternalStorageManager();
            } catch (Throwable t) {
                allFiles = false;
            }
            sb.append("all files access: ").append(allFiles ? "GRANTED" : "DENIED").append('\n');
        } else if (context != null) {
            sb.append("read external storage: ").append(granted(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE)).append('\n');
            sb.append("write external storage: ").append(granted(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)).append('\n');
        }
        sb.append("internet: ").append(granted(context, android.Manifest.permission.INTERNET)).append('\n');
        if (dir != null && dir.exists()) {
            sb.append("log dir writable: ").append(dir.canWrite()).append('\n');
            sb.append("free space: ").append(dir.getFreeSpace() / (1024 * 1024)).append(" MB\n");
        }
    }

    private static String granted(Context context, String permission) {
        if (context == null) return "?";
        try {
            return context.checkSelfPermission(permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String Arrays_toString(String[] abis) {
        if (abis == null) return "";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < abis.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(abis[i]);
        }
        return sb.append("]").toString();
    }

    /** Same logs, cut down to what survives a URL query parameter. */
    public static String buildShortDetails(CrashInfo info, Context context) {
        String out = buildReport(info, context);
        if (out.length() > PREFILL_MAX_CHARS) {
            out = out.substring(0, PREFILL_MAX_CHARS) + "\n...[truncated]...";
        }
        return out;
    }

    /** Browser URL for anonymous submit (repo must be Activated on gitreports). */
    public static String buildGitReportsUrl(CrashInfo info, Context context) {
        String details = buildShortDetails(info, context);
        return Uri.parse(GITREPORTS_BASE_URL).buildUpon()
                .appendQueryParameter("details", details)
                .build().toString();
    }

    private static String stamp(long millis) {
        if (millis <= 0) return "unknown";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private static String markerStamp(CrashInfo info) {
        return info.markerTime > 0 ? stamp(info.markerTime) : "unknown (no marker)";
    }

    /** One-line summary for the dialog header. */
    public static String describeCrash(CrashInfo info) {
        if ("native".equals(info.crashKind)) return "Native engine crash (signal / Fatal signal)";
        if ("java".equals(info.crashKind)) return "Java crash in the application";
        if ("engine".equals(info.crashKind)) return "Engine FATAL error (see the engine log)";
        if (info.sessionMarker) return "The game exited unexpectedly (process died)";
        return "The previous session ended unexpectedly";
    }

    // ------------------------------------------------------------------ //
    // Fingerprint (dedup key for the GitHub reporter).
    // ------------------------------------------------------------------ //

    /**
     * Stable id of a crash signature: hex of a hash over the crash line with
     * all volatile parts (addresses, timestamps, pids, offsets) stripped, so
     * the same bug on two devices produces the same fingerprint and gets
     * appended to the existing issue instead of spamming new ones.
     */
    public static String fingerprint(CrashInfo info, Context context) {
        String src = (info.crashLine != null ? info.crashLine : "no-fatal-line")
                + "|" + info.crashKind
                + "|" + gameMode(context);
        return hash(normalizeForHash(src));
    }

    /** Drops hex addresses, numbers and timestamps — the parts that differ per run. */
    private static String normalizeForHash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '0' || c == 'x' || Character.digit(c, 16) >= 0 && (Character.isDigit(c) || (c >= 'a' && c <= 'f'))) {
                sb.append('#');
                // Collapse runs of hex-ish chars into a single placeholder.
                while (i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    boolean hexish = Character.isDigit(n) || (n >= 'a' && n <= 'f') || n == 'x' || n == 'X';
                    if (!hexish) break;
                    i++;
                }
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** FNV-1a, 64-bit, rendered as hex. */
    private static String hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return String.format(Locale.US, "%012x", h & 0xFFFFFFFFFFFFL);
    }

    // ------------------------------------------------------------------ //
    // File helpers.
    // ------------------------------------------------------------------ //

    private static String gameMode(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return p.getString("game_mode", "soc");
        } catch (Exception ignored) {
            return "soc";
        }
    }

    private static File resolveDir(Context context, String gamePath) {
        if (gamePath != null && !gamePath.trim().isEmpty()) {
            return new File(gamePath.trim());
        }
        File fromLog = currentLogDir();
        if (fromLog != null) return fromLog;
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String base = p.getString("game_path",
                    new File(Environment.getExternalStorageDirectory(), "OpenXRay").getAbsolutePath());
            String mode = p.getString("game_mode", "soc");
            return new File(base, mode);
        } catch (Exception ignored) {}
        return new File(Environment.getExternalStorageDirectory(), "OpenXRay");
    }

    private static File currentLogDir() {
        try {
            return AppLog.getCurrentLogDir();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Base dir + per-mode dirs + current AppLog dir: crash may be in any mode. */
    private static List<File> candidateDirs(Context context) {
        List<File> out = new ArrayList<>();
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String base = p.getString("game_path",
                    new File(Environment.getExternalStorageDirectory(), "OpenXRay").getAbsolutePath());
            String curMode = p.getString("game_mode", "soc");
            // Current mode first — most likely the crashed one.
            out.add(new File(base, curMode));
            for (String m : new String[]{"soc", "cs", "cop"}) {
                if (m.equals(curMode)) continue;
                out.add(new File(base, m));
            }
            out.add(new File(base));
        } catch (Exception ignored) {}
        File cur = currentLogDir();
        if (cur != null && !out.contains(cur)) out.add(0, cur);
        List<File> existing = new ArrayList<>();
        for (File d : out) {
            if (d != null && d.isDirectory() && !existing.contains(d)) existing.add(d);
        }
        return existing;
    }

    /**
     * Last {@code maxLines} lines of a file. {@code maxChars <= 0} means no
     * character budget -- otherwise the drop-head loop below would immediately
     * squeeze the result down to a single line.
     */
    private static List<String> readTailLines(File file, int maxLines, int maxChars) {
        LinkedList<String> lines = new LinkedList<>();
        if (file == null || !file.exists()) return lines;
        int chars = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
                chars += line.length() + 1;
                while (lines.size() > maxLines) {
                    chars -= lines.removeFirst().length() + 1;
                }
                // Keep memory bounded for multi-MB logs: drop head.
                while (maxChars > 0 && chars > maxChars && lines.size() > 1) {
                    chars -= lines.removeFirst().length() + 1;
                }
            }
        } catch (Exception ignored) {}
        return lines;
    }

    }
