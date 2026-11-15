package com.realworld.tax.advisor.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.realworld.tax.advisor.graph.FactPaths;
import com.realworld.tax.advisor.graph.PersisterTypes;
import com.realworld.tax.advisor.graph.PersisterTypes.TypedValue;
import com.realworld.tax.advisor.graph.PlanningGraphService;
import com.realworld.tax.advisor.graph.SessionId;
import com.realworld.tax.advisor.knowledge.TaxKnowledgeService;
import org.springframework.stereotype.Component;

/** Collect a total first; retain incomplete allocations without making up actual mileage. */
@Component
public class MileageService {
    private final PlanningGraphService graph;
    private final TaxKnowledgeService knowledge;

    public MileageService(PlanningGraphService graph, TaxKnowledgeService knowledge) {
        this.graph = graph;
        this.knowledge = knowledge;
    }

    LocalDate projectionDate(SessionId session, String supplied, LocalDate today) {
        if (!ToolSupport.isBlank(supplied)) {
            return LocalDate.parse(supplied);
        }
        Map<String, Object> facts = graph.sessionFacts(session);
        if ((Boolean.TRUE.equals(facts.get(FactPaths.SE_MILEAGE_PENDING))
                        || Boolean.TRUE.equals(facts.get(FactPaths.SE_MILEAGE_ESTIMATED)))
                && Boolean.TRUE.equals(facts.get(FactPaths.SE_MILEAGE_PROJECTED))) {
            return LocalDate.parse(facts.get(FactPaths.SE_MILEAGE_AS_OF).toString());
        }
        return today;
    }

