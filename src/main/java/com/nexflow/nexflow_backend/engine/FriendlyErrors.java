package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import org.springframework.web.client.HttpStatusCodeException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Turns technical failures into sentences a non-developer can act on.
 *
 * The single place that knows how to phrase errors: executors call {@link #forException} /
 * {@link #forHttpStatus} when they have the exception or status code in hand, and the engine calls
 * {@link #describe} for every failed node so whatever slipped through still gets a readable message.
 * The original technical text is always kept separately as "errorDetail".
 */
public final class FriendlyErrors {

    /** Must match the read timeout configured on the shared RestTemplate (AppConfig). */
    public static final int HTTP_READ_TIMEOUT_SECONDS = 30;

    private static final Pattern STATUS_IN_TEXT = Pattern.compile("(?:API error|HTTP|status(?: code)?)[ :]*(\\d{3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_UNDEFINED_PROP = Pattern.compile("Cannot read propert(?:y|ies) of (undefined|null) \\(reading '([^']+)'\\)");
    private static final Pattern JS_NOT_DEFINED = Pattern.compile("ReferenceError: (\\w+) is not defined");
    private static final Pattern PY_KEY_ERROR = Pattern.compile("KeyError: '?([^'\\n]+)'?");
    private static final Pattern SCRIPT_TIMEOUT = Pattern.compile("timed out after (\\d+) seconds");

    private FriendlyErrors() {}

    // ── Entry point used by the engine ───────────────────────────────────────

    /**
     * Fills in a friendly errorMessage (and keeps the raw text as errorDetail) on a failed node result.
     * Results that already carry an errorDetail were translated by their executor and are returned as is.
     */
    public static NodeContext describe(FlowNode node, NodeContext result, Throwable cause) {
        if (result.getErrorDetail() != null) return result;
        String raw = cause != null ? technical(cause) : result.getErrorMessage();
        String friendly = cause != null ? forException(cause, targetOf(result)) : forNode(node.getNodeType(), result, raw);
        return result.toBuilder().errorMessage(friendly).errorDetail(raw).build();
    }

    private static String forNode(NodeType type, NodeContext result, String raw) {
        if (raw == null || raw.isBlank()) raw = "";
        return switch (type) {
            case NEXUS -> forHttpResult(result, raw);
            case SCRIPT, DECISION -> forScript(raw);
            case AI -> forAi(raw);
            case SUB_FLOW -> raw.startsWith("Child flow ended")
                    ? "The flow this step calls finished with an error. Open that flow's transaction to see why."
                    : raw;
            default -> raw.isBlank() ? "This step failed without saying why." : raw;
        };
    }

    // ── Exceptions ───────────────────────────────────────────────────────────

    /** Friendly sentence for an exception thrown while talking to {@code target} (a URL, host or name; may be null). */
    public static String forException(Throwable ex, String target) {
        String where = hostOf(target);
        if (ex instanceof HttpStatusCodeException http) return forHttpStatus(http.getStatusCode().value(), target);
        Throwable root = rootCause(ex);
        String text = String.valueOf(root.getMessage()).toLowerCase(Locale.ROOT);

        if (root instanceof UnknownHostException) {
            return "Couldn't find the server " + where + ". Check the address for typos.";
        }
        if (root instanceof SocketTimeoutException || root instanceof HttpTimeoutException) {
            return text.contains("connect")
                    ? "Couldn't connect to " + where + " — the connection attempt timed out."
                    : cap(where) + " didn't respond within " + HTTP_READ_TIMEOUT_SECONDS + " seconds.";
        }
        if (root instanceof ConnectException || text.contains("connection refused")) {
            return cap(where) + " refused the connection. The service may be down, or the address or port is wrong.";
        }
        if (root instanceof SSLException) {
            return "Couldn't make a secure (HTTPS) connection to " + where + ". Its certificate may be invalid or expired.";
        }
        if (root instanceof SQLException sql) return forSql(sql);
        if (root instanceof InterruptedException) return "This step was stopped before it finished.";
        String message = root.getMessage();
        return message != null && !message.isBlank() ? firstLine(message) : "Something went wrong (" + root.getClass().getSimpleName() + ").";
    }

    /** Friendly sentence for an HTTP error status returned by {@code target}. */
    public static String forHttpStatus(int status, String target) {
        String where = hostOf(target);
        return switch (status) {
            case 400 -> cap(where) + " rejected the request as invalid (HTTP 400). Check the data being sent.";
            case 401 -> cap(where) + " says the request isn't signed in (HTTP 401). Check the API key or credentials.";
            case 403 -> cap(where) + " refused access (HTTP 403). The credentials don't have permission for this.";
            case 404 -> cap(where) + " couldn't find what was requested (HTTP 404). Check the URL or path.";
            case 405 -> cap(where) + " doesn't allow this request method (HTTP 405). Check GET/POST/PUT.";
            case 408, 504 -> cap(where) + " took too long to answer (HTTP " + status + ").";
            case 409 -> cap(where) + " reported a conflict with existing data (HTTP 409).";
            case 413 -> "The data sent to " + where + " is too large (HTTP 413).";
            case 415, 422 -> cap(where) + " didn't accept the format of the data sent (HTTP " + status + ").";
            case 429 -> cap(where) + " is limiting how often it can be called (HTTP 429). Wait and try again, or add a retry delay.";
            default -> status >= 500
                    ? cap(where) + " had a problem on its side (HTTP " + status + "). Try again later."
                    : cap(where) + " answered with an error (HTTP " + status + ").";
        };
    }

    public static String forSql(SQLException ex) {
        String state = ex.getSQLState() != null ? ex.getSQLState() : "";
        if (state.startsWith("08")) return "Couldn't connect to the database. Check the connector's address, port, username and password.";
        if (state.startsWith("28")) return "The database rejected the username or password.";
        if (state.startsWith("42")) return "The database didn't understand the query — there's a typo, or a table or column that doesn't exist.";
        if (state.startsWith("23")) return "The database refused the change because it breaks a rule (for example a duplicate or a missing required value).";
        if (state.startsWith("22")) return "The database rejected a value in the query (wrong type or format).";
        return "The database returned an error: " + firstLine(ex.getMessage());
    }

    // ── Node-specific phrasing from raw text ─────────────────────────────────

    private static String forHttpResult(NodeContext result, String raw) {
        Map<String, Object> failure = result.getFailureOutput();
        if (failure != null && failure.get("statusCode") instanceof Number code) {
            return forHttpStatus(code.intValue(), targetOf(result));
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        String where = hostOf(targetOf(result));
        if (lower.contains("read timed out")) return cap(where) + " didn't respond within " + HTTP_READ_TIMEOUT_SECONDS + " seconds.";
        if (lower.contains("connect timed out")) return "Couldn't connect to " + where + " — the connection attempt timed out.";
        if (lower.contains("connection refused")) return cap(where) + " refused the connection. The service may be down, or the address or port is wrong.";
        if (lower.contains("unknownhost") || lower.contains("nodename nor servname")) return "Couldn't find the server " + where + ". Check the address for typos.";
        return raw.isBlank() ? "The request to " + where + " failed." : raw;
    }

    private static String forScript(String raw) {
        Matcher m;
        if ((m = SCRIPT_TIMEOUT.matcher(raw)).find()) {
            return "The script stopped because it ran longer than " + m.group(1) + " seconds.";
        }
        if (raw.startsWith("Infinite loop detected")) {
            return "The script was stopped because it looked stuck in an endless loop.";
        }
        if (raw.contains("Cannot run program \"node\"") || raw.contains("node: not found") || raw.contains("node: command not found")) {
            return "JavaScript can't run on this server right now (Node.js isn't installed). Ask the admin to install it, or write the script in Python.";
        }
        if (raw.contains("Cannot run program \"python3\"")) {
            return "Python can't run on this server right now (python3 isn't installed). Ask the admin to install it.";
        }
        if (raw.startsWith("Script uses blocked pattern")) {
            return "The script uses something that isn't allowed for security reasons (" + raw.substring(raw.indexOf(':') + 1).trim() + ").";
        }
        if ((m = JS_UNDEFINED_PROP.matcher(raw)).find()) {
            return "The script tried to read '" + m.group(2) + "' from a value that is " + m.group(1)
                    + ". Something it expected is missing — check the nex names it uses.";
        }
        if ((m = JS_NOT_DEFINED.matcher(raw)).find()) {
            return "The script uses '" + m.group(1) + "', which doesn't exist. Check the spelling, or use nex." + m.group(1) + ".";
        }
        if ((m = PY_KEY_ERROR.matcher(raw)).find()) {
            return "The script looked up '" + m.group(1) + "', but that key isn't in the data.";
        }
        if (raw.startsWith("Script produced no valid JSON")) {
            return "The script finished but its result couldn't be read. Return plain data (numbers, text, lists or objects).";
        }
        return raw.isBlank() ? "The script failed." : "The script failed: " + firstLine(raw);
    }

    private static String forAi(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        Matcher m = STATUS_IN_TEXT.matcher(raw);
        int status = m.find() ? Integer.parseInt(m.group(1)) : -1;
        if (status == 401 || lower.contains("api key") && (lower.contains("invalid") || lower.contains("incorrect"))) {
            return "The AI provider rejected the API key — check Settings → AI Providers.";
        }
        if (status == 403) return "The AI provider refused access for this API key. Check the key's permissions or your account.";
        if (status == 404 || lower.contains("model") && lower.contains("not found")) {
            return "The AI model wasn't found. Check the model name on this step.";
        }
        if (status == 429) return "The AI provider's rate limit or quota was reached. Wait a moment, or check your plan and billing.";
        if (status >= 500 || lower.contains("overloaded")) return "The AI provider is overloaded or having problems right now. Try again shortly.";
        if (lower.contains("timed out") || lower.contains("timeout")) return "The AI provider didn't answer in time.";
        if (lower.contains("could not parse a valid json")) {
            return "The AI answered, but not in the expected JSON format. Make the prompt more specific about the output.";
        }
        return raw.isBlank() ? "The AI step failed." : raw;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String targetOf(NodeContext result) {
        Object url = result.getInput() != null ? result.getInput().get("url") : null;
        return url != null ? url.toString() : null;
    }

    /** "https://api.x.com/v1/items" → "api.x.com"; falls back to the text itself or "the server". */
    static String hostOf(String target) {
        if (target == null || target.isBlank()) return "the server";
        try {
            String host = URI.create(target.trim()).getHost();
            if (host != null) return host;
        } catch (IllegalArgumentException ignored) { /* not a URL */ }
        return target;
    }

    /** Sentence subject for a host: "The server at api.x.com" (or just "The server" when unknown). */
    private static String cap(String where) {
        return "the server".equals(where) ? "The server" : "The server at " + where;
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    static String technical(Throwable ex) {
        Throwable root = rootCause(ex);
        String head = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return root == ex ? head : head + " (caused by " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 240 ? line.substring(0, 240) + "…" : line;
    }
}
