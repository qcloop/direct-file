package com.realworld.tax.advisor.graph;

import java.util.UUID;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.Test;

import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionIdTest {

    @HegelTest(testCases = 3000)
    void idsPreserveTheirExactValueAndEquality(TestCase tc) {
        String raw = tc.draw(text(), "id");
        if (raw.isBlank()) {
            assertThatThrownBy(() -> SessionId.of(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("create_session");
        } else {
            assertThat(SessionId.of(raw))
                    .isEqualTo(new SessionId(raw))
                    .hasSameHashCodeAs(new SessionId(raw))
                    .hasToString(raw);
            assertThat(SessionId.of(raw).value()).isEqualTo(raw);
            assertThat(SessionId.of(raw)).isNotEqualTo(SessionId.of(raw + "x"));
        }
    }

    @Test
    void aMissingIdIsRejectedWithGuidanceRatherThanLookedUp() {
        assertThatThrownBy(() -> SessionId.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("create_session");
        assertThatThrownBy(() -> SessionId.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @HegelTest(testCases = 1000)
    void whitespaceOnlyIdsAreRejectedWithGuidance(TestCase tc) {
        String blank = tc.draw(lists(sampledFrom(" ", "\t", "\n", "\r", "\u2003")), "whitespace").stream()
                .reduce("", String::concat);
        assertThatThrownBy(() -> new SessionId(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("create_session");
        assertThatThrownBy(() -> SessionId.of(blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freshIdsAreDistinctVersionFourUuids() {
        SessionId fresh = SessionId.random();
        assertThat(UUID.fromString(fresh.value()).version()).isEqualTo(4);
        assertThat(fresh).isNotEqualTo(SessionId.random());
    }
}
