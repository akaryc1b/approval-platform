package io.github.akaryc1b.approval.application;

import java.util.Locale;
import java.util.regex.Pattern;

import static io.github.akaryc1b.approval.application.ProcessTemplateContracts.MAX_STRING_LENGTH;

/** Shared whitelist and executable-content rejection rules. */
public final class ProcessTemplateSecurity {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final Pattern SAFE_RESOURCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION_PART = Pattern.compile("0|[1-9][0-9]*");
    private static final String[] EXECUTABLE_MARKERS = {
        "javascript:", "<script", "dynamic import", "import(", "remote module",
        "remotemodule", "expression", "eval(", "data:text/html"
    };

    private ProcessTemplateSecurity() {
    }

    public static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " must not be blank");
        }
        if (value.length() > MAX_STRING_LENGTH) {
            throw new ProcessTemplateException.PackageTooLarge(name + " exceeds maximum string length");
        }
        requireValidUnicode(value, name);
        rejectExecutable(value);
        return value.trim();
    }

    public static String key(String value, String name) {
        String checked = text(value, name);
        if (!SAFE_KEY.matcher(checked).matches()) {
            throw invalid(name + " is not a safe key");
        }
        return checked;
    }

    public static String resourceName(String value, String name) {
        String checked = text(value, name);
        if (!SAFE_RESOURCE.matcher(checked).matches()
            || checked.startsWith("/")
            || checked.endsWith("/")
            || checked.contains("..")
            || checked.contains("//")
            || checked.contains("\\")) {
            throw invalid(name + " is unsafe");
        }
        return checked;
    }

    public static String hash(String value, String name) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw invalid(name + " must be a lowercase SHA-256 value");
        }
        return value;
    }

    public static String version(String value, String name) {
        if (value == null) {
            throw invalid(name + " must not be null");
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) {
            throw invalid(name + " must use major.minor.patch");
        }
        for (String part : parts) {
            if (!VERSION_PART.matcher(part).matches()) {
                throw invalid(name + " has an invalid numeric version part");
            }
            try {
                Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                throw invalid(name + " version part is too large");
            }
        }
        return value;
    }

    public static int compareVersions(String left, String right) {
        int[] leftParts = parseVersion(left);
        int[] rightParts = parseVersion(right);
        for (int index = 0; index < leftParts.length; index++) {
            int compared = Integer.compare(leftParts[index], rightParts[index]);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    public static void rejectExecutable(String value) {
        if (value == null) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String marker : EXECUTABLE_MARKERS) {
            if (normalized.contains(marker)) {
                throw invalid("template package contains prohibited executable content");
            }
        }
        if (normalized.contains("://")) {
            throw invalid("template package contains a prohibited executable URL");
        }
        if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
            throw invalid("template package contains prohibited arbitrary HTML");
        }
    }

    private static int[] parseVersion(String value) {
        String checked = version(value, "version");
        String[] parts = checked.split("\\.");
        return new int[] {
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2])
        };
    }

    private static void requireValidUnicode(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid(name + " contains invalid Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalid(name + " contains invalid Unicode");
            }
        }
    }

    private static ProcessTemplateException invalid(String message) {
        return new ProcessTemplateException(message);
    }
}
