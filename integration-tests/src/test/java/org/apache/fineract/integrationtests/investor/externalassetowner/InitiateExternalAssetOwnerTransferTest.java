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
package org.apache.fineract.integrationtests.investor.externalassetowner;

import static org.apache.fineract.client.models.ExternalTransferData.StatusEnum.ACTIVE;
import static org.apache.fineract.client.models.ExternalTransferData.StatusEnum.BUYBACK;
import static org.apache.fineract.client.models.ExternalTransferData.StatusEnum.CANCELLED;
import static org.apache.fineract.client.models.ExternalTransferData.StatusEnum.DECLINED;
import static org.apache.fineract.client.models.ExternalTransferData.StatusEnum.PENDING;
import static org.apache.fineract.client.models.ExternalTransferData.SubStatusEnum.BALANCE_ZERO;
import static org.apache.fineract.client.models.ExternalTransferData.SubStatusEnum.SAMEDAY_TRANSFERS;
import static org.apache.fineract.client.models.ExternalTransferData.SubStatusEnum.UNSOLD;
import static org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType.BUSINESS_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.common.AccountingConstants;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.client.models.ExternalAssetOwnerRequest;
import org.apache.fineract.client.models.ExternalOwnerJournalEntryData;
import org.apache.fineract.client.models.ExternalOwnerTransferJournalEntryData;
import org.apache.fineract.client.models.ExternalTransferData;
import org.apache.fineract.client.models.GetFinancialActivityAccountsResponse;
import org.apache.fineract.client.models.GetJournalEntriesTransactionIdResponse;
import org.apache.fineract.client.models.JournalEntryCommand;
import org.apache.fineract.client.models.JournalEntryTransactionItem;
import org.apache.fineract.client.models.PageExternalTransferData;
import org.apache.fineract.client.models.PostFinancialActivityAccountsRequest;
import org.apache.fineract.client.models.PostInitiateTransferResponse;
import org.apache.fineract.client.models.PostJournalEntriesResponse;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansLoanIdTransactionsRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.client.models.SingleDebitOrCreditEntryCommand;
import org.apache.fineract.client.util.CallFailedRuntimeException;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.infrastructure.event.external.data.ExternalEventResponse;
import org.apache.fineract.integrationtests.BaseLoanIntegrationTest;
import org.apache.fineract.integrationtests.common.BusinessDateHelper;
import org.apache.fineract.integrationtests.common.BusinessStepHelper;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CollateralManagementHelper;
import org.apache.fineract.integrationtests.common.ExternalAssetOwnerHelper;
import org.apache.fineract.integrationtests.common.OfficeHelper;
import org.apache.fineract.integrationtests.common.SchedulerJobHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.FinancialActivityAccountHelper;
import org.apache.fineract.integrationtests.common.accounting.JournalEntryHelper;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.externalevents.ExternalEventHelper;
import org.apache.fineract.integrationtests.common.externalevents.ExternalEventsExtension;
import org.apache.fineract.integrationtests.common.loans.LoanApplicationTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanProductTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanStatusChecker;
import org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper;
import org.apache.fineract.integrationtests.common.report.ReportHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.hamcrest.Matchers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@SuppressWarnings("rawtypes")
@ExtendWith({ ExternalEventsExtension.class })
public class InitiateExternalAssetOwnerTransferTest extends BaseLoanIntegrationTest {

    private static ResponseSpecification RESPONSE_SPEC;
    private static RequestSpecification REQUEST_SPEC;
    private static Account ASSET_ACCOUNT;
    private static Account FEE_PENALTY_ACCOUNT;
    private static Account TRANSFER_ACCOUNT;
    private static Account EXPENSE_ACCOUNT;
    private static Account INCOME_ACCOUNT;
    private static Account OVERPAYMENT_ACCOUNT;
    private static FinancialActivityAccountHelper FINANCIAL_ACTIVITY_ACCOUNT_HELPER;
    private static ExternalAssetOwnerHelper EXTERNAL_ASSET_OWNER_HELPER;
    private static LoanTransactionHelper LOAN_TRANSACTION_HELPER;
    private static SchedulerJobHelper SCHEDULER_JOB_HELPER;
    private static OfficeHelper OFFICE_HELPER;
    private static LocalDate TODAYS_DATE;
    public String ownerExternalId;
    private static ReportHelper reportHelper;
    private final DateTimeFormatter dateFormatter = new DateTimeFormatterBuilder().appendPattern("dd MMMM yyyy").toFormatter();

    @BeforeAll
    public static void setupInvestorBusinessStep() {
        Utils.initializeRESTAssured();
        REQUEST_SPEC = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        REQUEST_SPEC.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        RESPONSE_SPEC = new ResponseSpecBuilder().expectStatusCode(200).build();
        AccountHelper accountHelper = new AccountHelper(REQUEST_SPEC, RESPONSE_SPEC);
        EXTERNAL_ASSET_OWNER_HELPER = new ExternalAssetOwnerHelper();
        SCHEDULER_JOB_HELPER = new SchedulerJobHelper(REQUEST_SPEC);
        FINANCIAL_ACTIVITY_ACCOUNT_HELPER = new FinancialActivityAccountHelper(REQUEST_SPEC);
        LOAN_TRANSACTION_HELPER = new LoanTransactionHelper(REQUEST_SPEC, RESPONSE_SPEC);
        OFFICE_HELPER = new OfficeHelper(REQUEST_SPEC, RESPONSE_SPEC);

        TODAYS_DATE = Utils.getLocalDateOfTenant();
        new BusinessStepHelper().updateSteps("LOAN_CLOSE_OF_BUSINESS", "APPLY_CHARGE_TO_OVERDUE_LOANS", "LOAN_DELINQUENCY_CLASSIFICATION",
                "CHECK_LOAN_REPAYMENT_DUE", "CHECK_LOAN_REPAYMENT_OVERDUE", "UPDATE_LOAN_ARREARS_AGING", "ADD_PERIODIC_ACCRUAL_ENTRIES",
                "EXTERNAL_ASSET_OWNER_TRANSFER");

        ASSET_ACCOUNT = accountHelper.createAssetAccount();
        FEE_PENALTY_ACCOUNT = accountHelper.createAssetAccount();
        TRANSFER_ACCOUNT = accountHelper.createAssetAccount();
        EXPENSE_ACCOUNT = accountHelper.createExpenseAccount();
        INCOME_ACCOUNT = accountHelper.createIncomeAccount();
        OVERPAYMENT_ACCOUNT = accountHelper.createLiabilityAccount();

        setProperFinancialActivity(TRANSFER_ACCOUNT);
        reportHelper = new ReportHelper();
    }

    private static void setProperFinancialActivity(Account transferAccount) {
        List<GetFinancialActivityAccountsResponse> financialMappings = FINANCIAL_ACTIVITY_ACCOUNT_HELPER.getAllFinancialActivityAccounts();
        financialMappings.forEach(mapping -> FINANCIAL_ACTIVITY_ACCOUNT_HELPER.deleteFinancialActivityAccount(mapping.getId()));
        FINANCIAL_ACTIVITY_ACCOUNT_HELPER.createFinancialActivityAccount(new PostFinancialActivityAccountsRequest()
                .financialActivityId((long) AccountingConstants.FinancialActivity.ASSET_TRANSFER.getValue())
                .glAccountId((long) transferAccount.getAccountID()));
    }

    @Test
    public void saleActiveLoanToExternalAssetOwnerWithCancelAndBuybackADayLater() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");