    Allocation apply(
            SessionId session,
            LocalDate asOf,
            boolean projection,
            String totalInput,
            String beforeInput,
            String afterInput,
            Amounts amounts) {
        Map<String, Object> existing = graph.sessionFacts(session);
        boolean resume = (Boolean.TRUE.equals(existing.get(FactPaths.SE_MILEAGE_PENDING))
                        || Boolean.TRUE.equals(existing.get(FactPaths.SE_MILEAGE_ESTIMATED)))
                && Boolean.valueOf(projection).equals(existing.get(FactPaths.SE_MILEAGE_PROJECTED))
                && asOf.toString().equals(existing.get(FactPaths.SE_MILEAGE_AS_OF));
        boolean hasTotal = !ToolSupport.isBlank(totalInput) || resume;
        long total = !ToolSupport.isBlank(totalInput)
                ? miles(totalInput)
                : resume ? ((Number) existing.get(FactPaths.SE_VEHICLE_BUSINESS_MILES)).longValue() : 0;
        Long before = suppliedMiles(beforeInput, existing, FactPaths.SE_MILES_BEFORE_CHANGE, resume);
        Long after = suppliedMiles(afterInput, existing, FactPaths.SE_MILES_AFTER_CHANGE, resume);
        LocalDate change = changeDate(session);
        Allocation allocation = resolve(asOf, change, total, hasTotal, before, after);
        if (allocation.pending() && !Boolean.TRUE.equals(existing.get(FactPaths.SE_MILEAGE_REQUIRE_EXACT))) {
            allocation = new Allocation(allocation.total(), null, null, false, true);
        }
        Map<String, TypedValue> values = new LinkedHashMap<>();
        put(
                values,
                FactPaths.SE_GROSS_RECEIPTS,
                PersisterTypes.DOLLAR,
                amount(amounts.gross(), existing, FactPaths.SE_GROSS_RECEIPTS, resume, true));
        put(
                values,
                FactPaths.SE_PLATFORM_FEES,
                PersisterTypes.DOLLAR,
                amount(amounts.fees(), existing, FactPaths.SE_PLATFORM_FEES, resume, false));
        put(
                values,
                FactPaths.SE_SUPPLIES_AND_OTHER,
                PersisterTypes.DOLLAR,
                amount(amounts.supplies(), existing, FactPaths.SE_SUPPLIES_AND_OTHER, resume, false));
        put(
                values,
                FactPaths.SE_SOCIAL_SECURITY_WAGES_FROM_W2,
                PersisterTypes.DOLLAR,
                amount(amounts.wages(), existing, FactPaths.SE_SOCIAL_SECURITY_WAGES_FROM_W2, resume, false));
        put(values, FactPaths.SE_VEHICLE_BUSINESS_MILES, PersisterTypes.INT, allocation.total());
        put(values, FactPaths.SE_MILEAGE_PENDING, PersisterTypes.BOOLEAN, allocation.pending());
        put(values, FactPaths.SE_MILEAGE_ESTIMATED, PersisterTypes.BOOLEAN, allocation.estimated());
        put(
                values,
                FactPaths.SE_MILEAGE_ASSUMPTION,
                PersisterTypes.resolve("string", null),
                allocation.estimated() ? assumption(asOf) : "");
        put(values, FactPaths.SE_MILEAGE_AS_OF, PersisterTypes.resolve("string", null), asOf.toString());
        put(values, FactPaths.SE_MILEAGE_PROJECTED, PersisterTypes.BOOLEAN, projection);
        int months = projection ? asOf.getMonthValue() : 12;
        put(values, FactPaths.SE_MONTHS_ELAPSED, PersisterTypes.INT, months);
        put(
                values,
                FactPaths.SE_ANNUALIZATION_FACTOR,
                PersisterTypes.RATIONAL,
                PersisterTypes.rational(projection ? 12 : 1, projection ? months : 1));
        if (allocation.estimated()) {
            // Only the elapsed-day shares are policy inputs. The graph allocates miles and
            // computes the deduction; no estimated period counts become recorded actuals.
            int firstDays = (int) ChronoUnit.DAYS.between(asOf.withDayOfYear(1), change);
            int coveredDays = asOf.getDayOfYear();
            int laterDays = coveredDays - firstDays;
            put(
                    values,
                    FactPaths.SE_MILEAGE_FIRST_SHARE,
                    PersisterTypes.RATIONAL,
                    PersisterTypes.rational(firstDays, coveredDays));
            put(
                    values,
                    FactPaths.SE_MILEAGE_SECOND_SHARE,
                    PersisterTypes.RATIONAL,
                    PersisterTypes.rational(laterDays, coveredDays));
            put(values, FactPaths.SE_MILEAGE_FIRST_FACTOR, PersisterTypes.RATIONAL, PersisterTypes.rational(1, 1));
            put(
                    values,
                    FactPaths.SE_MILEAGE_SECOND_FACTOR,
                    PersisterTypes.RATIONAL,
                    PersisterTypes.rational(
                            projection ? asOf.lengthOfYear() - firstDays : 1, projection ? laterDays : 1));
        } else if (!allocation.pending() && change != null) {
            put(values, FactPaths.SE_MILES_BEFORE_CHANGE, PersisterTypes.INT, allocation.before());
            put(values, FactPaths.SE_MILES_AFTER_CHANGE, PersisterTypes.INT, allocation.after());
            int month = change.getMonthValue();
            put(
                    values,
                    FactPaths.SE_MILEAGE_FIRST_FACTOR,
                    PersisterTypes.RATIONAL,
                    PersisterTypes.rational(
                            projection && months < month ? month - 1 : 1, projection && months < month ? months : 1));
            put(
                    values,
                    FactPaths.SE_MILEAGE_SECOND_FACTOR,
                    PersisterTypes.RATIONAL,
                    PersisterTypes.rational(
                            projection ? 13 - month : 1,
                            projection ? (months < month ? months : months - month + 1) : 1));
        }
        // Replace allocation inputs as one batch. Estimated counts never enter the actuals,
        // and opting into exact-only mode invalidates any previous estimated calculation.
        Set<String> removals = Set.of(
                FactPaths.SE_MILES_BEFORE_CHANGE,
                FactPaths.SE_MILES_AFTER_CHANGE,
                FactPaths.SE_MILEAGE_FIRST_FACTOR,
                FactPaths.SE_MILEAGE_SECOND_FACTOR,
                FactPaths.SE_MILEAGE_FIRST_SHARE,
                FactPaths.SE_MILEAGE_SECOND_SHARE);
        graph.writeFacts(session, values, removals);
        return allocation;
    }

