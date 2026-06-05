package gov.irs.directfile.planservice.report;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import gov.irs.directfile.planservice.graph.PlanningGraphService;
import gov.irs.directfile.planservice.mcp.PlanningTools;
import gov.irs.directfile.planservice.report.PlanReportService.PlanReport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The taxpayer-held export: a self-contained, SHA-256-sealed planning summary the service hands
 * back rather than storing. These tests prove the artifact (a) carries the three required sections
 * with their provenance and the self-reported/unverified labelling, and (b) is genuinely tamper-
 * evident — recomputing the documented hash over the body reproduces the seal.
 */
@SpringBootTest
class PlanReportTest {

    @Autowired
    PlanningGraphService graph;

    @Autowired
    PlanningTools tools;

    @Autowired
    PlanReportService reportService;

    /** A gig-worker session whose SE-tax and quarterly figures are all computed before export. */
    private String seededSession() {
        String sid = (String) tools.createSession("2025", null).get("sessionId");
        tools.calculateSeTax(sid, "40000", "10000", "3000", "1000", "0");
        tools.setFact(sid, "/planning/priorYearTotalTax", "dollar", null, "3800", "2024 Form 1040, line 24");
        tools.setFact(sid, "/planning/priorYearAGI", "dollar", null, "58000", null);
        tools.setFact(sid, "/planning/projectedCurrentYearTax", "dollar", null, "7200", null);
        tools.setFact(sid, "/planning/ytdWithholding", "dollar", null, "400", null);
        tools.setFact(sid, "/planning/ytdEstimatedPaymentsMade", "dollar", null, "1800", null);
        // Populates /planning/remainingQuarters so the suggested-payment result row renders.
        tools.estimateQuarterlyPayment(sid, "2025-08-20");
        return sid;
    }

    @Test
    void reportCarriesTheThreeSectionsWithProvenanceAndDisclaimer() {
        PlanReport report = reportService.generate(seededSession());
        String md = report.markdown();

        assertThat(report.taxYear()).isEqualTo(2025);
        assertThat(md).contains("Tax Year 2025");

        // Honest about what it is and is not.
        assertThat(md).contains("planning estimate, not a filed tax return");
        assertThat(md).contains("self-reported");
        assertThat(md).contains("not stored by the service");

        // Three sections present.
        assertThat(md).contains("## Your inputs (self-reported, unverified)");
        assertThat(md).contains("## Tax-year parameters used");
        assertThat(md).contains("## Results");

        // Inputs and results are traceable.
        assertThat(md).contains("Gross receipts"); // a self-reported input
        assertThat(md).contains("Self-employment tax"); // a computed result
        assertThat(md).contains("computed from:"); // one-level derivation shown

        // A taxpayer-stated source note rides through to the inputs table.
        assertThat(md).contains("2024 Form 1040, line 24");

        // With citations on (the default), authorities resolve to formal citations, each result
        // names the basis behind it, and a friendly plain-language Sources section is appended.
        assertThat(md).contains("26 U.S.C. § 6654");
        assertThat(md).contains("based on:");
        assertThat(md).contains("## Sources & plain-language");
        assertThat(md).contains("In plain terms:");

        // 2025 is finalized, so no provisional banner.
        assertThat(md).doesNotContain("Provisional values");
    }

    @Test
    void flagsProvisionalParametersInTheReportForADraftYear() {
        String sid = (String) tools.createSession("2026", null).get("sessionId");
        tools.calculateSeTax(sid, "40000", "10000", "0", "0", "0");
        String md = reportService.generate(sid).markdown();

        // 2026's draft constants surface as a banner and are marked in the parameter table.
        assertThat(md).contains("Provisional values for 2026");
        assertThat(md).contains("⚠ provisional");
    }

    @Test
    void citationsCanBeOmittedAndLeaveTheRawSourceIds() {
        String sid = seededSession();
        String without = reportService.generate(sid, false).markdown();
        String with = reportService.generate(sid, true).markdown();

        // Off: no citation prose, raw source ids retained in the parameter table.
        assertThat(without).doesNotContain("## Sources & plain-language");
        assertThat(without).doesNotContain("based on:");
        assertThat(without).contains("irc_6654");

        // On: the same session gains the citation layer.
        assertThat(with).contains("## Sources & plain-language");
        assertThat(with).contains("26 U.S.C. § 6654");
    }

    @Test
    void citeToolExplainsAuthorityInPlainLanguageFromTheComputation() {
        String sid = seededSession();

        // Self-employment tax flows from the Schedule SE rates and the SSA wage base, so those are
        // the authorities the cite tool surfaces — derived from the computation, not hand-mapped.
        Map<String, Object> seTax = tools.cite(sid, "/seTax");
        assertThat(seTax).containsEntry("status", "ok");
        @SuppressWarnings("unchecked")
        var authorities = (java.util.List<Map<String, Object>>) seTax.get("authorities");
        assertThat(authorities).isNotEmpty();
        assertThat(authorities).anySatisfy(a -> {
            assertThat((String) a.get("citation")).contains("Schedule SE");
            assertThat((String) a.get("plain_language")).isNotBlank();
            assertThat((String) a.get("url")).startsWith("http");
        });

        // The safe-harbor target is governed by IRC § 6654.
        Map<String, Object> safeHarbor = tools.cite(sid, "/planning/safeHarborTarget");
        @SuppressWarnings("unchecked")
        var shAuthorities = (java.util.List<Map<String, Object>>) safeHarbor.get("authorities");
        assertThat(shAuthorities)
                .anySatisfy(a -> assertThat((String) a.get("citation")).contains("§ 6654"));
    }

    @Test
    void hashIsTamperEvidentOverTheDocumentedBody() {
        PlanReport report = reportService.generate(seededSession());
        String md = report.markdown();

        // Seal is present and well-formed.
        assertThat(md).contains("## Integrity");
        assertThat(md).contains(report.sha256());
        assertThat(report.sha256()).matches("[0-9a-f]{64}");

        // Re-run the exact verification the footer documents: hash everything before the
        // integrity delimiter; it must reproduce the seal.
        int idx = md.indexOf(PlanReportService.INTEGRITY_DELIMITER);
        assertThat(idx).isPositive();
        String body = md.substring(0, idx);
        assertThat(PlanReportService.sha256Hex(body)).isEqualTo(report.sha256());

        // Any edit to the body breaks the seal.
        assertThat(PlanReportService.sha256Hex(body + " ")).isNotEqualTo(report.sha256());
    }

    @Test
    void exportPlanToolReturnsTheArtifactWithoutPersisting() {
        Map<String, Object> out = tools.exportPlan(seededSession(), null);

        assertThat(out).containsEntry("status", "ok");
        assertThat(out).containsEntry("tax_year", 2025);
        assertThat(out).containsEntry("hash_algorithm", "SHA-256");
        assertThat((String) out.get("sha256")).matches("[0-9a-f]{64}");
        assertThat((String) out.get("report_markdown")).contains("Tax Year 2025");
    }
}