            ExternalEventHelper.deleteAllExternalEvents(REQUEST_SPEC, new ResponseSpecBuilder().expectStatusCode(Matchers.is(204)).build());
            ExternalEventHelper.changeEventState(REQUEST_SPEC, RESPONSE_SPEC, "LoanOwnershipTransferBusinessEvent", true);

            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);
            addPenaltyForLoan(loanID, "10");

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-02");
            validateResponse(saleTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            PageExternalTransferData retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            retrieveResponse.getContent().forEach(transfer -> getAndValidateThereIsNoJournalEntriesForTransfer(transfer.getTransferId()));

            EXTERNAL_ASSET_OWNER_HELPER.cancelTransferByTransferExternalId(saleTransferResponse.getResourceExternalId());

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(CANCELLED, saleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            PostInitiateTransferResponse oldSaleTransferResponse = saleTransferResponse;
            saleTransferResponse = createSaleTransfer(loanID, "2020-03-02");
            validateResponse(saleTransferResponse, loanID);

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(CANCELLED, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));

            updateBusinessDateAndExecuteCOBJob("2020-03-03");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(CANCELLED, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));

            List<ExternalEventResponse> allExternalEvents = ExternalEventHelper.getAllExternalEvents(REQUEST_SPEC, RESPONSE_SPEC);
            Assertions.assertEquals(1, allExternalEvents.size());
            Assertions.assertEquals("LoanOwnershipTransferBusinessEvent", allExternalEvents.get(0).getType());
            Assertions.assertEquals(Long.valueOf(loanID), allExternalEvents.get(0).getAggregateRootId());

            ExternalEventHelper.deleteAllExternalEvents(REQUEST_SPEC, new ResponseSpecBuilder().expectStatusCode(Matchers.is(204)).build());
            ExternalEventHelper.changeEventState(REQUEST_SPEC, RESPONSE_SPEC, "LoanOwnershipTransferBusinessEvent", true);

            getAndValidateThereIsActiveMapping(loanID);
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            LocalDate expectedDate = LocalDate.of(2020, 3, 2);
            int initial = 2;
            getAndValidateThereIsJournalEntriesForTransfer(retrieveResponse.getContent().get(initial + 1).getTransferId(),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15767.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15767.420000), expectedDate, expectedDate));

            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-03");
            validateResponse(buybackTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(CANCELLED, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsActiveMapping(loanID);
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            getAndValidateThereIsNoJournalEntriesForTransfer(retrieveResponse.getContent().get(initial + 2).getTransferId());

            LOAN_TRANSACTION_HELPER.makeLoanRepayment((long) loanID, new PostLoansLoanIdTransactionsRequest().dateFormat("dd MMMM yyyy")
                    .transactionDate(dateFormatter.format(expectedDate)).locale("en").transactionAmount(5.0));
            LocalDate repaymentSubmittedOnDate = expectedDate.plusDays(1);
            getAndValidateOwnerJournalEntries(ownerExternalId,
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, repaymentSubmittedOnDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, repaymentSubmittedOnDate));

            updateBusinessDateAndExecuteCOBJob("2020-03-04");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(CANCELLED, oldSaleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "2020-03-03", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "2020-03-03", true, new BigDecimal("15762.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("5.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            expectedDate = LocalDate.of(2020, 3, 3);
            getAndValidateThereIsJournalEntriesForTransfer(retrieveResponse.getContent().get(initial + 2).getTransferId(),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15762.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15762.420000), expectedDate, expectedDate));
            LocalDate previousDayDate = LocalDate.of(2020, 3, 2);
            getAndValidateOwnerJournalEntries(ownerExternalId,
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), previousDayDate, previousDayDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), previousDayDate, previousDayDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), previousDayDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(5.000000), previousDayDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(9.680000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) INCOME_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(9.680000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, expectedDate));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleActiveLoanToExternalAssetOwnerAndBuybackADayLater() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);
            addPenaltyForLoan(loanID, "10");

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-02");
            validateResponse(saleTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            PageExternalTransferData retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            retrieveResponse.getContent().forEach(transfer -> getAndValidateThereIsNoJournalEntriesForTransfer(transfer.getTransferId()));

            updateBusinessDateAndExecuteCOBJob("2020-03-03");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsActiveMapping(loanID);
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            LocalDate expectedDate = LocalDate.of(2020, 3, 2);
            getAndValidateThereIsJournalEntriesForTransfer(retrieveResponse.getContent().get(1).getTransferId(),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15767.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15767.420000), expectedDate, expectedDate));

            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-03");
            validateResponse(buybackTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsActiveMapping(loanID);
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            getAndValidateThereIsNoJournalEntriesForTransfer(retrieveResponse.getContent().get(2).getTransferId());

            LOAN_TRANSACTION_HELPER.makeLoanRepayment((long) loanID, new PostLoansLoanIdTransactionsRequest().dateFormat("dd MMMM yyyy")
                    .transactionDate(dateFormatter.format(expectedDate)).locale("en").transactionAmount(5.0));
            LocalDate repaymentSubmittedOnDate = expectedDate.plusDays(1);
            getAndValidateOwnerJournalEntries(ownerExternalId,
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, repaymentSubmittedOnDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, repaymentSubmittedOnDate));

            updateBusinessDateAndExecuteCOBJob("2020-03-04");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "2020-03-03", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "2020-03-03", true, new BigDecimal("15762.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("5.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            expectedDate = LocalDate.of(2020, 3, 3);
            getAndValidateThereIsJournalEntriesForTransfer(retrieveResponse.getContent().get(2).getTransferId(),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15762.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15762.420000), expectedDate, expectedDate));
            LocalDate previousDayDate = LocalDate.of(2020, 3, 2);
            getAndValidateOwnerJournalEntries(ownerExternalId,
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), previousDayDate, previousDayDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), previousDayDate, previousDayDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), previousDayDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(5.000000), previousDayDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(9.680000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) INCOME_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(9.680000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(5.000000), expectedDate, expectedDate));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleOverpaidLoanToExternalAssetOwnerAndBuybackADayLater() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);
            addPenaltyForLoan(loanID, "10");

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-02");
            validateResponse(saleTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            PageExternalTransferData retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            retrieveResponse.getContent().forEach(transfer -> getAndValidateThereIsNoJournalEntriesForTransfer(transfer.getTransferId()));

            updateBusinessDateAndExecuteCOBJob("2020-03-03");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsActiveMapping(loanID);
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            LocalDate expectedDate = LocalDate.of(2020, 3, 2);
            getAndValidateThereIsJournalEntriesForTransfer(retrieveResponse.getContent().get(1).getTransferId(),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15767.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(15767.420000), expectedDate, expectedDate));

            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-03");
            validateResponse(buybackTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsActiveMapping(loanID);
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            getAndValidateThereIsNoJournalEntriesForTransfer(retrieveResponse.getContent().get(2).getTransferId());

            LOAN_TRANSACTION_HELPER.makeLoanRepayment((long) loanID, new PostLoansLoanIdTransactionsRequest().dateFormat("dd MMMM yyyy")
                    .transactionDate(dateFormatter.format(expectedDate)).locale("en").transactionAmount(15777.42));
            LocalDate repaymentSubmittedOnDate = expectedDate.plusDays(1);
            getAndValidateOwnerJournalEntries(ownerExternalId,
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) OVERPAYMENT_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), repaymentSubmittedOnDate, repaymentSubmittedOnDate));

            updateBusinessDateAndExecuteCOBJob("2020-03-04");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "2020-03-03", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "2020-03-03", true, new BigDecimal("0.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000"), new BigDecimal("0.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("10.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            expectedDate = LocalDate.of(2020, 3, 3);
            getAndValidateThereIsJournalEntriesForTransfer(retrieveResponse.getContent().get(2).getTransferId(),
                    ExpectedJournalEntryData.expected((long) OVERPAYMENT_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) OVERPAYMENT_ACCOUNT.getAccountID(), (long) JournalEntryType.CREDIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate),
                    ExpectedJournalEntryData.expected((long) TRANSFER_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate));
            LocalDate previousDayDate = LocalDate.of(2020, 3, 2);
            getAndValidateOwnerJournalEntries(ownerExternalId,
                    ExpectedJournalEntryData.expected((long) ASSET_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(15757.420000), previousDayDate, previousDayDate),
                    ExpectedJournalEntryData.expected((long) FEE_PENALTY_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), previousDayDate, previousDayDate),
                    ExpectedJournalEntryData.expected((long) OVERPAYMENT_ACCOUNT.getAccountID(), (long) JournalEntryType.DEBIT.getValue(),
                            BigDecimal.valueOf(10.000000), expectedDate, expectedDate));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleIsNotAllowedWhenTransferIsAlreadyPending() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            createSaleTransfer(loanID, "2020-03-02");

            CallFailedRuntimeException exception = assertThrows(CallFailedRuntimeException.class,
                    () -> createSaleTransfer(loanID, "2020-03-02"));
            assertTrue(exception.getMessage().contains("External asset owner transfer is already in PENDING state for this loan"));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleIsNotAllowedWhenLoanIsNotActive() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            updateBusinessDateAndExecuteCOBJob("2020-03-04");

            LOAN_TRANSACTION_HELPER.makeRepayment("04 March 2020", 16000.0f, loanID);

            HashMap loanStatusHashMap = LoanStatusChecker.getStatusOfLoan(REQUEST_SPEC, RESPONSE_SPEC, loanID);
            LoanStatus loanStatus = LoanStatus.fromInt((Integer) loanStatusHashMap.get("id"));

            CallFailedRuntimeException exception = assertThrows(CallFailedRuntimeException.class,
                    () -> createSaleTransfer(loanID, "2020-03-02"));
            assertTrue(exception.getMessage().contains(String.format("Loan status %s is not valid for transfer.", loanStatus)));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleIsDeclinedWhenLoanIsCancelled() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-06");
            updateBusinessDateAndExecuteCOBJob("2020-03-04");

            LOAN_TRANSACTION_HELPER.writeOffLoan("04 March 2020", loanID);

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-06", "2020-03-02",
                            "2020-03-04"),
                    ExpectedExternalTransferData.expected(DECLINED, saleTransferResponse.getResourceExternalId(), "2020-03-06",
                            "2020-03-04", "2020-03-04", BALANCE_ZERO));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void buybackIsExecutedWhenLoanIsCancelled() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-04");
            updateBusinessDateAndExecuteCOBJob("2020-03-05");
            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-06");

            LOAN_TRANSACTION_HELPER.writeOffLoan("04 March 2020", loanID);

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-04", "2020-03-02",
                            "2020-03-04"),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-04", "2020-03-05",
                            "2020-03-05", true, new BigDecimal("15757.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("0.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-06",
                            "2020-03-05", "2020-03-05", true, new BigDecimal("15757.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("0.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void buybackAndSaleIsCancelledWhenLoanIsCancelled() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-04");
            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-06");

            LOAN_TRANSACTION_HELPER.writeOffLoan("02 March 2020", loanID);

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-04", "2020-03-02",
                            "2020-03-02"),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-06",
                            "2020-03-02", "2020-03-02"),
                    ExpectedExternalTransferData.expected(CANCELLED, buybackTransferResponse.getResourceExternalId(), "2020-03-06",
                            "2020-03-02", "2020-03-02", UNSOLD),
                    ExpectedExternalTransferData.expected(DECLINED, saleTransferResponse.getResourceExternalId(), "2020-03-04",
                            "2020-03-02", "2020-03-02", BALANCE_ZERO));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void sameDayBuybackAndSaleIsCancelledWhenLoanIsCancelled() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-03");
            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-03");

            LOAN_TRANSACTION_HELPER.writeOffLoan("02 March 2020", loanID);

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-03", "2020-03-02",
                            "2020-03-02"),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-02", "2020-03-02"),
                    ExpectedExternalTransferData.expected(CANCELLED, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-02", "2020-03-02", SAMEDAY_TRANSFERS),
                    ExpectedExternalTransferData.expected(CANCELLED, saleTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-02", "2020-03-02", SAMEDAY_TRANSFERS));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleAndBuybackOnTheSameDay() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-02");
            validateResponse(saleTransferResponse, loanID);
            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-02");
            validateResponse(buybackTransferResponse, loanID);

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            getAndValidateThereIsNoActiveMapping(buybackTransferResponse.getResourceExternalId());

            updateBusinessDateAndExecuteCOBJob("2020-03-03");

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(CANCELLED, buybackTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(CANCELLED, saleTransferResponse.getResourceExternalId(), "2020-03-02",
                            "2020-03-02", "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping((long) loanID);
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleAndBuybackMultipleTimes() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            PostInitiateTransferResponse saleTransferResponse = createSaleTransfer(loanID, "2020-03-04");
            PostInitiateTransferResponse buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-04");

            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-04", "2020-03-02",
                            "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-04",
                            "2020-03-02", "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));

            CallFailedRuntimeException exception = assertThrows(CallFailedRuntimeException.class,
                    () -> createSaleTransfer(loanID, "2020-03-04"));
            assertTrue(exception.getMessage().contains("This loan cannot be sold, there is already an in progress transfer"));

            CallFailedRuntimeException exception2 = assertThrows(CallFailedRuntimeException.class,
                    () -> createBuybackTransfer(loanID, "2020-03-04"));
            assertTrue(exception2.getMessage()
                    .contains("This loan cannot be bought back, external asset owner buyback transfer is already in progress"));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void buybackExceptionHandling() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");

            CallFailedRuntimeException exception = assertThrows(CallFailedRuntimeException.class, () -> createBuybackTransfer(1, null));
            assertTrue(exception.getMessage().contains("The parameter `settlementDate` is mandatory."));

            CallFailedRuntimeException exception2 = assertThrows(CallFailedRuntimeException.class, () -> {
                Integer clientID = createClient();
                Integer loanID = createLoanForClient(clientID);
                createBuybackTransfer(loanID, "1970-01-01");
            });
            assertTrue(exception2.getMessage().contains("Settlement date cannot be in the past"));

            CallFailedRuntimeException exception3 = assertThrows(CallFailedRuntimeException.class, () -> {
                Integer clientID = createClient();
                Integer loanID = createLoanForClient(clientID);
                createSaleTransfer(loanID, "2020-03-03");
                createBuybackTransfer(loanID, "2020-03-02");
            });
            assertTrue(exception3.getMessage().contains(
                    "This loan cannot be bought back, settlement date is earlier than effective transfer settlement date: 2020-03-03"));

            CallFailedRuntimeException exception4 = assertThrows(CallFailedRuntimeException.class, () -> {
                Integer clientID = createClient();
                Integer loanID = createLoanForClient(clientID);
                createBuybackTransfer(loanID, "2020-03-03");
            });
            assertTrue(exception4.getMessage().contains("This loan cannot be bought back, it is not owned by an external asset owner"));

            CallFailedRuntimeException exception5 = assertThrows(CallFailedRuntimeException.class,
                    () -> createBuybackTransfer(-1, "2020-03-03"));
            assertTrue(exception5.getMessage().contains("Loan with identifier -1 does not exist"));

            String externalId = UUID.randomUUID().toString();
            String transferExternalGroupId = UUID.randomUUID().toString();

            CallFailedRuntimeException exception6 = assertThrows(CallFailedRuntimeException.class, () -> {
                Integer clientID = createClient();
                Integer loanID = createLoanForClient(clientID);
                createSaleTransfer(loanID, "2020-03-03", externalId, transferExternalGroupId, "1", "1.0");
                createBuybackTransfer(loanID, "2020-03-02", externalId);
            });
            assertTrue(exception6.getMessage()
                    .contains(String.format("Already existing an asset transfer with the provided transfer external id: %s", externalId)));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void saleExceptionHandling() {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");
            Integer clientID = createClient();
            Integer loanID = createLoanForClient(clientID);

            CallFailedRuntimeException exception = assertThrows(CallFailedRuntimeException.class, () -> createSaleTransfer(loanID, null));
            assertTrue(exception.getMessage().contains("The parameter `settlementDate` is mandatory."));

            CallFailedRuntimeException exception2 = assertThrows(CallFailedRuntimeException.class, () -> createSaleTransfer(loanID,
                    "2020-03-02", UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, "1.0"));
            assertTrue(exception2.getMessage().contains("The parameter `ownerExternalId` is mandatory."));

            CallFailedRuntimeException exception3 = assertThrows(CallFailedRuntimeException.class,
                    () -> createSaleTransfer(loanID, "2020-03-02", null, UUID.randomUUID().toString(), UUID.randomUUID().toString(), null));
            assertTrue(exception3.getMessage().contains("The parameter `purchasePriceRatio` is mandatory."));

            CallFailedRuntimeException exception4 = assertThrows(CallFailedRuntimeException.class,
                    () -> createSaleTransfer(loanID, "1970-01-01"));
            assertTrue(exception4.getMessage().contains("Settlement date cannot be in the past"));

            CallFailedRuntimeException exception5 = assertThrows(CallFailedRuntimeException.class, () -> {
                createSaleTransfer(loanID, "2020-03-03");
                createBuybackTransfer(loanID, "2020-03-04");
                createSaleTransfer(loanID, "2020-03-05");
            });
            assertTrue(exception5.getMessage().contains("This loan cannot be sold, there is already an in progress transfer"));
            CallFailedRuntimeException exception6 = assertThrows(CallFailedRuntimeException.class, () -> {
                Integer loanID2 = createLoanForClient(clientID);
                createSaleTransfer(loanID2, "2020-03-03");
                updateBusinessDateAndExecuteCOBJob("2020-03-04");
                createSaleTransfer(loanID2, "2020-03-05");
            });
            assertTrue(exception6.getMessage().contains("This loan cannot be sold, because it is owned by an external asset owner"));
            String externalId = UUID.randomUUID().toString();
            String transferExternalGroupId = UUID.randomUUID().toString();
            CallFailedRuntimeException exception7 = assertThrows(CallFailedRuntimeException.class, () -> {
                Integer loanID2 = createLoanForClient(clientID);
                createSaleTransfer(loanID2, "2020-03-05", externalId, transferExternalGroupId, "1", "1.0");
                createSaleTransfer(loanID2, "2020-03-05", externalId, transferExternalGroupId, "1", "1.0");
            });
            assertTrue(exception7.getMessage()
                    .contains(String.format("Already existing an asset transfer with the provided transfer external id: %s", externalId)));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void transactionSummaryReportWithAssetOwner() throws IOException {
        try {
            globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, true);
            setInitialBusinessDate("2020-03-02");

            ExternalEventHelper.deleteAllExternalEvents(REQUEST_SPEC, new ResponseSpecBuilder().expectStatusCode(Matchers.is(204)).build());
            ExternalEventHelper.changeEventState(REQUEST_SPEC, RESPONSE_SPEC, "LoanOwnershipTransferBusinessEvent", true);

            final var officeId = OFFICE_HELPER.createOffice("1 January 2020");
            final var clientID = ClientHelper.createClient(REQUEST_SPEC, RESPONSE_SPEC, "1 January 2020", officeId.toString());
            final var loanID = createLoanForClient(clientID);
            addPenaltyForLoan(loanID, "10");

            final var saleTransferResponse = createSaleTransfer(loanID, "2020-03-02");
            validateResponse(saleTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            var retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            retrieveResponse.getContent().forEach(transfer -> getAndValidateThereIsNoJournalEntriesForTransfer(transfer.getTransferId()));

            updateBusinessDateAndExecuteCOBJob("2020-03-03");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));

            final var allExternalEvents = ExternalEventHelper.getAllExternalEvents(REQUEST_SPEC, RESPONSE_SPEC);
            Assertions.assertEquals(1, allExternalEvents.size());
            Assertions.assertEquals("LoanOwnershipTransferBusinessEvent", allExternalEvents.get(0).getType());
            Assertions.assertEquals(Long.valueOf(loanID), allExternalEvents.get(0).getAggregateRootId());

            ExternalEventHelper.deleteAllExternalEvents(REQUEST_SPEC, new ResponseSpecBuilder().expectStatusCode(Matchers.is(204)).build());
            ExternalEventHelper.changeEventState(REQUEST_SPEC, RESPONSE_SPEC, "LoanOwnershipTransferBusinessEvent", true);

            getAndValidateThereIsActiveMapping(loanID);

            final var expectedDate = LocalDate.of(2020, 3, 2);
            final var initial = 0;

            final var buybackTransferResponse = createBuybackTransfer(loanID, "2020-03-03");
            validateResponse(buybackTransferResponse, loanID);
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "9999-12-31", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "9999-12-31", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsActiveMapping(loanID);
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
            getAndValidateThereIsNoJournalEntriesForTransfer(retrieveResponse.getContent().get(initial + 2).getTransferId());

            LOAN_TRANSACTION_HELPER.makeLoanRepayment((long) loanID, new PostLoansLoanIdTransactionsRequest().dateFormat("dd MMMM yyyy")
                    .transactionDate(dateFormatter.format(expectedDate)).locale("en").transactionAmount(5.0));

            updateBusinessDateAndExecuteCOBJob("2020-03-04");
            getAndValidateExternalAssetOwnerTransferByLoan(loanID,
                    ExpectedExternalTransferData.expected(PENDING, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-02",
                            "2020-03-02", false, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(ACTIVE, saleTransferResponse.getResourceExternalId(), "2020-03-02", "2020-03-03",
                            "2020-03-03", true, new BigDecimal("15767.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("10.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")),
                    ExpectedExternalTransferData.expected(BUYBACK, buybackTransferResponse.getResourceExternalId(), "2020-03-03",
                            "2020-03-03", "2020-03-03", true, new BigDecimal("15762.420000"), new BigDecimal("15000.000000"),
                            new BigDecimal("757.420000"), new BigDecimal("5.000000"), new BigDecimal("0.000000"),
                            new BigDecimal("0.000000")));
            getAndValidateThereIsNoActiveMapping(saleTransferResponse.getResourceExternalId());
            retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());

            final var reportResult = reportHelper.runReport("Transaction Summary Report with Asset Owner",
                    Map.of("R_endDate", "2020-03-03", "R_officeId", officeId.toString(), "output-type", "CSV"));

            assertNotNull(reportResult.body());
            final var csvContent = reportResult.body().string();
            final var jsonPath = JsonPath.from(csvContent);

            assertNotNull(jsonPath.getString("columnHeaders[0].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[0].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[0].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[1].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[1].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[1].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[2].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[2].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[2].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[3].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[3].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[3].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[4].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[4].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[4].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[5].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[5].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[5].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[6].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[6].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[6].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[7].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[7].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[7].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[8].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[8].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[8].isColumnNullable"));

            assertNotNull(jsonPath.getString("columnHeaders[9].columnType"));
            assertNotNull(jsonPath.getString("columnHeaders[9].columnDisplayType"));
            assertFalse(jsonPath.getBoolean("columnHeaders[9].isColumnNullable"));

            assertNotNull(retrieveResponse.getContent().get(0).getOwner());
            final var ownerId = retrieveResponse.getContent().get(0).getOwner().getExternalId();

            assertEquals("2020-03-03", jsonPath.getString("data[0].row[0]"));
            assertTrue(jsonPath.getString("data[0].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Apply Charges", jsonPath.getString("data[0].row[2]"));
            assertNull(jsonPath.getString("data[0].row[3]"));
            assertEquals("", jsonPath.getString("data[0].row[4]"));
            assertFalse(jsonPath.getBoolean("data[0].row[5]"));
            assertEquals("Interest", jsonPath.getString("data[0].row[6]"));
            assertNull(jsonPath.getString("data[0].row[7]"));
            assertEquals(9.68, jsonPath.getDouble("data[0].row[8]"), 0.01);
            assertEquals(ownerId, jsonPath.getString("data[0].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[1].row[0]"));
            assertTrue(jsonPath.getString("data[1].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Asset Buyback", jsonPath.getString("data[1].row[2]"));
            assertNull(jsonPath.getString("data[1].row[3]"));
            assertEquals("", jsonPath.getString("data[1].row[4]"));
            assertFalse(jsonPath.getBoolean("data[1].row[5]"));
            assertEquals("Interest", jsonPath.getString("data[1].row[6]"));
            assertNull(jsonPath.getString("data[1].row[7]"));
            assertEquals(-757.42, jsonPath.getDouble("data[1].row[8]"), 0.01);
            assertNull(jsonPath.getString("data[1].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[2].row[0]"));
            assertTrue(jsonPath.getString("data[2].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Asset Buyback", jsonPath.getString("data[2].row[2]"));
            assertNull(jsonPath.getString("data[2].row[3]"));
            assertEquals("", jsonPath.getString("data[2].row[4]"));
            assertFalse(jsonPath.getBoolean("data[2].row[5]"));
            assertEquals("Penalty", jsonPath.getString("data[2].row[6]"));
            assertNull(jsonPath.getString("data[2].row[7]"));
            assertEquals(-5.00, jsonPath.getDouble("data[2].row[8]"), 0.01);
            assertNull(jsonPath.getString("data[2].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[3].row[0]"));
            assertTrue(jsonPath.getString("data[3].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Asset Buyback", jsonPath.getString("data[3].row[2]"));
            assertNull(jsonPath.getString("data[3].row[3]"));
            assertEquals("", jsonPath.getString("data[3].row[4]"));
            assertFalse(jsonPath.getBoolean("data[3].row[5]"));
            assertEquals("Principal", jsonPath.getString("data[3].row[6]"));
            assertNull(jsonPath.getString("data[3].row[7]"));
            assertEquals(-15000.00, jsonPath.getDouble("data[3].row[8]"), 0.01);
            assertNull(jsonPath.getString("data[3].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[4].row[0]"));
            assertTrue(jsonPath.getString("data[4].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Repayment", jsonPath.getString("data[4].row[2]"));
            assertNull(jsonPath.getString("data[4].row[3]"));
            assertEquals("", jsonPath.getString("data[4].row[4]"));
            assertFalse(jsonPath.getBoolean("data[4].row[5]"));
            assertEquals("Fees", jsonPath.getString("data[4].row[6]"));
            assertNull(jsonPath.getString("data[4].row[7]"));
            assertEquals(0.00, jsonPath.getDouble("data[4].row[8]"), 0.01);
            assertEquals(ownerId, jsonPath.getString("data[4].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[5].row[0]"));
            assertTrue(jsonPath.getString("data[5].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Repayment", jsonPath.getString("data[5].row[2]"));
            assertNull(jsonPath.getString("data[5].row[3]"));
            assertEquals("", jsonPath.getString("data[5].row[4]"));
            assertFalse(jsonPath.getBoolean("data[5].row[5]"));
            assertEquals("Interest", jsonPath.getString("data[5].row[6]"));
            assertNull(jsonPath.getString("data[5].row[7]"));
            assertEquals(0.00, jsonPath.getDouble("data[5].row[8]"), 0.01);
            assertEquals(ownerId, jsonPath.getString("data[5].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[6].row[0]"));
            assertTrue(jsonPath.getString("data[6].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Repayment", jsonPath.getString("data[6].row[2]"));
            assertNull(jsonPath.getString("data[6].row[3]"));
            assertEquals("", jsonPath.getString("data[6].row[4]"));
            assertFalse(jsonPath.getBoolean("data[6].row[5]"));
            assertEquals("Penalty", jsonPath.getString("data[6].row[6]"));
            assertNull(jsonPath.getString("data[6].row[7]"));
            assertEquals(-5.00, jsonPath.getDouble("data[6].row[8]"), 0.01);
            assertEquals(ownerId, jsonPath.getString("data[6].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[7].row[0]"));
            assertTrue(jsonPath.getString("data[7].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Repayment", jsonPath.getString("data[7].row[2]"));
            assertNull(jsonPath.getString("data[7].row[3]"));
            assertEquals("", jsonPath.getString("data[7].row[4]"));
            assertFalse(jsonPath.getBoolean("data[7].row[5]"));
            assertEquals("Principal", jsonPath.getString("data[7].row[6]"));
            assertNull(jsonPath.getString("data[7].row[7]"));
            assertEquals(0.00, jsonPath.getDouble("data[7].row[8]"), 0.01);
            assertEquals(ownerId, jsonPath.getString("data[7].row[9]"));

            assertEquals("2020-03-03", jsonPath.getString("data[8].row[0]"));
            assertTrue(jsonPath.getString("data[8].row[1]").matches("^LOAN_PRODUCT_.{6}$"));
            assertEquals("Repayment", jsonPath.getString("data[8].row[2]"));
            assertNull(jsonPath.getString("data[8].row[3]"));
            assertEquals("", jsonPath.getString("data[8].row[4]"));
            assertFalse(jsonPath.getBoolean("data[8].row[5]"));
            assertEquals("Unallocated Credit (UNC)", jsonPath.getString("data[8].row[6]"));
            assertNull(jsonPath.getString("data[8].row[7]"));
            assertEquals(0.00, jsonPath.getDouble("data[8].row[8]"), 0.01);
            assertEquals(ownerId, jsonPath.getString("data[8].row[9]"));
        } finally {
            cleanUpAndRestoreBusinessDate();
        }
    }

    @Test
    public void addManualJournalEntriesWithAssetExternalization() {
        runAt("10 April 2025", () -> {

            final Account glAccountDebit = accountHelper.createAssetAccount();
            final Account glAccountCredit = accountHelper.createLiabilityAccount();
            final String externalAssetOwner = Utils.uniqueRandomStringGenerator("ASSET_EXTERNAL_", 5);

            CallFailedRuntimeException callFailedRuntimeException = Assertions.assertThrows(CallFailedRuntimeException.class,
                    () -> JournalEntryHelper.createJournalEntry("", new JournalEntryCommand().amount(BigDecimal.TEN).officeId(1L)
                            .currencyCode("USD").locale("en").dateFormat("uuuu-MM-dd").transactionDate(LocalDate.of(2024, 1, 1))
                            .addCreditsItem(new SingleDebitOrCreditEntryCommand().glAccountId(glAccountDebit.getAccountID().longValue())
                                    .amount(BigDecimal.TEN))
                            .addDebitsItem(new SingleDebitOrCreditEntryCommand().glAccountId(glAccountCredit.getAccountID().longValue())
                                    .amount(BigDecimal.TEN))
                            .externalAssetOwner(externalAssetOwner)));
            Assertions.assertTrue(callFailedRuntimeException.getMessage().contains("External asset owner with external id:"));

            final Integer clientId = ClientHelper.createClient(requestSpec, responseSpec);
            final String operationDate = "10 April 2025";

            PostLoanProductsResponse loanProductResponse = loanProductHelper.createLoanProduct(
                    createOnePeriod30DaysPeriodicAccrualProductWithAdvancedPaymentAllocationAndInterestRecalculation(12.0, 4));

            final PostLoansRequest applicationRequest = applyLoanRequest(clientId.longValue(), loanProductResponse.getResourceId(),
                    operationDate, 1000.0, 4).transactionProcessingStrategyCode("advanced-payment-allocation-strategy")//
                    .interestRatePerPeriod(BigDecimal.valueOf(12.0));

            final PostLoansResponse loanResponse = loanTransactionHelper.applyLoan(applicationRequest);
            final Long loanId = loanResponse.getLoanId();

            loanTransactionHelper.approveLoan(loanId, new PostLoansLoanIdRequest().approvedLoanAmount(BigDecimal.valueOf(1000.0))
                    .dateFormat(DATETIME_PATTERN).approvedOnDate(operationDate).locale("en"));

            loanTransactionHelper.disburseLoan(loanId, new PostLoansLoanIdRequest().actualDisbursementDate(operationDate)
                    .dateFormat(DATETIME_PATTERN).transactionAmount(BigDecimal.valueOf(1000.0)).locale("en"));

            PostInitiateTransferResponse transferResponse = EXTERNAL_ASSET_OWNER_HELPER.initiateTransferByLoanId(loanId, "sale",
                    new ExternalAssetOwnerRequest().settlementDate("2025-04-20").dateFormat("yyyy-MM-dd").locale("en")
                            .transferExternalId(externalAssetOwner).transferExternalGroupId(null).ownerExternalId(externalAssetOwner)
                            .purchasePriceRatio("0.90"));
            assertEquals(externalAssetOwner, transferResponse.getResourceExternalId());

            final PostJournalEntriesResponse journalEntriesResponse = JournalEntryHelper.createJournalEntry("",
                    new JournalEntryCommand().amount(BigDecimal.TEN).officeId(1L).currencyCode("USD").locale("en").dateFormat("uuuu-MM-dd")
                            .transactionDate(LocalDate.of(2024, 1, 1))
                            .addCreditsItem(new SingleDebitOrCreditEntryCommand().glAccountId(glAccountDebit.getAccountID().longValue())
                                    .amount(BigDecimal.TEN))
                            .addDebitsItem(new SingleDebitOrCreditEntryCommand().glAccountId(glAccountCredit.getAccountID().longValue())
                                    .amount(BigDecimal.TEN))
                            .externalAssetOwner(externalAssetOwner));

            final GetJournalEntriesTransactionIdResponse journalEntriesTransactionIdResponse = JournalEntryHelper
                    .retrieveJournalEntryByTransactionId(journalEntriesResponse.getTransactionId());
            Assertions.assertNotNull(journalEntriesTransactionIdResponse);
            assertEquals(2, journalEntriesTransactionIdResponse.getPageItems().size());
            JournalEntryTransactionItem journalEntryItem = journalEntriesTransactionIdResponse.getPageItems().get(0);
            assertEquals(externalAssetOwner, journalEntryItem.getExternalAssetOwner());
            journalEntryItem = journalEntriesTransactionIdResponse.getPageItems().get(1);
            assertEquals(externalAssetOwner, journalEntryItem.getExternalAssetOwner());
        });
    }

    private void updateBusinessDateAndExecuteCOBJob(String date) {
        BusinessDateHelper.updateBusinessDate(REQUEST_SPEC, RESPONSE_SPEC, BUSINESS_DATE, LocalDate.parse(date));
        SCHEDULER_JOB_HELPER.executeAndAwaitJob("Loan COB");
    }

    private PostInitiateTransferResponse createSaleTransfer(Integer loanID, String settlementDate) {
        String transferExternalId = UUID.randomUUID().toString();
        String transferExternalGroupId = UUID.randomUUID().toString();
        ownerExternalId = UUID.randomUUID().toString();
        return createSaleTransfer(loanID, settlementDate, transferExternalId, transferExternalGroupId, ownerExternalId, "1.0");
    }

    private PostInitiateTransferResponse createSaleTransfer(Integer loanID, String settlementDate, String transferExternalId,
            String transferExternalGroupId, String ownerExternalId, String purchasePriceRatio) {
        PostInitiateTransferResponse saleResponse = EXTERNAL_ASSET_OWNER_HELPER.initiateTransferByLoanId(loanID.longValue(), "sale",
                new ExternalAssetOwnerRequest().settlementDate(settlementDate).dateFormat("yyyy-MM-dd").locale("en")
                        .transferExternalId(transferExternalId).transferExternalGroupId(transferExternalGroupId)
                        .ownerExternalId(ownerExternalId).purchasePriceRatio(purchasePriceRatio));
        assertEquals(transferExternalId, saleResponse.getResourceExternalId());
        return saleResponse;
    }

    private PostInitiateTransferResponse createBuybackTransfer(Integer loanID, String settlementDate) {
        String transferExternalId = UUID.randomUUID().toString();
        return createBuybackTransfer(loanID, settlementDate, transferExternalId);
    }

    private PostInitiateTransferResponse createBuybackTransfer(Integer loanID, String settlementDate, String transferExternalId) {
        PostInitiateTransferResponse saleResponse = EXTERNAL_ASSET_OWNER_HELPER.initiateTransferByLoanId(loanID.longValue(), "buyback",
                new ExternalAssetOwnerRequest().settlementDate(settlementDate).dateFormat("yyyy-MM-dd").locale("en")
                        .transferExternalId(transferExternalId));
        assertEquals(transferExternalId, saleResponse.getResourceExternalId());
        return saleResponse;
    }

    private void addPenaltyForLoan(Integer loanID, String amount) {
        // Add Charge Penalty
        Integer penalty = ChargesHelper.createCharges(REQUEST_SPEC, RESPONSE_SPEC,
                ChargesHelper.getLoanSpecifiedDueDateJSON(ChargesHelper.CHARGE_CALCULATION_TYPE_FLAT, amount, true));
        Integer penalty1LoanChargeId = LOAN_TRANSACTION_HELPER.addChargesForLoan(loanID,
                LoanTransactionHelper.getSpecifiedDueDateChargesForLoanAsJSON(String.valueOf(penalty), "02 March 2020", amount));
        assertNotNull(penalty1LoanChargeId);
    }

    private void setInitialBusinessDate(String date) {
        globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                new PutGlobalConfigurationsRequest().enabled(true));
        BusinessDateHelper.updateBusinessDate(REQUEST_SPEC, RESPONSE_SPEC, BUSINESS_DATE, LocalDate.parse(date));
    }

    private void cleanUpAndRestoreBusinessDate() {
        REQUEST_SPEC = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        REQUEST_SPEC.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        REQUEST_SPEC.header("Fineract-Platform-TenantId", "default");
        RESPONSE_SPEC = new ResponseSpecBuilder().expectStatusCode(200).build();
        BusinessDateHelper.updateBusinessDate(REQUEST_SPEC, RESPONSE_SPEC, BUSINESS_DATE, TODAYS_DATE);
        globalConfigurationHelper.updateGlobalConfiguration(GlobalConfigurationConstants.ENABLE_BUSINESS_DATE,
                new PutGlobalConfigurationsRequest().enabled(false));
        globalConfigurationHelper.manageConfigurations(GlobalConfigurationConstants.ENABLE_AUTO_GENERATED_EXTERNAL_ID, false);
    }

    @NotNull
    private Integer createClient() {
        final Integer clientID = ClientHelper.createClient(REQUEST_SPEC, RESPONSE_SPEC);
        Assertions.assertNotNull(clientID);
        return clientID;
    }

    @NotNull
    private Integer createLoanForClient(Integer clientID) {
        Integer overdueFeeChargeId = ChargesHelper.createCharges(REQUEST_SPEC, RESPONSE_SPEC,
                ChargesHelper.getLoanOverdueFeeJSONWithCalculationTypePercentage("1"));
        Assertions.assertNotNull(overdueFeeChargeId);

        Integer loanProductID = createLoanProduct(overdueFeeChargeId.toString());
        Assertions.assertNotNull(loanProductID);
        HashMap loanStatusHashMap;

        Integer loanID = applyForLoanApplication(clientID.toString(), loanProductID.toString(), "1 March 2020");

        Assertions.assertNotNull(loanID);

        loanStatusHashMap = LoanStatusChecker.getStatusOfLoan(REQUEST_SPEC, RESPONSE_SPEC, loanID);
        LoanStatusChecker.verifyLoanIsPending(loanStatusHashMap);

        loanStatusHashMap = LOAN_TRANSACTION_HELPER.approveLoan("01 March 2020", loanID);
        LoanStatusChecker.verifyLoanIsApproved(loanStatusHashMap);

        String loanDetails = LOAN_TRANSACTION_HELPER.getLoanDetails(REQUEST_SPEC, RESPONSE_SPEC, loanID);
        loanStatusHashMap = LOAN_TRANSACTION_HELPER.disburseLoanWithNetDisbursalAmount("02 March 2020", loanID,
                JsonPath.from(loanDetails).get("netDisbursalAmount").toString());
        LoanStatusChecker.verifyLoanIsActive(loanStatusHashMap);
        return loanID;
    }

    private Integer createLoanProduct(final String chargeId) {

        final String loanProductJSON = new LoanProductTestBuilder().withPrincipal("15,000.00").withNumberOfRepayments("4")
                .withRepaymentAfterEvery("1").withRepaymentTypeAsMonth().withinterestRatePerPeriod("1")
                .withAccountingRulePeriodicAccrual(new Account[] { ASSET_ACCOUNT, EXPENSE_ACCOUNT, INCOME_ACCOUNT, OVERPAYMENT_ACCOUNT })
                .withInterestRateFrequencyTypeAsMonths().withAmortizationTypeAsEqualInstallments().withInterestTypeAsDecliningBalance()
                .withFeeAndPenaltyAssetAccount(FEE_PENALTY_ACCOUNT).build(chargeId);
        return LOAN_TRANSACTION_HELPER.getLoanProductId(loanProductJSON);
    }

    private Integer applyForLoanApplication(final String clientID, final String loanProductID, final String date) {
        List<HashMap> collaterals = new ArrayList<>();
        Integer collateralId = CollateralManagementHelper.createCollateralProduct(REQUEST_SPEC, RESPONSE_SPEC);
        Assertions.assertNotNull(collateralId);
        Integer clientCollateralId = CollateralManagementHelper.createClientCollateral(REQUEST_SPEC, RESPONSE_SPEC, clientID, collateralId);
        Assertions.assertNotNull(clientCollateralId);
        addCollaterals(collaterals, clientCollateralId, BigDecimal.valueOf(1));

        String loanApplicationJSON = new LoanApplicationTestBuilder().withPrincipal("15,000.00").withLoanTermFrequency("4")
                .withLoanTermFrequencyAsMonths().withNumberOfRepayments("4").withRepaymentEveryAfter("1")
                .withRepaymentFrequencyTypeAsMonths().withInterestRatePerPeriod("2").withAmortizationTypeAsEqualInstallments()
                .withInterestTypeAsDecliningBalance().withInterestCalculationPeriodTypeSameAsRepaymentPeriod()
                .withExpectedDisbursementDate(date).withSubmittedOnDate(date).withCollaterals(collaterals)
                .build(clientID, loanProductID, null);
        return LOAN_TRANSACTION_HELPER.getLoanId(loanApplicationJSON);
    }

    private void addCollaterals(List<HashMap> collaterals, Integer collateralId, BigDecimal quantity) {
        collaterals.add(collaterals(collateralId, quantity));
    }

    private HashMap<String, String> collaterals(Integer collateralId, BigDecimal quantity) {
        HashMap<String, String> collateral = new HashMap<>(2);
        collateral.put("clientCollateralId", collateralId.toString());
        collateral.put("quantity", quantity.toString());
        return collateral;
    }

    private void getAndValidateExternalAssetOwnerTransferByLoan(Integer loanID, ExpectedExternalTransferData... expectedItems) {
        PageExternalTransferData retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue());
        assertEquals(expectedItems.length, retrieveResponse.getNumberOfElements());

        for (ExpectedExternalTransferData expected : expectedItems) {
            assertNotNull(retrieveResponse.getContent());
            Optional<ExternalTransferData> first = retrieveResponse.getContent().stream()
                    .filter(e -> Objects.equals(e.getTransferExternalId(), expected.transferExternalId)
                            && Objects.equals(e.getStatus(), expected.status))
                    .findFirst();
            assertTrue(first.isPresent());
            ExternalTransferData etd = first.get();
            assertEquals(expected.transferExternalId, etd.getTransferExternalId());

            assertEquals(expected.status, etd.getStatus());
            assertEquals(LocalDate.parse(expected.settlementDate), etd.getSettlementDate());
            assertEquals(LocalDate.parse(expected.effectiveFrom), etd.getEffectiveFrom());
            assertEquals(LocalDate.parse(expected.effectiveTo), etd.getEffectiveTo());
            if (!expected.detailsExpected) {
                assertNull(etd.getDetails());
            } else {
                assertNotNull(etd.getDetails());
                assertEquals(expected.totalOutstanding, etd.getDetails().getTotalOutstanding());
                assertEquals(expected.totalPrincipalOutstanding, etd.getDetails().getTotalPrincipalOutstanding());
                assertEquals(expected.totalInterestOutstanding, etd.getDetails().getTotalInterestOutstanding());
                assertEquals(expected.totalPenaltyOutstanding, etd.getDetails().getTotalPenaltyChargesOutstanding());
                assertEquals(expected.totalFeeOutstanding, etd.getDetails().getTotalFeeChargesOutstanding());
                assertEquals(expected.totalOverpaid, etd.getDetails().getTotalOverpaid());
            }
            if (expected.subStatus != null) {
                assertEquals(expected.subStatus, etd.getSubStatus());
            }
        }
    }

    private void getAndValidateThereIsActiveMapping(Integer loanID) {
        ExternalTransferData activeTransfer = EXTERNAL_ASSET_OWNER_HELPER.retrieveActiveTransferByLoanId((long) loanID);
        assertNotNull(activeTransfer);
        ExternalTransferData retrieveResponse = EXTERNAL_ASSET_OWNER_HELPER.retrieveTransfersByLoanId(loanID.longValue()).getContent()
                .stream().filter(transfer -> ExternalTransferData.StatusEnum.ACTIVE.equals(transfer.getStatus())).findFirst().get();
        assertEquals(retrieveResponse.getTransferId(), activeTransfer.getTransferId());
    }

    private void getAndValidateThereIsNoActiveMapping(Long loanId) {
        ExternalTransferData activeTransfer = EXTERNAL_ASSET_OWNER_HELPER.retrieveActiveTransferByLoanId(loanId);
        assertNull(activeTransfer);
    }

    private void getAndValidateThereIsNoActiveMapping(String transferExternalId) {
        ExternalTransferData activeTransfer = EXTERNAL_ASSET_OWNER_HELPER.retrieveActiveTransferByTransferExternalId(transferExternalId);
        assertNull(activeTransfer);
    }

    private void validateResponse(PostInitiateTransferResponse transferResponse, Integer loanID) {
        assertNotNull(transferResponse);
        assertNotNull(transferResponse.getResourceId());
        assertNotNull(transferResponse.getResourceExternalId());
        assertNotNull(transferResponse.getSubResourceId());
        assertEquals((long) loanID, transferResponse.getSubResourceId());
        assertNotNull(transferResponse.getSubResourceExternalId());
        assertNull(transferResponse.getChanges());
    }

    private void getAndValidateOwnerJournalEntries(String ownerExternalId, ExpectedJournalEntryData... expectedItems) {
        ExternalOwnerJournalEntryData result = EXTERNAL_ASSET_OWNER_HELPER.retrieveJournalEntriesOfOwner(ownerExternalId);
        assertNotNull(result);
        assertEquals(expectedItems.length, result.getJournalEntryData().getTotalElements());
        int i = 0;
        assertEquals(ownerExternalId, result.getOwnerData().getExternalId());
        for (ExpectedJournalEntryData expectedJournalEntryData : expectedItems) {
            assertTrue(expectedJournalEntryData.amount.compareTo(result.getJournalEntryData().getContent().get(i).getAmount()) == 0);
            assertEquals(expectedJournalEntryData.entryTypeId, result.getJournalEntryData().getContent().get(i).getEntryType().getId());
            assertEquals(expectedJournalEntryData.glAccountId, result.getJournalEntryData().getContent().get(i).getGlAccountId());
            assertEquals(expectedJournalEntryData.transactionDate, result.getJournalEntryData().getContent().get(i).getTransactionDate());
            assertEquals(expectedJournalEntryData.submittedOnDate, result.getJournalEntryData().getContent().get(i).getSubmittedOnDate());
            i++;
        }
    }

    private void getAndValidateThereIsJournalEntriesForTransfer(Long transferId, ExpectedJournalEntryData... expectedItems) {
        ExternalOwnerTransferJournalEntryData result = EXTERNAL_ASSET_OWNER_HELPER.retrieveJournalEntriesOfTransfer(transferId);
        assertNotNull(result);
        long totalElements = result.getJournalEntryData().getTotalElements();
        assertEquals(expectedItems.length, totalElements);
        int i = 0;
        assertEquals(transferId, result.getTransferData().getTransferId());
        for (ExpectedJournalEntryData expectedJournalEntryData : expectedItems) {
            assertTrue(expectedJournalEntryData.amount.compareTo(result.getJournalEntryData().getContent().get(i).getAmount()) == 0);
            assertEquals(expectedJournalEntryData.entryTypeId, result.getJournalEntryData().getContent().get(i).getEntryType().getId());
            assertEquals(expectedJournalEntryData.glAccountId, result.getJournalEntryData().getContent().get(i).getGlAccountId());
            assertEquals(expectedJournalEntryData.transactionDate, result.getJournalEntryData().getContent().get(i).getTransactionDate());
            assertEquals(expectedJournalEntryData.submittedOnDate, result.getJournalEntryData().getContent().get(i).getSubmittedOnDate());
            i++;
        }
    }

    private void getAndValidateThereIsNoJournalEntriesForTransfer(Long transferId) {
        ExternalOwnerTransferJournalEntryData result = EXTERNAL_ASSET_OWNER_HELPER.retrieveJournalEntriesOfTransfer(transferId);
        assertNull(result.getJournalEntryData());
    }

    @RequiredArgsConstructor()
    public static class ExpectedExternalTransferData {

        private final ExternalTransferData.StatusEnum status;

        private final String transferExternalId;

        private final String settlementDate;

        private final String effectiveFrom;
        private final String effectiveTo;
        private final ExternalTransferData.SubStatusEnum subStatus;
        private final boolean detailsExpected;

        private final BigDecimal totalOutstanding;
        private final BigDecimal totalPrincipalOutstanding;
        private final BigDecimal totalInterestOutstanding;
        private final BigDecimal totalPenaltyOutstanding;
        private final BigDecimal totalFeeOutstanding;
        private final BigDecimal totalOverpaid;

        static ExpectedExternalTransferData expected(ExternalTransferData.StatusEnum status, String transferExternalId,
                String settlementDate, String effectiveFrom, String effectiveTo, boolean detailsExpected, BigDecimal totalOutstanding,
                BigDecimal totalPrincipalOutstanding, BigDecimal totalInterestOutstanding, BigDecimal totalPenaltyOutstanding,
                BigDecimal totalFeeOutstanding, BigDecimal totalOverpaid) {
            return new ExpectedExternalTransferData(status, transferExternalId, settlementDate, effectiveFrom, effectiveTo, null,
                    detailsExpected, totalOutstanding, totalPrincipalOutstanding, totalInterestOutstanding, totalPenaltyOutstanding,
                    totalFeeOutstanding, totalOverpaid);
        }

        static ExpectedExternalTransferData expected(ExternalTransferData.StatusEnum status, String transferExternalId,
                String settlementDate, String effectiveFrom, String effectiveTo) {
            return new ExpectedExternalTransferData(status, transferExternalId, settlementDate, effectiveFrom, effectiveTo, null, false,
                    null, null, null, null, null, null);
        }

        static ExpectedExternalTransferData expected(ExternalTransferData.StatusEnum status, String transferExternalId,
                String settlementDate, String effectiveFrom, String effectiveTo, ExternalTransferData.SubStatusEnum subStatus) {
            return new ExpectedExternalTransferData(status, transferExternalId, settlementDate, effectiveFrom, effectiveTo, subStatus,
                    false, null, null, null, null, null, null);
        }
    }

    @RequiredArgsConstructor()
    public static class ExpectedJournalEntryData {

        private final Long glAccountId;

        private final Long entryTypeId;

        private final BigDecimal amount;
        private final LocalDate transactionDate;
        private final LocalDate submittedOnDate;

        static ExpectedJournalEntryData expected(Long glAccountId, Long entryTypeId, BigDecimal amount, LocalDate transactionDate,
                LocalDate submittedOnDate) {
            return new ExpectedJournalEntryData(glAccountId, entryTypeId, amount, transactionDate, submittedOnDate);
        }

    }
}
