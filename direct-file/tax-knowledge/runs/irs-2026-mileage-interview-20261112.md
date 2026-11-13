# Total-first mileage interview update

Date: 2026-11-12. Review status: draft; tax/content/engineering approvals remain pending.

Users first provide one actual business-mileage total. If the observation period
spans the income year's rate change, the calculation returns `needs_facts` with
one question authored in the year's interview artifact. The total, receipts,
expenses, wages, observation date, and calculation mode are retained in the session.
The reply can supply only one period amount; subtraction determines the remainder.
Existing callers supplying both periods continue to work.

A single-rate year, zero-mile total, or YTD figures ending before the change do not
need a breakdown. The date the figures cover controls this decision. An omitted
date on a pending follow-up retains the original date, rather than advancing with
the server clock. Actual period counts are returned with dated rates; the existing
fact graph separately projects future mileage from observed pace.

Unknown allocations remain pending without guessed actual mileage. Related input
changes are saved in one validated session update. Earlier period facts are removed
on an unresolved replacement, so the graph cannot reuse an old mileage deduction or
dependent tax result. Fresh inputs are required when changing the observation date
or switching between YTD projection and full-year calculation.

The [change record](../changes/federal/2026/mileage-interview-20261112.yaml) carries
exact rate-boundary provenance. The question order and phrasing are product choices,
not asserted IRS requirements. No tax rates changed. The existing graph supports one
rate or two periods separated at a configured month's start; this update does not
add arbitrary numbers of rate periods or automatic allocation estimates.

Validation: `MileageInterviewIntegrationTest` adds nine regression cases for the
initial question, conditional follow-up, saved-input completion, observation dates,
single-rate/zero mileage, either-period subtraction, incomplete downstream results,
invalid allocations, and atomic rollback. Updated 2026 boundary and existing
projection tests also pass. Full `mvn -o clean package` passed 96 tests plus
SpotBugs, PMD, Spotless, and packaging. Python pipeline tests passed (34 tests), and
year-scoped provenance validation reported zero errors and warnings.

The live preview service passed health, MCP initialization, and a 14-tool inventory
check. Tool responses validated against their advertised JSON Schemas, including
pending results with null tax amounts and null unknown period counts. The live
conversation completed a retained August 31 request using only one period answer,
invalidated a superseded tax estimate, completed a full-year request without
re-entering other inputs, and verified pre-change/single-rate flows with no
follow-up. Citations and the versioned preview export also passed.
