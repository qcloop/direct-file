package com.realworld.tax.advisor.mcp;

import java.time.LocalDate;
import java.util.List;

/**
 * Federal individual estimated-tax deadlines (IRC §6654) for one tax year: the Apr 15 / Jun 15 /
 * Sep 15 installments in that year and the Jan 15 installment in the next. Derived from the tax year
 * (which the session is created with) rather than hard-coded, and pushed to the next Monday when the
 * 15th lands on a weekend (federal-holiday shifts, e.g. Emancipation Day, are not modeled — treat
 * these as planning dates, not authoritative filing dates).
 */
final class EstimatedTaxSchedule {

    private final List<Deadline> deadlines;

    private EstimatedTaxSchedule(List<Deadline> deadlines) {
        this.deadlines = deadlines;
    }

    static EstimatedTaxSchedule forTaxYear(int taxYear) {
        return new EstimatedTaxSchedule(List.of(
                new Deadline("Q1", weekdayOnOrAfter(LocalDate.of(taxYear, 4, 15))),
                new Deadline("Q2", weekdayOnOrAfter(LocalDate.of(taxYear, 6, 15))),
                new Deadline("Q3", weekdayOnOrAfter(LocalDate.of(taxYear, 9, 15))),
                new Deadline("Q4", weekdayOnOrAfter(LocalDate.of(taxYear + 1, 1, 15)))));
    }

    /** The first deadline still ahead of {@code asOf}, or the final (Jan 15) one once all have passed. */
    Deadline nextOnOrAfter(LocalDate asOf) {
        return deadlines.stream()
                .filter(deadline -> deadline.isOnOrAfter(asOf))
                .findFirst()
                .orElse(deadlines.get(deadlines.size() - 1));
    }

    /**
     * Installments still ahead of {@code asOf}. Past the final (Jan 15) deadline this is 1 rather than
     * 0, so the remaining amount is recommended as a single catch-up payment.
     */
    long remainingQuarters(LocalDate asOf) {
        long remaining = deadlines.stream()
                .filter(deadline -> deadline.isOnOrAfter(asOf))
                .count();
        return remaining == 0 ? 1 : remaining;
    }

    /** Push a date to the following Monday if it falls on a weekend. */
    private static LocalDate weekdayOnOrAfter(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY -> d.plusDays(2);
            case SUNDAY -> d.plusDays(1);
            default -> d;
        };
    }

    record Deadline(String quarter, LocalDate due) {
        boolean isOnOrAfter(LocalDate date) {
            return !due.isBefore(date);
        }
    }
}
