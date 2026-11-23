package com.realworld.tax.advisor.graph;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;

import gov.irs.directfile.models.FactTypeWithItem;

import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.*;

/** Both store implementations run the same generated roundtrip, ordering, and optimistic-update contracts. */
abstract class SessionStoreContract {
    protected static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    protected abstract SessionStore store();

    private static FactTypeWithItem dollars(String amount) {
        return new FactTypeWithItem(PersisterTypes.DOLLAR, MAPPER.valueToTree(amount));
    }

    private static SessionSnapshot newSession(TestCase tc) {
        var ratio = MAPPER.createObjectNode()
                .put("n", tc.draw(integers(), "numerator"))
                .put("d", tc.draw(integers().min(1), "denominator"));
        return new SessionSnapshot(
                tc.draw(integers(), "year"),
                tc.draw(sampledFrom(FilingStatus.values()), "status"),
                Map.of(
                        "/amount",
                        dollars(tc.draw(integers(), "amount").toString()),
                        "/ratio",
                        new FactTypeWithItem(PersisterTypes.RATIONAL, ratio)),
                Map.of("/amount", tc.draw(text(), "note")),
                tc.draw(text(), "knowledgeVersion"));
    }

    @HegelTest(testCases = 3000)
    void aCreatedSessionRoundtripsAllMetadataFactsAndProvenance(TestCase tc) {
        SessionId id = SessionId.random();
        SessionSnapshot expected = newSession(tc);
        try {
            store().create(id, expected);
            SessionStore.Stored read = store().find(id).orElseThrow();
            assertThat(read.snapshot()).isEqualTo(expected);
            assertThat(read.version()).isZero();
        } finally {
            store().delete(id);
        }
    }

    @HegelTest(testCases = 3000)
    void replacementPreservesInsertionOrderIncludingDuplicatePaths(TestCase tc) {
        SessionId id = SessionId.random();
        SessionSnapshot snapshot = newSession(tc);
        Map<String, FactTypeWithItem> expected = new LinkedHashMap<>(snapshot.facts());
        for (String suffix : tc.draw(lists(text()), "paths")) {
            String path = "/" + suffix;
            FactTypeWithItem value = dollars(tc.draw(integers(), "value").toString());
            snapshot = snapshot.withFact(path, value);
            expected.put(path, value);
        }
        try {
            store().create(id, snapshot);
            var actual = store().find(id).orElseThrow().snapshot();
            assertThat(actual.facts()).containsExactlyEntriesOf(expected);
        } finally {
            store().delete(id);
        }
    }

    @HegelTest(testCases = 3000)
    void exactlyOneWriterCanReplaceTheVersionItRead(TestCase tc) {
        SessionId id = SessionId.random();
        SessionSnapshot snapshot = newSession(tc);
        SessionId unrelated = SessionId.random();
        try {
            store().create(id, snapshot);
            store().create(unrelated, snapshot);
            SessionStore.Stored first = store().find(id).orElseThrow();
            SessionStore.Stored second = store().find(id).orElseThrow();
            String winner = tc.draw(text(), "winningNote");
            String loser = winner + "loser";
            assertThat(store().replace(id, first, snapshot.withSourceNote("/winner", winner)))
                    .isTrue();
            assertThat(store().replace(id, second, snapshot.withSourceNote("/loser", loser)))
                    .isFalse();
            SessionStore.Stored after = store().find(id).orElseThrow();
            assertThat(after.snapshot()).isEqualTo(snapshot.withSourceNote("/winner", winner));
            assertThat(after.version()).isEqualTo(first.version() + 1);
            assertThat(store().find(unrelated).orElseThrow().snapshot()).isEqualTo(snapshot);
        } finally {
            store().delete(id);
            store().delete(unrelated);
        }
    }

    @HegelTest(testCases = 3000)
    void deletionIsIdempotentAndStaleWritesCannotResurrectSessions(TestCase tc) {
        SessionId id = SessionId.random();
        SessionSnapshot snapshot = newSession(tc);
        try {
            assertThat(store().find(id)).isEmpty();
            store().delete(id);
            store().create(id, snapshot);
            SessionStore.Stored read = store().find(id).orElseThrow();
            store().delete(id);
            store().delete(id);
            assertThat(store().replace(id, read, snapshot.withSourceNote("/deleted", tc.draw(text(), "note"))))
                    .isFalse();
            assertThat(store().find(id)).isEmpty();
        } finally {
            store().delete(id);
        }
    }
}
