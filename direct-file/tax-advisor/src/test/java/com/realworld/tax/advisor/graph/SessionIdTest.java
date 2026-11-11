package com.realworld.tax.advisor.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionIdTest {

    @Test
    void idsWithTheSameValueAreTheSameSession() {
        assertThat(SessionId.of("abc")).isEqualTo(SessionId.of("abc")).hasSameHashCodeAs(SessionId.of("abc"));
        assertThat(SessionId.of("abc")).isNotEqualTo(SessionId.of("abd"));
    }

    @Test
    void rendersAsTheBareIdSoMessagesAndLogsAreUnchanged() {
        assertThat(SessionId.of("abc")).hasToString("abc");
        assertThat("session " + SessionId.of("abc")).isEqualTo("session abc");
    }

    @Test
    void aMissingIdIsRejectedWithGuidanceRatherThanLookedUp() {
        assertThatThrownBy(() -> SessionId.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("create_session");
        assertThatThrownBy(() -> SessionId.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freshIdsAreDistinct() {
        assertThat(SessionId.random()).isNotEqualTo(SessionId.random());
    }
}