    private static Allocation resolve(
            LocalDate asOf, LocalDate change, long total, boolean hasTotal, Long beforeInput, Long afterInput) {
        Long before = beforeInput;
        Long after = afterInput;
        // Some MCP clients fill optional period fields with zero placeholders. A
        // 0/0 pair supplies no allocation of a positive total: retain the total
        // and use the disclosed estimate (or exact-only pending state). Other
        // inconsistent or negative period amounts still fail before any writes.
        if (hasTotal && Long.valueOf(0).equals(before) && Long.valueOf(0).equals(after)) {
            before = null;
            after = null;
        }
        if (change == null) {
            if (before != null || after != null) {
                throw new IllegalArgumentException("This income year has a single mileage rate; supply businessMiles.");
            }
            return new Allocation(total, total, 0L, false);
        }
        if (before == null && after == null) {
            return !asOf.isBefore(change) && total > 0
                    ? new Allocation(total, null, null, true)
                    : new Allocation(total, total, 0L, false);
        }
        if (hasTotal) {
            if (before == null) {
                before = total - after;
            }
            if (after == null) {
                after = total - before;
            }
        }
        if (before == null || after == null) {
            throw new IllegalArgumentException("Supply total business miles or both period totals.");
        }
        if (before < 0 || after < 0) {
            throw new IllegalArgumentException("Period mileage cannot exceed total business miles.");
        }
        long sum = Math.addExact(before, after);
        if (hasTotal && total != sum) {
            throw new IllegalArgumentException(
                    "The two mileage-period totals must equal the supplied total business miles.");
        }
        if (asOf.isBefore(change) && after > 0) {
            throw new IllegalArgumentException(
                    "Year-to-date miles after the rate change cannot be positive before " + change);
        }
        return new Allocation(sum, before, after, false);
    }

    LocalDate changeDate(SessionId session) {
        if (!Boolean.TRUE.equals(
                graph.readFact(session, FactPaths.SE_MILEAGE_SPLIT_RATE).value())) {
            return null;
        }
        int month = ((Number) graph.readFact(session, FactPaths.SE_MILEAGE_CHANGE_MONTH)
                        .value())
                .intValue();
        if (month < 2 || month > 12) {
            throw new IllegalStateException("Split-rate mileage requires a change month from 2 through 12.");
        }
        return LocalDate.of(graph.taxYearOf(session), month, 1);
    }

    List<RatePeriod> periods(SessionId session, Allocation allocation) {
        int year = graph.taxYearOf(session);
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        LocalDate change = changeDate(session);
        BigDecimal firstRate = graph.readDecimal(session, FactPaths.STANDARD_MILEAGE_RATE);
        if (change == null) {
            return List.of(new RatePeriod(
                    start.toString(),
                    end.toString(),
                    firstRate,
                    allocation.total(),
                    null,
                    graph.readDecimal(session, FactPaths.SE_PROJECTED_MILES)));
        }
        return List.of(
                new RatePeriod(
                        start.toString(),
                        change.minusDays(1).toString(),
                        firstRate,
                        allocation.before(),
                        allocation.estimated()
                                ? graph.readDecimal(session, FactPaths.SE_MILEAGE_CALCULATED_BEFORE)
                                : null,
                        graph.readDecimal(session, FactPaths.SE_PROJECTED_MILES_BEFORE)),
                new RatePeriod(
                        change.toString(),
                        end.toString(),
                        graph.readDecimal(session, FactPaths.STANDARD_MILEAGE_RATE_AFTER_CHANGE),
                        allocation.after(),
                        allocation.estimated()
                                ? graph.readDecimal(session, FactPaths.SE_MILEAGE_CALCULATED_AFTER)
                                : null,
                        graph.readDecimal(session, FactPaths.SE_PROJECTED_MILES_AFTER)));
    }

