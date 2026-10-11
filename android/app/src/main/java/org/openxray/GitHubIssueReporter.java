package org.openxray;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends a crash report straight into the project's GitHub issue tracker.
 *
 * <p>One tap in the crash dialog = one issue, no CAPTCHA, no login: the app
 * POSTs to {@code https://api.github.com/repos/OWNER/REPO/issues} with a
 * token baked in at build time ({@code -POXR_GITHUB_TOKEN=...}).</p>
 *
 * <h3>About that token</h3>
 * <p>A token inside an APK is extractable by anyone — treat it as public. Keep
 * it a <b>fine-grained</b> PAT scoped to this single repository with the
 * {@code Issues: Read and write} permission only, and a short expiry. Such a
 * token cannot touch code, releases or tags, and cannot see other repos; if it
 * leaks, the worst case is somebody spamming issues, which is visible and
 * reversible. Never use a classic {@code repo}-scoped PAT here.</p>
 *
 * <p>{@code GITHUB_API_BASE} exists so this class can be pointed at a relay
 * (a Cloudflare Worker holding the token server-side) later by changing the
 * build property alone — no client code changes.</p>
 */
public final class GitHubIssueReporter {

    private static final String TAG = "GitHubReporter";

    /** GitHub rejects issue bodies over 65536 chars; keep a margin. */
    private static final int MAX_BODY_CHARS = 60000;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    private GitHubIssueReporter() {}

    /** Outcome of a report attempt, delivered on the main thread. */
    public static final class Result {
        public boolean success;
        /** True when an existing issue was found and the report appended to it. */
        public boolean duplicate;
        /** Web URL of the issue; null on failure. */
        public String issueUrl;
        /** Human-readable reason, null on success. */
        public String error;
    }

    public interface Callback {
        void onFinished(Result result);
    }

    /**
     * True when both the token and the repo were supplied at build time.
     * Default builds ship with an empty token, so this returns false and the
     * UI hides the send button instead of failing at tap time.
     */
    public static boolean isConfigured() {
        return !token().isEmpty() && !repo().isEmpty();
    }

    /**
     * Creates an issue (or comments on a matching one) in a background thread.
     * Never throws; {@code callback} always runs on the main thread.
     */
    public static void report(final CrashReporter.CrashInfo info, final Context context,
                              final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Context appCtx = context.getApplicationContext();
        final String report = buildBody(info, appCtx);
        final String title = buildTitle(info);
        final String fp = CrashReporter.fingerprint(info, appCtx);

        new Thread(() -> {
            Result r = new Result();
            try {
                // Same crash from another device: append to the existing issue
                // instead of flooding the tracker with near-identical reports.
                String existing = findExistingIssue(appCtx, fp);
                if (existing != null) {
                    if (appendComment(appCtx, existing, report)) {
                        r.success = true;
                        r.duplicate = true;
                        r.issueUrl = existing;
                    } else {
                        r.error = "Could not add a comment to the existing issue";
                    }
                } else {
                    r.issueUrl = createIssue(appCtx, title, report);
                    if (r.issueUrl == null) {
                        r.error = lastError;
                    } else {
                        r.success = true;
                    }
                }
            } catch (Throwable t) {
                r.success = false;
                r.error = "Network error: " + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " (" + t.getMessage() + ")" : "");
            }
            final Result out = r;
            main.post(() -> callback.onFinished(out));
        }, "GitHubIssueReporter").start();
    }

    /** Opens the created issue in the browser so the user sees what landed. */
    public static void openIssue(Context context, String url) {
        if (url == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ //
    // Issue content.
    // ------------------------------------------------------------------ //

    private static String buildTitle(CrashReporter.CrashInfo info) {
        String sig = info.crashLine != null ? info.crashLine.trim() : "unexpected exit";
        // The tail of the line is usually the pointer/address; the head is the
        // signal name. Cut it to something readable that still identifies it.
        if (sig.length() > 120) sig = sig.substring(0, 120) + "…";
        sig = sig.replaceAll("\\s+", " ").trim();
        String title = "[crash/" + info.crashKind + "] " + sig;
        return title.length() > 250 ? title.substring(0, 250) : title;
    }

    private static String buildBody(CrashReporter.CrashInfo info, Context context) {
        String body = CrashReporter.buildReport(info, context);
        // The fingerprint is already the first line of the report body, where the
        // dedup search below can see it.
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS) + "\n\n...[truncated by app]...";
        }
        return body;
    }

    // ------------------------------------------------------------------ //
    // GitHub REST calls.
    // ------------------------------------------------------------------ //

    private static String lastError = null;

    /**
     * Looks for an open issue already carrying this fingerprint. GitHub's
     * search index lags by seconds-to-minutes, which is irrelevant here: we
     * only dedupe reports of the *previous* run.
     */
    private static String findExistingIssue(Context context, String fingerprint) {
        try {
            String q = "repo:" + repo() + " \"oxr-fp: " + fingerprint + "\" in:body is:issue is:open";
            JSONObject resp = getJson(context, apiBase() + "/search/issues?q=" + Uri.encode(q));
            JSONArray items = resp.optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            // Prefer the oldest match: the original report, not a follow-up.
            JSONObject best = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                if (best == null || it.optInt("number", Integer.MAX_VALUE) < best.optInt("number", Integer.MAX_VALUE)) {
                    best = it;
                }
            }
            return best != null ? best.optString("html_url", null) : null;
        } catch (Throwable t) {
            // Dedup is best-effort: on failure we just create a new issue.
            Log.w(TAG, "dedup lookup failed: " + t);
            return null;
        }
    }

    /** POSTs a comment to an existing issue. Returns true on success. */
    private static boolean appendComment(Context context, String issueUrl, String body) {
        try {
            int number = issueNumberFromUrl(issueUrl);
            if (number <= 0) return false;
            JSONObject payload = new JSONObject();
            payload.put("body", body.length() > MAX_BODY_CHARS
                    ? body.substring(0, MAX_BODY_CHARS) : body);
            HttpResult r = post(context,
                    apiBase() + "/repos/" + repo() + "/issues/" + number + "/comments", payload);
            return r.status == 201;
        } catch (Throwable t) {
            Log.w(TAG, "comment failed: " + t);
            return false;
        }
    }

    /** Creates the issue; returns its html_url, or null on failure. */
    private static String createIssue(Context context, String title, String body) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", title);
            payload.put("body", body);
            JSONArray labels = new JSONArray();
            labels.put("crash");
            labels.put("android");
            payload.put("labels", labels);

            HttpResult r = post(context, apiBase() + "/repos/" + repo() + "/issues", payload);

            // 422 usually means the labels don't exist in this repo — the
            // issue itself is fine, so retry once without them.
            if (r.status == 422) {
                payload.remove("labels");
                r = post(context, apiBase() + "/repos/" + repo() + "/issues", payload);
            }
            if (r.status == 201) {
                return new JSONObject(r.body).optString("html_url", null);
            }
            lastError = describeHttpError(r);
            return null;
        } catch (Throwable t) {
            lastError = "Could not parse the GitHub response: " + t.getMessage();
            return null;
        }
    }

    /** Maps HTTP status + message to something actionable for a user. */
    private static String describeHttpError(HttpResult r) {
        String message = extractMessage(r.body);
        switch (r.status) {
            case 401:
            case 403:
                if (message != null && message.toLowerCase().contains("rate limit")) {
                    return "GitHub API rate limit reached, try again later";
                }
                if (message != null && message.toLowerCase().contains("bad credentials")) {
                    return "The reporting token is invalid or was revoked - please tell the maintainer";
                }
                return "GitHub rejected the request (HTTP " + r.status + ")"
                        + (message != null ? ": " + message : "");
            case 404:
                return "Repository not found, or the token cannot post issues (HTTP 404)";
            case 422:
                return "GitHub rejected the issue content (HTTP 422)"
                        + (message != null ? ": " + message : "");
            case 0:
                return "No connection to GitHub - check your internet";
            default:
                return "GitHub API error (HTTP " + r.status + ")"
                        + (message != null ? ": " + message : "");
        }
    }

    private static String extractMessage(String body) {
        try {
            return new JSONObject(body).optString("message", null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int issueNumberFromUrl(String url) {
        if (url == null) return -1;
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= url.length()) return -1;
        try {
            return Integer.parseInt(url.substring(slash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ //
    // HTTP plumbing.
    // ------------------------------------------------------------------ //

    private static final class HttpResult {
        int status;
        String body = "";
    }

    private static JSONObject getJson(Context context, String urlStr) throws IOException, JSONException {
        HttpResult r = request(context, "GET", urlStr, null);
        if (r.status < 200 || r.status >= 300) throw new IOException("HTTP " + r.status);
        return new JSONObject(r.body);
    }

    private static HttpResult post(Context context, String urlStr, JSONObject payload)
            throws IOException {
        return request(context, "POST", urlStr, payload.toString());
    }

    private static HttpResult request(Context context, String method, String urlStr, String jsonBody) {
        HttpResult out = new HttpResult();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "openxray-android");
            conn.setRequestProperty("Authorization", "Bearer " + token());

            if (jsonBody != null) {
                byte[] payloadBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payloadBytes.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payloadBytes);
                }
            }

            out.status = conn.getResponseCode();
            InputStream is = (out.status >= 200 && out.status < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            out.body = readAll(is);
        } catch (Throwable t) {
            Log.w(TAG, method + " " + urlStr + " failed: " + t);
            out.status = 0;
            out.body = "";
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    // ------------------------------------------------------------------ //
    // Build configuration.
    // ------------------------------------------------------------------ //

    private static String token() {
        return BuildConfig.GITHUB_TOKEN == null ? "" : BuildConfig.GITHUB_TOKEN.trim();
    }

    private static String repo() {
        String r = BuildConfig.GITHUB_REPO;
        return (r == null || r.trim().isEmpty()) ? CrashReporter.GITREPORTS_OWNER
                + "/" + CrashReporter.GITREPORTS_REPO : r.trim();
    }

    /** Default is the GitHub API; swap to a relay URL to keep tokens out of APKs. */
    private static String apiBase() {
        String b = BuildConfig.GITHUB_API_BASE;
        return (b == null || b.trim().isEmpty()) ? "https://api.github.com" : b.trim();
    }
}