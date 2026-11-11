package com.realworld.tax.advisor.graph;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import gov.irs.directfile.models.FactTypeWithItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link SessionStore} must do, whatever it stores sessions in. Each implementation's test
 * class extends this, so the in-memory and Redis stores are held to the same behavior — the service
 * above them cannot tell which one it is talking to.
 */
abstract class SessionStoreContract {

    protected static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    protected abstract SessionStore store();

    private static FactTypeWithItem dollars(String amount) {
        return new FactTypeWithItem(PersisterTypes.DOLLAR, MAPPER.valueToTree(amount));
    }

    private static SessionSnapshot newSession() {
        return new SessionSnapshot(
                2025,
                FilingStatus.MFJ,
                Map.of("/seGrossReceipts", dollars("60000")),
                Map.of("/seGrossReceipts", "2025 1099-K"));
    }

    @Test
    void aCreatedSessionIsReadBackIntact() {
        SessionId id = SessionId.random();
        store().create(id, newSession());

        SessionSnapshot read = store().find(id).orElseThrow().snapshot();
        assertThat(read.taxYear()).isEqualTo(2025);
        assertThat(read.filingStatus()).isEqualTo(FilingStatus.MFJ);
        assertThat(read.facts()).isEqualTo(newSession().facts());
        assertThat(read.sourceNotes()).containsEntry("/seGrossReceipts", "2025 1099-K");
        store().delete(id);
    }

    @Test
    void factsKeepTheOrderTheyWereWrittenIn() {
        SessionId id = SessionId.random();
        SessionSnapshot ordered = newSession()
                .withFact("/zLast", dollars("1"))
                .withFact("/aFirst", dollars("2"))
                .withFact("/mMiddle", dollars("3"));
        store().create(id, ordered);

        assertThat(store().find(id).orElseThrow().snapshot().facts().keySet())
                .containsExactly("/seGrossReceipts", "/zLast", "/aFirst", "/mMiddle");
        store().delete(id);
    }

    @Test
    void anUnknownSessionIsAbsent() {
        assertThat(store().find(SessionId.random())).isEmpty();
    }

    @Test
    void replaceStoresTheUpdateWhenTheSessionIsUnchanged() {
        SessionId id = SessionId.random();
        store().create(id, newSession());
        SessionStore.Stored read = store().find(id).orElseThrow();

        boolean replaced = store().replace(id, read, read.snapshot().withFact("/sePlatformFees", dollars("1500")));

        assertThat(replaced).isTrue();
        SessionStore.Stored after = store().find(id).orElseThrow();
        assertThat(after.snapshot().facts()).containsKey("/sePlatformFees");
        assertThat(after.version()).isGreaterThan(read.version());
        store().delete(id);
    }

    @Test
    void replaceRefusesAStaleReadSoAConcurrentWriteIsNotLost() {
        SessionId id = SessionId.random();
        store().create(id, newSession());
        SessionStore.Stored first = store().find(id).orElseThrow();
        SessionStore.Stored second = store().find(id).orElseThrow();

        assertThat(store().replace(id, first, first.snapshot().withFact("/a", dollars("1"))))
                .isTrue();
        // `second` was read before that write landed; applying it would silently drop /a.
        assertThat(store().replace(id, second, second.snapshot().withFact("/b", dollars("2"))))
                .isFalse();

        assertThat(store().find(id).orElseThrow().snapshot().facts())
                .containsKey("/a")
                .doesNotContainKey("/b");
        store().delete(id);
    }

    @Test
    void replaceDoesNotResurrectADeletedSession() {
        SessionId id = SessionId.random();
        store().create(id, newSession());
        SessionStore.Stored read = store().find(id).orElseThrow();
        store().delete(id);

        assertThat(store().replace(id, read, read.snapshot().withFact("/a", dollars("1"))))
                .isFalse();
        assertThat(store().find(id)).isEmpty();
    }

    @Test
    void deletingAnUnknownSessionIsHarmless() {
        store().delete(SessionId.random());
    }
}
