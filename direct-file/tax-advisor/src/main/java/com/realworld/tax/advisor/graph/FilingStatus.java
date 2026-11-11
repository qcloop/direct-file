package com.realworld.tax.advisor.graph;

import java.util.Arrays;
import java.util.List;

/**
 * Filing statuses recognized for status-scoped parameters (standard deduction, bracket widths, the
 * QBI and Additional Medicare thresholds). The lower-case {@link #code()} is the wire form used by the
 * MCP tools and by the {@code filing_status} field of the tax-knowledge parameter rows.
 */
public enum FilingStatus {
    SINGLE("single"),
    MFJ("mfj"),
    MFS("mfs");

    private final String code;

    FilingStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** All wire codes, in declaration order. */
    public static List<String> codes() {
        return Arrays.stream(values()).map(FilingStatus::code).toList();
    }

    /** Parse a caller-supplied status (case- and whitespace-insensitive); blank defaults to single. */
    public static FilingStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SINGLE;
        }
        String normalized = raw.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(status -> status.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown filing status '" + raw + "'. Use one of "
                        + codes() + " (head-of-household / qualifying surviving spouse share the 'single'"
                        + " thresholds for these provisions)."));
    }

    /**
     * Whether a parameter row scoped to {@code parameterStatus} applies to this status. A null scope
     * means the parameter is status-agnostic and applies to every status (the common case).
     */
    public boolean appliesTo(String parameterStatus) {
        return parameterStatus == null || code.equalsIgnoreCase(parameterStatus.trim());
    }
}
