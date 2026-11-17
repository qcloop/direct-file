package com.realworld.tax.advisor.graph;

import java.util.Locale;
import java.util.Map;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;

import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.*;

class PersisterTypesTest {
    @HegelTest(testCases = 1000)
    void aliasesResolveRegardlessOfLocaleAndExplicitTypesTakePrecedence(TestCase tc) {
        Map<String, String> wrappers = Map.of(
                "dollar",
                "DollarWrapper",
                "int",
                "IntWrapper",
                "boolean",
                "BooleanWrapper",
                "string",
                "StringWrapper",
                "day",
                "DayWrapper",
                "enum",
                "EnumWrapper",
                "ein",
                "EinWrapper",
                "tin",
                "TinWrapper");
        String alias = tc.draw(sampledFrom(wrappers.keySet().stream().sorted().toList()), "alias");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(tc.draw(sampledFrom(Locale.US, Locale.ROOT, Locale.forLanguageTag("tr-TR")), "locale"));
            String expected = PersisterTypes.PACKAGE_PREFIX + wrappers.get(alias);
            assertThat(PersisterTypes.resolve(alias.toUpperCase(Locale.ROOT), null))
                    .isEqualTo(expected);
            assertThat(PersisterTypes.resolve("unknown:" + tc.draw(text(), "ignoredAlias"), expected))
                    .isEqualTo(expected);
        } finally {
            Locale.setDefault(original);
        }
    }

    @HegelTest(testCases = 3000)
    void rationalParametersPreserveTheirComponents(TestCase tc) {
        int n = tc.draw(integers(), "numerator");
        int d = tc.draw(integers(), "denominator");
        var rational = PersisterTypes.forParameter("rational", n + " / " + d, "/test");
        assertThat(rational.typeCode()).isEqualTo(PersisterTypes.RATIONAL);
        assertThat(rational.value()).isEqualTo(Map.of("n", n, "d", d));
    }

    @HegelTest(testCases = 3000)
    void integerParametersAgreeWithToolValueShaping(TestCase tc) {
        long n = tc.draw(longs(), "integer");
        assertThat(PersisterTypes.shape(PersisterTypes.INT, " " + n + " ")).isEqualTo(n);
        assertThat(PersisterTypes.forParameter("int", Long.toString(n), "/test").value())
                .isEqualTo(n);
    }

    @HegelTest(testCases = 3000)
    void booleanInputAcceptsOnlyTrueOrFalseAndNeverCoercesUnknownToFalse(TestCase tc) {
        String raw = tc.draw(oneOf(text(), sampledFrom("true", "false", " TrUe ", " FALSE ")), "booleanText");
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false"))
            assertThat(PersisterTypes.shape(PersisterTypes.BOOLEAN, raw)).isEqualTo(Boolean.parseBoolean(trimmed));
        else
            assertThatThrownBy(() -> PersisterTypes.shape(PersisterTypes.BOOLEAN, raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown answer unset");
    }
}
