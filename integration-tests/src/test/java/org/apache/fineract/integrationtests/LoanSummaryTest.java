/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LoanSummaryTest extends BaseLoanIntegrationTest {

    Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
    Long loanId;

    @Test
    public void testUnpaidPayableNotDueInterestForProgressiveLoanInCaseOfEarlyRepayment() {
        runAt("1 January 2024", () -> {
            final PostLoanProductsResponse loanProductsResponse = loanProductHelper.createLoanProduct(create4IProgressive());
            PostLoansResponse postLoansResponse = loanTransactionHelper.applyLoan(applyLP2ProgressiveLoanRequest(clientId,
                    loanProductsResponse.getResourceId(), "01 January 2024", 1000.0, 9.99, 6, null));
            loanId = postLoansResponse.getLoanId();
            loanTransactionHelper.approveLoan(loanId, approveLoanRequest(1000.0, "01 January 2024"));
            disburseLoan(loanId, BigDecimal.valueOf(250.0), "01 January 2024");
        });
        runAt("7 january 2024", () -> {
            disburseLoan(loanId, BigDecimal.valueOf(350.0), "04 January 2024");
            disburseLoan(loanId, BigDecimal.valueOf(400.0), "05 January 2024");
        });
        runAt("15 January 2024", () -> {
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.valueOf(3.05),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            loanTransactionHelper.makeLoanRepayment(loanId, "Repayment", "15 January 2024", 171.43);
            loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
        });
        runAt("16 January 2024", () -> {
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.valueOf(0.22),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            verifyTransactions(loanId, transaction(250.0, "Disbursement", "01 January 2024"),
                    transaction(350.0, "Disbursement", "04 January 2024"), transaction(400.0, "Disbursement", "05 January 2024"),
                    transaction(2.78, "Accrual", "14 January 2024"), transaction(171.43, "Repayment", "15 January 2024"),
                    transaction(0.27, "Accrual", "15 January 2024"));
        });
        runAt("17 January 2024", () -> {
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.valueOf(0.44),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            verifyTransactions(loanId, transaction(250.0, "Disbursement", "01 January 2024"),
                    transaction(350.0, "Disbursement", "04 January 2024"), transaction(400.0, "Disbursement", "05 January 2024"),
                    transaction(2.78, "Accrual", "14 January 2024"), transaction(171.43, "Repayment", "15 January 2024"),
                    transaction(0.27, "Accrual", "15 January 2024"), transaction(0.22, "Accrual", "16 January 2024"));
        });
        runAt("18 January 2024", () -> {
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.valueOf(0.67),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            verifyTransactions(loanId, transaction(250.0, "Disbursement", "01 January 2024"),
                    transaction(350.0, "Disbursement", "04 January 2024"), transaction(400.0, "Disbursement", "05 January 2024"),
                    transaction(2.78, "Accrual", "14 January 2024"), transaction(171.43, "Repayment", "15 January 2024"),
                    transaction(0.27, "Accrual", "15 January 2024"), transaction(0.22, "Accrual", "16 January 2024"),
                    transaction(0.22, "Accrual", "17 January 2024"));
        });
        runAt("19 January 2024", () -> {
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
            verifyTransactions(loanId, transaction(250.0, "Disbursement", "01 January 2024"),
                    transaction(350.0, "Disbursement", "04 January 2024"), transaction(400.0, "Disbursement", "05 January 2024"),
                    transaction(2.78, "Accrual", "14 January 2024"), transaction(171.43, "Repayment", "15 January 2024"),
                    transaction(0.27, "Accrual", "15 January 2024"), transaction(0.22, "Accrual", "16 January 2024"),
                    transaction(0.22, "Accrual", "17 January 2024"), transaction(0.23, "Accrual", "18 January 2024"));
        });
    }

    @Test
    public void testUnpaidPayableNotDueInterestForProgressiveLoanInCaseOfEarlyRepaymentAlmostFullyPaid2ndPeriod() {
        runAt("15 March 2025", () -> {
            final PostLoanProductsResponse loanProductsResponse = loanProductHelper.createLoanProduct(
                    create4IProgressive().interestRatePerPeriod(35.99).numberOfRepayments(12).isInterestRecalculationEnabled(true));
            PostLoansResponse postLoansResponse = loanTransactionHelper.applyLoan(applyLP2ProgressiveLoanRequest(clientId,
                    loanProductsResponse.getResourceId(), "15 March 2025", 296.79, 35.99, 12, null));
            loanId = postLoansResponse.getLoanId();
            loanTransactionHelper.approveLoan(loanId, approveLoanRequest(296.79, "15 March 2025"));
            disburseLoan(loanId, BigDecimal.valueOf(296.79), "15 March 2025");
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("16 March 2025", () -> {
            loanTransactionHelper.makeLoanRepayment(loanId, "Repayment", "16 March 2025", 59.0);
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("17 March 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(0.23),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("18 March 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(0.46),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("19 March 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(0.69),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("20 March 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(0.92),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("21 March 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(1.15),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("22 March 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(1.38),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("14 May 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(13.81),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });

        runAt("15 May 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(14.05),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("16 May 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(14.28),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("17 May 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(14.51),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("18 May 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(14.74),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("19 May 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(14.97),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("20 May 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(15.20),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("14 June 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(20.96),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("15 June 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.valueOf(21.19),
                    loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.ZERO, loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
        runAt("16 June 2025", () -> {
            GetLoansLoanIdResponse loanDetails = loanTransactionHelper.getLoanDetails(loanId);
            Assertions.assertEquals(BigDecimal.valueOf(21.19),
                    loanDetails.getSummary().getTotalUnpaidPayableDueInterest().stripTrailingZeros());
            Assertions.assertEquals(BigDecimal.valueOf(0.24),
                    loanDetails.getSummary().getTotalUnpaidPayableNotDueInterest().stripTrailingZeros());
            inlineLoanCOBHelper.executeInlineCOB(List.of(loanId));
        });
    }

}
