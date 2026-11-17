package com.realworld.tax.advisor.graph;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.Test;

import gov.irs.directfile.models.FactTypeWithItem;

import static dev.hegel.Generators.*;
import static org.assertj.core.api.Assertions.*;

class SessionSnapshotTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void largeSnapshotsPreserveOrderAcrossCopiesAndSerialization() throws Exception {
        Map<String, FactTypeWithItem> facts = new LinkedHashMap<>();
        for (int i = 0; i < 100_000; i++) {
            facts.put("/fact" + i, new FactTypeWithItem(PersisterTypes.INT, MAPPER.valueToTree(i)));
        }
        var snapshot = new SessionSnapshot(2025, FilingStatus.MFJ, facts, Map.of(), "release");
        var changed = snapshot.withSourceNote("/fact0", "source");
        var roundtrip = MAPPER.readValue(MAPPER.writeValueAsBytes(changed), SessionSnapshot.class);
        assertThat(roundtrip).isEqualTo(changed).hasSameHashCodeAs(changed);
        assertThat(roundtrip.facts()).containsExactlyEntriesOf(facts);
        assertThat(snapshot.sourceNotes()).isEmpty();
    }

    @HegelTest(testCases = 1000)
    void nestedFactsCannotBeMutatedThroughConstructorInputsOrAccessors(TestCase tc) {
        int n = tc.draw(integers(), "numerator");
        int d = tc.draw(integers().min(1), "denominator");
        var node = MAPPER.createObjectNode().put("n", n).put("d", d);
        var facts = new LinkedHashMap<String, FactTypeWithItem>();
        facts.put("/ratio", new FactTypeWithItem(PersisterTypes.RATIONAL, node));
        var notes = new LinkedHashMap<String, String>();
        String note = tc.draw(text(), "note");
        notes.put("/ratio", note);
        var snapshot = new SessionSnapshot(2026, FilingStatus.MFJ, facts, notes, "release");
        node.put("n", ~n);
        facts.clear();
        notes.clear();
        assertThat(snapshot.facts().get("/ratio").item().get("n").asInt()).isEqualTo(n);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        snapshot.facts().get("/ratio").item())
                .put("n", ~n);
        assertThat(snapshot.facts().get("/ratio").item().get("n").asInt()).isEqualTo(n);
        assertThat(snapshot.sourceNotes()).containsExactlyEntriesOf(Map.of("/ratio", note));
    }

    @HegelTest(testCases = 1000)
    void updatesPreserveOriginalSnapshotsAndMetadata(TestCase tc) {
        String note = tc.draw(text(), "note");
        var node = MAPPER.createObjectNode()
                .put("n", tc.draw(integers(), "numerator"))
                .put("d", 1);
        var expected = node.deepCopy();
        var original = new SessionSnapshot(2025, FilingStatus.SINGLE, Map.of(), Map.of(), "version");
        var changed = original.withFact("/new", new FactTypeWithItem(PersisterTypes.RATIONAL, node))
                .withSourceNote("/new", note);
        node.removeAll();
        assertThat(original.facts()).isEmpty();
        assertThat(original.sourceNotes()).isEmpty();
        assertThat(changed.facts().get("/new").item()).isEqualTo(expected);
        assertThat(changed.sourceNotes()).containsEntry("/new", note);
        assertThat(changed.knowledgeVersion()).isEqualTo(original.knowledgeVersion());
        assertThat(changed.taxYear()).isEqualTo(original.taxYear());
        assertThat(changed.filingStatus()).isEqualTo(original.filingStatus());
    }
}
