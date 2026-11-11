package com.realworld.tax.advisor.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import gov.irs.directfile.models.FactTypeWithItem;

/**
 * The complete state of one planning session at a point in time: the year and filing status it was
 * created with, every fact written into it (injected year parameters and taxpayer inputs alike, in
 * insertion order), and the taxpayer-stated provenance notes.
 *
 * <p>Immutable — a change produces a new snapshot — so a session can live in a shared store and be
 * updated with compare-and-set instead of a lock that only one JVM could hold. The fact graph itself
 * is never stored: it is rebuilt from {@link #facts()} whenever a value is read or validated.
 *
 * <p>Serialized as JSON by {@link SessionStore} implementations that keep sessions outside the JVM;
 * {@link FactTypeWithItem} already carries its value as a (Jackson 2) JSON node.
 */
public record SessionSnapshot(
        int taxYear, FilingStatus filingStatus, Map<String, FactTypeWithItem> facts, Map<String, String> sourceNotes) {

    public SessionSnapshot {
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        sourceNotes = Collections.unmodifiableMap(new LinkedHashMap<>(sourceNotes));
    }

    /** This session with {@code path} set to {@code fact} (replacing any earlier value in place). */
    public SessionSnapshot withFact(String path, FactTypeWithItem fact) {
        Map<String, FactTypeWithItem> updated = new LinkedHashMap<>(facts);
        updated.put(path, fact);
        return new SessionSnapshot(taxYear, filingStatus, updated, sourceNotes);
    }

    /** This session with the provenance note for {@code path} set to {@code note}. */
    public SessionSnapshot withSourceNote(String path, String note) {
        Map<String, String> updated = new LinkedHashMap<>(sourceNotes);
        updated.put(path, note);
        return new SessionSnapshot(taxYear, filingStatus, facts, updated);
    }
}