    List<FollowUp> followUps(SessionId session, boolean projection) {
        // Question wording, scope, and authority stay in the year-specific interview artifacts.
        return knowledge
                .plan(new TaxKnowledgeService.PlanRequest(
                        graph.taxYearOf(session),
                        "federal",
                        graph.sessionFacts(session),
                        List.of(),
                        Map.of(),
                        false,
                        null))
                .questions()
                .stream()
                .filter(question -> question.writes().contains(FactPaths.SE_MILES_BEFORE_CHANGE))
                .map(question -> new FollowUp(
                        question.questionId(),
                        question.prompt(),
                        question.helpText(),
                        projection ? "ytdBusinessMilesBeforeRateChange" : "businessMilesBeforeRateChange",
                        FactPaths.SE_MILES_BEFORE_CHANGE))
                .toList();
    }

    private static Long suppliedMiles(String supplied, Map<String, Object> existing, String path, boolean resume) {
        return !ToolSupport.isBlank(supplied)
                ? miles(supplied)
                : resume && existing.get(path) instanceof Number number ? number.longValue() : null;
    }

    private static long miles(String value) {
        long result = Long.parseLong(value.trim());
        if (result < 0) {
            throw new IllegalArgumentException("Business miles must be a nonnegative whole number.");
        }
        return result;
    }

    private static String amount(
            String supplied, Map<String, Object> existing, String path, boolean resume, boolean required) {
        if (!ToolSupport.isBlank(supplied)) {
            return supplied.trim();
        }
        if (resume && existing.containsKey(path)) {
            return existing.get(path).toString();
        }
        if (required) {
            throw new IllegalArgumentException("Supply gross receipts when starting or replacing a calculation.");
        }
        return "0";
    }

    private static void put(Map<String, TypedValue> values, String path, String type, Object value) {
        values.put(path, new TypedValue(type, value));
    }

    String allocationNote(SessionId session) {
        Object value = graph.readFact(session, FactPaths.SE_MILEAGE_ASSUMPTION).value();
        return value == null ? "" : value.toString();
    }

    RecordedInputs recordedInputs(SessionId session) {
        return new RecordedInputs(
                graph.readDecimal(session, FactPaths.SE_GROSS_RECEIPTS),
                graph.readFact(session, FactPaths.SE_VEHICLE_BUSINESS_MILES).value(),
                graph.readDecimal(session, FactPaths.SE_PLATFORM_FEES),
                graph.readDecimal(session, FactPaths.SE_SUPPLIES_AND_OTHER),
                graph.readDecimal(session, FactPaths.SE_SOCIAL_SECURITY_WAGES_FROM_W2));
    }

    private static String assumption(LocalDate asOf) {
        return "Mileage split estimated for planning: business miles are assumed evenly spread by calendar day from "
                + asOf.withDayOfYear(1) + " through " + asOf
                + ". Period amounts are estimates, not actual mileage records. Provide either period's actual miles to refine this estimate.";
    }

    record Amounts(String gross, String fees, String supplies, String wages) {}

    record Allocation(long total, Long before, Long after, boolean pending, boolean estimated) {
        Allocation(long total, Long before, Long after, boolean pending) {
            this(total, before, after, pending, false);
        }
    }

    public record RatePeriod(
            String startDate,
            String endDate,
            BigDecimal rate,
            Object actualBusinessMiles,
            Object estimatedBusinessMiles,
            Object projectedBusinessMiles) {}

    public record FollowUp(String questionId, String prompt, String helpText, String argumentName, String factPath) {}

    public record RecordedInputs(
            Object grossReceipts,
            Object businessMiles,
            Object platformFees,
            Object suppliesAndOther,
            Object socialSecurityWagesFromW2) {}
}
