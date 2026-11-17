package com.realworld.tax.advisor.graph;

import java.util.Locale;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;

import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.*;

class FilingStatusTest {
    @HegelTest(testCases = 1000)
    void parsingAndParameterMatchingAreIndependentOfDefaultLocale(TestCase tc) {
        FilingStatus status = tc.draw(sampledFrom(FilingStatus.values()), "status");
        Locale locale = tc.draw(sampledFrom(Locale.ROOT, Locale.US, Locale.forLanguageTag("tr-TR")), "locale");
        String spelling = tc.draw(booleans(), "uppercase") ? status.code().toUpperCase(Locale.ROOT) : status.code();
        String padding = tc.draw(sampledFrom("", " ", "\t", "\r\n"), "padding");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            assertThat(FilingStatus.parse(padding + spelling + padding)).isEqualTo(status);
            for (FilingStatus other : FilingStatus.values()) {
                assertThat(other.appliesTo(padding + spelling + padding)).isEqualTo(other == status);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @HegelTest(testCases = 1000)
    void unknownCodesAreRejectedAndBlankDefaultsToSingle(TestCase tc) {
        String raw = tc.draw(text(), "status");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) assertThat(FilingStatus.parse(raw)).isEqualTo(FilingStatus.SINGLE);
        else if (FilingStatus.codes().contains(normalized))
            assertThat(FilingStatus.parse(raw).code()).isEqualTo(normalized);
        else
            assertThatThrownBy(() -> FilingStatus.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("filing status");
    }
}
