package com.realworld.tax.advisor.graph;

/**
 * Paths of the fact-dictionary facts this service reads and writes by name (production
 * {@code tax/*.xml} plus the planning {@code tax-plan/*.xml}). Kept in one place so a path renamed in
 * the dictionary XML is changed once here rather than hunted down as string literals.
 */
public final class FactPaths {

    // Schedule C inputs
    public static final String SE_GROSS_RECEIPTS = "/seGrossReceipts";
    public static final String SE_VEHICLE_BUSINESS_MILES = "/seVehicleBusinessMiles";
    public static final String SE_PLATFORM_FEES = "/sePlatformFees";
    public static final String SE_SUPPLIES_AND_OTHER = "/seSuppliesAndOther";
    public static final String SE_SOCIAL_SECURITY_WAGES_FROM_W2 = "/seSocialSecurityWagesFromW2";
    public static final String SE_MONTHS_ELAPSED = "/seMonthsElapsed";
    public static final String SE_ANNUALIZATION_FACTOR = "/seAnnualizationFactor";

    // Schedule C / Schedule SE results
    public static final String STANDARD_MILEAGE_RATE = "/standardMileageRate";
    public static final String SE_EFFECTIVE_GROSS_RECEIPTS = "/seEffectiveGrossReceipts";
    public static final String SE_NET_PROFIT = "/seNetProfit";
    public static final String SE_NET_EARNINGS = "/seNetEarnings";
    public static final String SE_SOCIAL_SECURITY_TAX = "/seSocialSecurityTax";
    public static final String SE_MEDICARE_TAX = "/seMedicareTax";
    public static final String SE_TAX = "/seTax";
    public static final String DEDUCTIBLE_HALF_OF_SE_TAX = "/deductibleHalfOfSETax";

    // Additional Medicare Tax (Form 8959)
    public static final String MEDICARE_WAGES_FROM_W2 = "/medicareWagesFromW2";
    public static final String ADDITIONAL_MEDICARE_THRESHOLD = "/additionalMedicareThreshold";
    public static final String SE_INCOME_SUBJECT_TO_ADDITIONAL_MEDICARE = "/seIncomeSubjectToAdditionalMedicare";
    public static final String ADDITIONAL_MEDICARE_TAX_ON_SE = "/additionalMedicareTaxOnSE";
    public static final String WAGES_SUBJECT_TO_ADDITIONAL_MEDICARE = "/wagesSubjectToAdditionalMedicare";
    public static final String ADDITIONAL_MEDICARE_TAX_ON_WAGES = "/additionalMedicareTaxOnWages";
    public static final String ADDITIONAL_MEDICARE_TAX = "/additionalMedicareTax";

    // QBI deduction (Form 8995)
    public static final String QUALIFIED_BUSINESS_INCOME = "/qualifiedBusinessIncome";
    public static final String QBI_COMPONENT = "/qbiComponent";
    public static final String QBI_INCOME_LIMIT = "/qbiIncomeLimit";
    public static final String QBI_DEDUCTION = "/qbiDeduction";
    public static final String QBI_THRESHOLD = "/qbiThreshold";
    public static final String QBI_ABOVE_THRESHOLD = "/qbiAboveThreshold";

    // Income-tax ladder
    public static final String OTHER_TAXABLE_INCOME = "/planning/otherTaxableIncome";
    public static final String NET_CAPITAL_GAINS = "/planning/netCapitalGains";
    public static final String PROJECTED_AGI = "/planning/projectedAGI";
    public static final String STANDARD_DEDUCTION = "/standardDeduction";
    public static final String TAXABLE_INCOME = "/planning/taxableIncome";
    public static final String PROJECTED_INCOME_TAX = "/incomeTax/projectedIncomeTax";
    /** Derived projected current-year total tax (income tax + SE tax + Additional Medicare). */
    public static final String PROJECTED_TOTAL_TAX = "/planning/projectedCurrentYearTax";

    // Quarterly estimated payments (IRC §6654 safe harbor)
    public static final String PRIOR_YEAR_TOTAL_TAX = "/planning/priorYearTotalTax";
    public static final String PRIOR_YEAR_AGI = "/planning/priorYearAGI";
    public static final String YTD_WITHHOLDING = "/planning/ytdWithholding";
    public static final String YTD_ESTIMATED_PAYMENTS_MADE = "/planning/ytdEstimatedPaymentsMade";
    public static final String REMAINING_QUARTERS = "/planning/remainingQuarters";
    public static final String HIGH_INCOME_SAFE_HARBOR_APPLIES = "/planning/highIncomeSafeHarborApplies";
    public static final String SAFE_HARBOR_TARGET = "/planning/safeHarborTarget";
    public static final String YTD_PAYMENTS_APPLIED = "/planning/ytdPaymentsApplied";
    public static final String REMAINING_PAYMENT_DUE = "/planning/remainingPaymentDue";
    public static final String NEXT_QUARTERLY_PAYMENT_SUGGESTED = "/planning/nextQuarterlyPaymentSuggested";
    public static final String PROJECTED_BALANCE_DUE_AT_FILING = "/planning/projectedBalanceDueAtFiling";

    private FactPaths() {}
}
