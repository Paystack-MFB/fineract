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
package org.apache.fineract.portfolio.loanaccount.service;

import static org.apache.fineract.portfolio.loanaccount.domain.Loan.ACTUAL_DISBURSEMENT_DATE;
import static org.apache.fineract.portfolio.loanaccount.domain.Loan.CLOSED_ON_DATE;
import static org.apache.fineract.portfolio.loanaccount.domain.Loan.EXTERNAL_ID;
import static org.apache.fineract.portfolio.loanaccount.domain.Loan.PARAM_STATUS;
import static org.apache.fineract.portfolio.loanaccount.domain.Loan.TRANSACTION_DATE;
import static org.apache.fineract.portfolio.loanaccount.domain.Loan.WRITTEN_OFF_ON_DATE;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.cob.exceptions.LoanAccountLockCannotBeOverruledException;
import org.apache.fineract.cob.service.LoanAccountLockService;
import org.apache.fineract.infrastructure.codes.domain.CodeValue;
import org.apache.fineract.infrastructure.codes.domain.CodeValueRepositoryWrapper;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.configuration.service.TemporaryConfigurationServiceContainer;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformServiceUnavailableException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.dataqueries.data.EntityTables;
import org.apache.fineract.infrastructure.dataqueries.data.StatusEnum;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanAcceptTransferBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanAdjustTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBalanceChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanChargebackTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanCloseAsRescheduleBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanCloseBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanDisbursalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanInitiateTransferBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanInterestRecalculationBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanReassignOfficerBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanRejectTransferBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanRemoveOfficerBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanRescheduledDueCalendarChangeBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanUndoDisbursalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanUndoLastDisbursalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanUpdateDisbursementDataBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanWithdrawTransferBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargeOffPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargeOffPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanDisbursalTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionInterestPaymentWaiverPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionInterestPaymentWaiverPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanUndoChargeOffBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanUndoWrittenOffBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanWaiveInterestBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanWrittenOffPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanWrittenOffPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepositoryWrapper;
import org.apache.fineract.organisation.holiday.service.HolidayUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.organisation.teller.data.CashierTransactionDataValidator;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.domain.AccountAssociationType;
import org.apache.fineract.portfolio.account.domain.AccountAssociations;
import org.apache.fineract.portfolio.account.domain.AccountAssociationsRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.AccountTransferStandingInstruction;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionPriority;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.accountdetails.domain.AccountType;
import org.apache.fineract.portfolio.calendar.domain.Calendar;
import org.apache.fineract.portfolio.calendar.domain.CalendarEntityType;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstance;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstanceRepository;
import org.apache.fineract.portfolio.calendar.domain.CalendarRepository;
import org.apache.fineract.portfolio.calendar.domain.CalendarType;
import org.apache.fineract.portfolio.calendar.exception.CalendarParameterUpdateNotSupportedException;
import org.apache.fineract.portfolio.calendar.service.CalendarUtils;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.collectionsheet.command.CollectionSheetBulkDisbursalCommand;
import org.apache.fineract.portfolio.collectionsheet.command.CollectionSheetBulkRepaymentCommand;
import org.apache.fineract.portfolio.collectionsheet.command.SingleDisbursalCommand;
import org.apache.fineract.portfolio.collectionsheet.command.SingleRepaymentCommand;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.command.LoanUpdateCommand;
import org.apache.fineract.portfolio.loanaccount.data.AccountingBridgeDataDTO;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.GLIMAccountInfoRepository;
import org.apache.fineract.portfolio.loanaccount.domain.GroupLoanIndividualMonitoringAccount;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanEvent;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallmentRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSubStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTermVariationType;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTermVariations;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelation;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationTypeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.MoneyHolder;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.TransactionCtx;
import org.apache.fineract.portfolio.loanaccount.exception.DateMismatchException;
import org.apache.fineract.portfolio.loanaccount.exception.ExceedingTrancheCountException;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidLoanStateTransitionException;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidLoanTransactionTypeException;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidPaidInAdvanceAmountException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanForeclosureException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanMultiDisbursementException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanOfficerAssignmentException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanOfficerUnassignmentException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanTransactionNotFoundException;
import org.apache.fineract.portfolio.loanaccount.exception.MultiDisbursementDataNotAllowedException;
import org.apache.fineract.portfolio.loanaccount.exception.MultiDisbursementDataRequiredException;
import org.apache.fineract.portfolio.loanaccount.exception.UndoLastTrancheDisbursementException;
import org.apache.fineract.portfolio.loanaccount.guarantor.service.GuarantorDomainService;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModel;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleModelPeriod;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleHistoryWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanAccountingBridgeMapper;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanMapper;
import org.apache.fineract.portfolio.loanaccount.rescheduleloan.domain.LoanRescheduleRequest;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanApplicationValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanDownPaymentTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanOfficerValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanUpdateCommandFromApiJsonDeserializer;
import org.apache.fineract.portfolio.loanaccount.service.adjustment.LoanAdjustmentParameter;
import org.apache.fineract.portfolio.loanaccount.service.adjustment.LoanAdjustmentService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.exception.LinkedAccountRequiredException;
import org.apache.fineract.portfolio.loanproduct.service.LoanEnumerations;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.repaymentwithpostdatedchecks.domain.PostDatedChecks;
import org.apache.fineract.portfolio.repaymentwithpostdatedchecks.domain.PostDatedChecksRepository;
import org.apache.fineract.portfolio.repaymentwithpostdatedchecks.service.RepaymentWithPostDatedChecksAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.transfer.api.TransferApiConstants;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class LoanWritePlatformServiceJpaRepositoryImpl implements LoanWritePlatformService {

    private final PlatformSecurityContext context;
    private final LoanTransactionValidator loanTransactionValidator;
    private final LoanUpdateCommandFromApiJsonDeserializer loanUpdateCommandFromApiJsonDeserializer;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final LoanAccountDomainService loanAccountDomainService;
    private final NoteRepository noteRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final LoanTransactionRelationRepository loanTransactionRelationRepository;
    private final LoanAssembler loanAssembler;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final CalendarInstanceRepository calendarInstanceRepository;
    private final PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    private final HolidayRepositoryWrapper holidayRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final WorkingDaysRepositoryWrapper workingDaysRepository;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private final LoanReadPlatformService loanReadPlatformService;
    private final FromJsonHelper fromApiJsonHelper;
    private final CalendarRepository calendarRepository;
    private final LoanScheduleHistoryWritePlatformService loanScheduleHistoryWritePlatformService;
    private final LoanApplicationValidator loanApplicationValidator;
    private final AccountAssociationsRepository accountAssociationRepository;
    private final AccountTransferDetailRepository accountTransferDetailRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final GuarantorDomainService guarantorDomainService;
    private final LoanUtilService loanUtilService;
    private final EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService;
    private final CodeValueRepositoryWrapper codeValueRepository;
    private final CashierTransactionDataValidator cashierTransactionDataValidator;
    private final GLIMAccountInfoRepository glimRepository;
    private final LoanRepository loanRepository;
    private final RepaymentWithPostDatedChecksAssembler repaymentWithPostDatedChecksAssembler;
    private final PostDatedChecksRepository postDatedChecksRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanLifecycleStateMachine loanLifecycleStateMachine;
    private final LoanAccountLockService loanAccountLockService;
    private final ExternalIdFactory externalIdFactory;
    private final LoanAccrualTransactionBusinessEventService loanAccrualTransactionBusinessEventService;
    private final ErrorHandler errorHandler;
    private final LoanDownPaymentHandlerService loanDownPaymentHandlerService;
    private final LoanTransactionAssembler loanTransactionAssembler;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanOfficerValidator loanOfficerValidator;
    private final LoanDownPaymentTransactionValidator loanDownPaymentTransactionValidator;
    private final LoanDisbursementService loanDisbursementService;
    private final LoanScheduleService loanScheduleService;
    private final LoanChargeValidator loanChargeValidator;
    private final LoanOfficerService loanOfficerService;
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService;
    private final LoanAccountService loanAccountService;
    private final LoanJournalEntryPoster journalEntryPoster;
    private final LoanAdjustmentService loanAdjustmentService;
    private final LoanAccountingBridgeMapper loanAccountingBridgeMapper;
    private final LoanMapper loanMapper;
    private final LoanTransactionProcessingService loanTransactionProcessingService;
    private final LoanBalanceService loanBalanceService;
    private final LoanTransactionService loanTransactionService;

    @Transactional
    @Override
    public CommandProcessingResult disburseGLIMLoan(final Long loanId, final JsonCommand command) {
        final Long parentLoanId = loanId;
        GroupLoanIndividualMonitoringAccount parentLoan = glimRepository.findById(parentLoanId).orElseThrow();
        List<Loan> childLoans = this.loanRepository.findByGlimId(loanId);
        CommandProcessingResult result = null;
        int count = 0;
        for (Loan loan : childLoans) {
            result = disburseLoan(loan.getId(), command, false);
            if (result.getLoanId() != null) {
                count++;
                // if all the child loans are approved, mark the parent loan as
                // approved
                if (count == parentLoan.getChildAccountsCount()) {
                    parentLoan.setLoanStatus(LoanStatus.ACTIVE.getValue());
                    glimRepository.save(parentLoan);
                }
            }
        }
        return result;
    }

    @Override
    public CommandProcessingResult disburseLoan(Long loanId, JsonCommand command, Boolean isAccountTransfer) {
        return disburseLoan(loanId, command, isAccountTransfer, false);
    }

    @Transactional
    @Override
    public CommandProcessingResult disburseLoan(final Long loanId, final JsonCommand command, Boolean isAccountTransfer,
            Boolean isWithoutAutoPayment) {
        loanTransactionValidator.validateDisbursement(command, isAccountTransfer, loanId);

        Loan loan = loanAssembler.assembleFrom(loanId);

        if (loan.loanProduct().isDisallowExpectedDisbursements()) {
            List<LoanDisbursementDetails> filteredList = loan.getDisbursementDetails().stream()
                    .filter(disbursementDetails -> disbursementDetails.actualDisbursementDate() == null).toList();
            // Check whether a new LoanDisbursementDetails is required
            if (filteredList.isEmpty()) {
                // create artificial 'tranche/expected disbursal' as current disburse code expects it for
                // multi-disbursal products
                final LocalDate artificialExpectedDate = loan.getExpectedDisbursedOnLocalDate();
                LoanDisbursementDetails disbursementDetail = new LoanDisbursementDetails(artificialExpectedDate, null,
                        loan.getDisbursedAmount(), null, false);
                disbursementDetail.updateLoan(loan);
                loan.getAllDisbursementDetails().add(disbursementDetail);
            }
        }

        final LocalDate nextPossibleRepaymentDate = loan.getNextPossibleRepaymentDateForRescheduling();
        final LocalDate rescheduledRepaymentDate = command.localDateValueOfParameterNamed("adjustRepaymentDate");
        final LocalDate actualDisbursementDate = command.localDateValueOfParameterNamed("actualDisbursementDate");
        if (!loan.isMultiDisburmentLoan()) {
            loan.setActualDisbursementDate(actualDisbursementDate);
        }

        // validate actual disbursement date against meeting date
        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, null);

        businessEventNotifierService.notifyPreBusinessEvent(new LoanDisbursalBusinessEvent(loan));

        List<Long> existingTransactionIds = new ArrayList<>();
        List<Long> existingReversedTransactionIds = new ArrayList<>();

        final AppUser currentUser = getAppUserIfPresent();
        final Map<String, Object> changes = new LinkedHashMap<>();

        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);
        if (paymentDetail != null && paymentDetail.getPaymentType() != null && paymentDetail.getPaymentType().getIsCashPayment()) {
            BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
            this.cashierTransactionDataValidator.validateOnLoanDisbursal(currentUser, loan.getCurrencyCode(), transactionAmount);
        }
        final boolean isPaymentTypeApplicableForDisbursementCharge = configurationDomainService
                .isPaymentTypeApplicableForDisbursementCharge();

        // Recalculate first repayment date based in actual disbursement date.
        updateLoanCounters(loan, actualDisbursementDate);
        Money amountBeforeAdjust = loan.getPrincipal();
        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);

        if (canDisburse(loan)) {
            // Get netDisbursalAmount from disbursal screen field.
            final BigDecimal netDisbursalAmount = command
                    .bigDecimalValueOfParameterNamed(LoanApiConstants.disbursementNetDisbursalAmountParameterName);
            if (netDisbursalAmount != null) {
                loan.setNetDisbursalAmount(netDisbursalAmount);
            }
            Money disburseAmount = loanDisbursementService.adjustDisburseAmount(loan, command, actualDisbursementDate);
            Money amountToDisburse = disburseAmount.copy();
            boolean recalculateSchedule = amountBeforeAdjust.isNotEqualTo(loan.getPrincipal());
            final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

            if (loan.isTopup() && loan.getClientId() != null) {
                final BigDecimal loanOutstanding = loanApplicationValidator.validateTopupLoan(loan, actualDisbursementDate);

                amountToDisburse = disburseAmount.minus(loanOutstanding);
                disburseLoanToLoan(loan, command, loanOutstanding);
            }

            LoanTransaction disbursementTransaction = null;
            if (isAccountTransfer) {
                disburseLoanToSavings(loan, command, amountToDisburse, paymentDetail);
                existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
                existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
            } else {
                existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
                existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
                disbursementTransaction = LoanTransaction.disbursement(loan, amountToDisburse, paymentDetail, actualDisbursementDate,
                        txnExternalId, loan.getTotalOverpaidAsMoney());
                disbursementTransaction.updateLoan(loan);
                loan.addLoanTransaction(disbursementTransaction);
                loanTransactionRepository.saveAndFlush(disbursementTransaction);
            }
            if (loan.getRepaymentScheduleInstallments().isEmpty()) {
                /*
                 * If no schedule, generate one (applicable to non-tranche multi-disbursal loans)
                 */
                recalculateSchedule = true;
            }

            regenerateScheduleOnDisbursement(command, loan, recalculateSchedule, scheduleGeneratorDTO, nextPossibleRepaymentDate,
                    rescheduledRepaymentDate);
            boolean downPaymentEnabled = loan.getLoanProductRelatedDetail().isEnableDownPayment();
            if (loan.isInterestBearingAndInterestRecalculationEnabled() || downPaymentEnabled) {
                createAndSaveLoanScheduleArchive(loan, scheduleGeneratorDTO);
            }
            disburseLoan(command, isPaymentTypeApplicableForDisbursementCharge, paymentDetail, loan, currentUser, changes,
                    scheduleGeneratorDTO);
            loan.adjustNetDisbursalAmount(amountToDisburse.getAmount());

            loanAccrualsProcessingService.reprocessExistingAccruals(loan);

            LocalDate firstInstallmentDueDate = loan.fetchRepaymentScheduleInstallment(1).getDueDate();
            if (loan.isInterestBearingAndInterestRecalculationEnabled()
                    && (DateUtils.isBeforeBusinessDate(firstInstallmentDueDate) || loan.isDisbursementMissed())) {
                loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
            }

            if (loan.isAutoRepaymentForDownPaymentEnabled() && !isWithoutAutoPayment) {
                // updating linked savings account for auto down payment transaction for disbursement to savings account
                if (isAccountTransfer && loan.shouldCreateStandingInstructionAtDisbursement()) {
                    final PortfolioAccountData linkedSavingsAccountData = this.accountAssociationsReadPlatformService
                            .retriveLoanLinkedAssociation(loanId);
                    final SavingsAccount fromSavingsAccount = null;
                    final boolean isRegularTransaction = true;
                    final boolean isExceptionForBalanceCheck = false;

                    BigDecimal disbursedAmountPercentageForDownPayment = loan.getLoanRepaymentScheduleDetail()
                            .getDisbursedAmountPercentageForDownPayment();
                    Money downPaymentMoney = Money.of(loan.getCurrency(),
                            MathUtil.percentageOf(amountToDisburse.getAmount(), disbursedAmountPercentageForDownPayment, 19));
                    if (loan.getLoanProductRelatedDetail().getInstallmentAmountInMultiplesOf() != null) {
                        downPaymentMoney = Money.roundToMultiplesOf(downPaymentMoney,
                                loan.getLoanProductRelatedDetail().getInstallmentAmountInMultiplesOf());
                    }
                    final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(actualDisbursementDate,
                            downPaymentMoney.getAmount(), PortfolioAccountType.SAVINGS, PortfolioAccountType.LOAN,
                            linkedSavingsAccountData.getId(), loan.getId(),
                            "To loan " + loan.getAccountNumber() + " from savings " + linkedSavingsAccountData.getAccountNo()
                                    + " Standing instruction transfer ",
                            locale, fmt, null, null, LoanTransactionType.DOWN_PAYMENT.getValue(), null, null,
                            AccountTransferType.LOAN_DOWN_PAYMENT.getValue(), null, null, ExternalId.empty(), null, null,
                            fromSavingsAccount, isRegularTransaction, isExceptionForBalanceCheck);
                    this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
                } else {
                    loanDownPaymentHandlerService.handleDownPayment(scheduleGeneratorDTO, command, disbursementTransaction, loan);
                    loanAccrualsProcessingService.reprocessExistingAccruals(loan);
                    if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
                        loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
                    }
                }
            }
        }
        if (!changes.isEmpty()) {
            loan.updateLoanScheduleDependentDerivedFields();
            loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

            createNote(loan, command, changes);
            // auto create standing instruction
            createStandingInstruction(loan);
        }

        final Set<LoanCharge> loanCharges = loan.getActiveCharges();
        final Map<Long, BigDecimal> disBuLoanCharges = new HashMap<>();
        for (final LoanCharge loanCharge : loanCharges) {
            if (loanCharge.isDueAtDisbursement() && loanCharge.getChargePaymentMode().isPaymentModeAccountTransfer()
                    && loanCharge.isChargePending()) {
                disBuLoanCharges.put(loanCharge.getId(), loanCharge.amountOutstanding());
            }
        }

        for (final Map.Entry<Long, BigDecimal> entrySet : disBuLoanCharges.entrySet()) {
            final PortfolioAccountData savingAccountData = this.accountAssociationsReadPlatformService.retriveLoanLinkedAssociation(loanId);
            final SavingsAccount fromSavingsAccount = null;
            final boolean isRegularTransaction = true;
            final boolean isExceptionForBalanceCheck = false;
            final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(actualDisbursementDate, entrySet.getValue(),
                    PortfolioAccountType.SAVINGS, PortfolioAccountType.LOAN, savingAccountData.getId(), loanId, "Loan Charge Payment",
                    locale, fmt, null, null, LoanTransactionType.REPAYMENT_AT_DISBURSEMENT.getValue(), entrySet.getKey(), null,
                    AccountTransferType.CHARGE_PAYMENT.getValue(), null, null, ExternalId.empty(), null, null, fromSavingsAccount,
                    isRegularTransaction, isExceptionForBalanceCheck);
            this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
        }
        updateRecurringCalendarDatesForInterestRecalculation(loan);
        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);
        this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

        // Post Dated Checks
        if (command.parameterExists("postDatedChecks")) {
            // get repayment with post dates checks to update
            Set<PostDatedChecks> postDatedChecks = this.repaymentWithPostDatedChecksAssembler.fromParsedJson(command.json(), loan);
            updatePostDatedChecks(postDatedChecks);
        }

        businessEventNotifierService.notifyPostBusinessEvent(new LoanDisbursalBusinessEvent(loan));

        Long disbursalTransactionId = null;
        ExternalId disbursalTransactionExternalId = null;

        if (!isAccountTransfer) {
            // If accounting is not periodic accrual, the last transaction might be the accrual not the disbursement
            LoanTransaction disbursalTransaction = Lists.reverse(loan.getLoanTransactions()).stream()
                    .filter(e -> LoanTransactionType.DISBURSEMENT.equals(e.getTypeOf())).findFirst().orElseThrow();
            disbursalTransactionId = disbursalTransaction.getId();
            disbursalTransactionExternalId = disbursalTransaction.getExternalId();
            businessEventNotifierService.notifyPostBusinessEvent(new LoanDisbursalTransactionBusinessEvent(disbursalTransaction));
        }

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loan.getId()) //
                .withEntityExternalId(loan.getExternalId()) //
                .withSubEntityId(disbursalTransactionId) //
                .withSubEntityExternalId(disbursalTransactionExternalId) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    private void createNote(Loan loan, JsonCommand command, Map<String, Object> changes) {
        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
            final Note note = Note.loanNote(loan, noteText);
            this.noteRepository.save(note);
        }
    }

    private void disburseLoan(JsonCommand command, boolean isPaymentTypeApplicableForDisbursementCharge, PaymentDetail paymentDetail,
            Loan loan, AppUser currentUser, Map<String, Object> changes, ScheduleGeneratorDTO scheduleGeneratorDTO) {
        final PaymentDetail paymentDetail1 = isPaymentTypeApplicableForDisbursementCharge ? paymentDetail : null;
        final LocalDate actualDisbursementDate1 = command.localDateValueOfParameterNamed(ACTUAL_DISBURSEMENT_DATE);

        loan.setDisbursedBy(currentUser);
        loan.updateLoanScheduleDependentDerivedFields();

        changes.put(Loan.LOCALE, command.locale());
        changes.put(Loan.DATE_FORMAT, command.dateFormat());
        changes.put(ACTUAL_DISBURSEMENT_DATE, command.stringValueOfParameterNamed(ACTUAL_DISBURSEMENT_DATE));

        boolean disbursementMissedParam = loan.isDisbursementMissed();
        LocalDate firstInstallmentDueDate = loan.fetchRepaymentScheduleInstallment(1).getDueDate();
        if ((loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()
                && (DateUtils.isBeforeBusinessDate(firstInstallmentDueDate) || disbursementMissedParam))) {
            loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
        } else {
            loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        }

        loan.updateSummaryWithTotalFeeChargesDueAtDisbursement(loan.deriveSumTotalOfChargesDueAtDisbursement());
        loan.updateLoanRepaymentPeriodsDerivedFields(actualDisbursementDate1);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.LOAN_DISBURSED,
                actualDisbursementDate1);
        loanDisbursementService.handleDisbursementTransaction(loan, actualDisbursementDate1, paymentDetail1);
        loanBalanceService.updateLoanSummaryDerivedFields(loan);
        final Money interestApplied = Money.of(loan.getCurrency(), loan.getSummary().getTotalInterestCharged());

        /*
         * Add an interest applied transaction of the interest is accrued upfront (Up front accrual), no accounting or
         * cash based accounting is selected
         */
        if (((loan.isMultiDisburmentLoan() && loan.getDisbursedLoanDisbursementDetails().size() == 1) || !loan.isMultiDisburmentLoan())
                && loan.isNoneOrCashOrUpfrontAccrualAccountingEnabledOnLoanProduct() && interestApplied.isGreaterThanZero()) {
            ExternalId externalId = ExternalId.empty();
            if (TemporaryConfigurationServiceContainer.isExternalIdAutoGenerationEnabled()) {
                externalId = ExternalId.generate();
            }
            final LoanTransaction interestAppliedTransaction = LoanTransaction.accrueInterest(loan.getOffice(), loan, interestApplied,
                    actualDisbursementDate1, externalId);
            loan.addLoanTransaction(interestAppliedTransaction);
        }

        if (loan.getLoanProduct().isMultiDisburseLoan() || loan.isProgressiveSchedule()) {
            final List<LoanTransaction> allNonContraTransactionsPostDisbursement = loanTransactionRepository
                    .findNonReversedTransactionsForReprocessingByLoan(loan);
            if (!allNonContraTransactionsPostDisbursement.isEmpty()) {
                reprocessLoanTransactionsService.reprocessTransactions(loan);
            }
            loanBalanceService.updateLoanSummaryDerivedFields(loan);
        }

        loanLifecycleStateMachine.transition(LoanEvent.LOAN_DISBURSED, loan);
        changes.put(PARAM_STATUS, LoanEnumerations.status(loan.getLoanStatus()));
    }

    private void updatePostDatedChecks(Set<PostDatedChecks> postDatedChecks) {
        this.postDatedChecksRepository.saveAll(postDatedChecks);
    }

    private void createAndSaveLoanScheduleArchive(final Loan loan, ScheduleGeneratorDTO scheduleGeneratorDTO) {
        LoanRescheduleRequest loanRescheduleRequest = null;
        LoanScheduleModel loanScheduleModel = loanMapper.regenerateScheduleModel(scheduleGeneratorDTO, loan);
        List<LoanRepaymentScheduleInstallment> installments = retrieveRepaymentScheduleFromModel(loanScheduleModel);
        this.loanScheduleHistoryWritePlatformService.createAndSaveLoanScheduleArchive(installments, loan, loanRescheduleRequest);
    }

    /**
     * create standing instruction for disbursed loan
     *
     * @param loan
     *            the disbursed loan
     **/
    private void createStandingInstruction(Loan loan) {

        if (loan.shouldCreateStandingInstructionAtDisbursement()) {
            AccountAssociations accountAssociations = this.accountAssociationRepository.findByLoanIdAndType(loan.getId(),
                    AccountAssociationType.LINKED_ACCOUNT_ASSOCIATION.getValue());

            if (accountAssociations != null) {

                SavingsAccount linkedSavingsAccount = accountAssociations.linkedSavingsAccount();

                // name is auto-generated
                final String name = "To loan " + loan.getAccountNumber() + " from savings " + linkedSavingsAccount.getAccountNumber();
                final Office fromOffice = loan.getOffice();
                final Client fromClient = loan.getClient();
                final Office toOffice = loan.getOffice();
                final Client toClient = loan.getClient();
                final Integer priority = StandingInstructionPriority.MEDIUM.getValue();
                final Integer transferType = AccountTransferType.LOAN_REPAYMENT.getValue();
                final Integer instructionType = StandingInstructionType.DUES.getValue();
                final Integer status = StandingInstructionStatus.ACTIVE.getValue();
                final Integer recurrenceType = AccountTransferRecurrenceType.AS_PER_DUES.getValue();
                final LocalDate validFrom = DateUtils.getBusinessLocalDate();

                AccountTransferDetails accountTransferDetails = AccountTransferDetails.savingsToLoanTransfer(fromOffice, fromClient,
                        linkedSavingsAccount, toOffice, toClient, loan, transferType);

                AccountTransferStandingInstruction accountTransferStandingInstruction = AccountTransferStandingInstruction.create(
                        accountTransferDetails, name, priority, instructionType, status, null, validFrom, null, recurrenceType, null, null,
                        null);
                accountTransferDetails.updateAccountTransferStandingInstruction(accountTransferStandingInstruction);

                this.accountTransferDetailRepository.save(accountTransferDetails);
            }
        }
    }

    private void updateRecurringCalendarDatesForInterestRecalculation(final Loan loan) {

        if (loan.isInterestBearingAndInterestRecalculationEnabled()
                && loan.loanInterestRecalculationDetails().getRestFrequencyType().isSameAsRepayment()) {
            final CalendarInstance calendarInstanceForInterestRecalculation = this.calendarInstanceRepository
                    .findByEntityIdAndEntityTypeIdAndCalendarTypeId(loan.loanInterestRecalculationDetailId(),
                            CalendarEntityType.LOAN_RECALCULATION_REST_DETAIL.getValue(), CalendarType.COLLECTION.getValue());

            Calendar calendarForInterestRecalculation = calendarInstanceForInterestRecalculation.getCalendar();
            calendarForInterestRecalculation.updateStartAndEndDate(loan.getDisbursementDate(), loan.getMaturityDate());
            this.calendarRepository.save(calendarForInterestRecalculation);
        }

    }

    private Loan saveAndFlushLoanWithDataIntegrityViolationChecks(final Loan loan) {
        /*
         * Due to the "saveAndFlushLoanWithDataIntegrityViolationChecks" method the loan is saved and flushed in the
         * middle of the transaction. EclipseLink is in some situations are saving inconsistently the newly created
         * associations, like the newly created repayment schedule installments. The save and flush cannot be removed
         * safely till any native queries are used as part of this transaction either. See:
         * this.loanAccountDomainService.recalculateAccruals(loan);
         */
        try {
            loanRepaymentScheduleInstallmentRepository.saveAll(loan.getRepaymentScheduleInstallments());
            return this.loanRepositoryWrapper.saveAndFlush(loan);
        } catch (final JpaSystemException | DataIntegrityViolationException e) {
            final Throwable realCause = e.getCause();
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");
            if (realCause.getMessage().toLowerCase().contains("external_id_unique")) {
                baseDataValidator.reset().parameter(LoanApiConstants.externalIdParameterName).failWithCode("value.must.be.unique");
            }
            if (!dataValidationErrors.isEmpty()) {
                throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                        dataValidationErrors, e);
            }
            throw e;
        }
    }

    private void saveLoanWithDataIntegrityViolationChecks(final Loan loan) {
        try {
            this.loanRepositoryWrapper.save(loan);
        } catch (final JpaSystemException | DataIntegrityViolationException e) {
            final Throwable realCause = e.getCause();
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");
            if (realCause.getMessage().toLowerCase().contains("external_id_unique")) {
                baseDataValidator.reset().parameter(LoanApiConstants.externalIdParameterName).failWithCode("value.must.be.unique");
            }
            if (!dataValidationErrors.isEmpty()) {
                throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                        dataValidationErrors, e);
            }
        }
    }

    /****
     * TODO Vishwas: Pair with Ashok and re-factor collection sheet code-base
     *
     * May of the changes made to disburseLoan aren't being made here, should refactor to reuse disburseLoan ASAP
     *****/
    @Transactional
    @Override
    public Map<String, Object> bulkLoanDisbursal(final JsonCommand command, final CollectionSheetBulkDisbursalCommand bulkDisbursalCommand,
            Boolean isAccountTransfer) {
        final AppUser currentUser = getAppUserIfPresent();

        final SingleDisbursalCommand[] disbursalCommand = bulkDisbursalCommand.getDisburseTransactions();
        final Map<String, Object> changes = new LinkedHashMap<>();
        if (disbursalCommand == null) {
            return changes;
        }

        final LocalDate nextPossibleRepaymentDate = null;
        final LocalDate rescheduledRepaymentDate = null;

        for (final SingleDisbursalCommand singleLoanDisbursalCommand : disbursalCommand) {
            Loan loan = this.loanAssembler.assembleFrom(singleLoanDisbursalCommand.getLoanId());
            final LocalDate actualDisbursementDate = command.localDateValueOfParameterNamed("actualDisbursementDate");

            // validate ActualDisbursement Date Against Expected Disbursement
            // Date
            LoanProduct loanProduct = loan.loanProduct();
            if (loanProduct.isSyncExpectedWithDisbursementDate()) {
                syncExpectedDateWithActualDisbursementDate(loan, actualDisbursementDate);
            }
            checkClientOrGroupActive(loan);
            businessEventNotifierService.notifyPreBusinessEvent(new LoanDisbursalBusinessEvent(loan));

            final List<Long> existingTransactionIds = new ArrayList<>();
            final List<Long> existingReversedTransactionIds = new ArrayList<>();

            final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

            // Bulk disbursement should happen on meeting date (mostly from
            // collection sheet).
            // FIXME: AA - this should be first meeting date based on
            // disbursement date and next available meeting dates
            // assuming repayment schedule won't regenerate because expected
            // disbursement and actual disbursement happens on same date
            loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_DISBURSED);
            updateLoanCounters(loan, actualDisbursementDate);
            if (canDisburse(loan)) {
                Money amountBeforeAdjust = loan.getPrincipal();
                Money disburseAmount = loanDisbursementService.adjustDisburseAmount(loan, command, actualDisbursementDate);
                boolean recalculateSchedule = amountBeforeAdjust.isNotEqualTo(loan.getPrincipal());
                final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);
                if (isAccountTransfer) {
                    disburseLoanToSavings(loan, command, disburseAmount, paymentDetail);
                    existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
                    existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));

                } else {
                    existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
                    existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
                    LoanTransaction disbursementTransaction = LoanTransaction.disbursement(loan, disburseAmount, paymentDetail,
                            actualDisbursementDate, txnExternalId, loan.getTotalOverpaidAsMoney());
                    disbursementTransaction.updateLoan(loan);
                    loan.addLoanTransaction(disbursementTransaction);
                    businessEventNotifierService
                            .notifyPostBusinessEvent(new LoanDisbursalTransactionBusinessEvent(disbursementTransaction));
                }
                LocalDate recalculateFrom = null;
                final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
                regenerateScheduleOnDisbursement(command, loan, recalculateSchedule, scheduleGeneratorDTO, nextPossibleRepaymentDate,
                        rescheduledRepaymentDate);
                boolean downPaymentEnabled = loan.getLoanProductRelatedDetail().isEnableDownPayment();
                if (loan.isInterestBearingAndInterestRecalculationEnabled() || downPaymentEnabled) {
                    createAndSaveLoanScheduleArchive(loan, scheduleGeneratorDTO);
                }
                disburseLoan(command, configurationDomainService.isPaymentTypeApplicableForDisbursementCharge(), paymentDetail, loan,
                        currentUser, changes, scheduleGeneratorDTO);

                loanAccrualsProcessingService.reprocessExistingAccruals(loan);

                LocalDate firstInstallmentDueDate = loan.fetchRepaymentScheduleInstallment(1).getDueDate();
                if (loan.isInterestBearingAndInterestRecalculationEnabled()
                        && (DateUtils.isBeforeBusinessDate(firstInstallmentDueDate) || loan.isDisbursementMissed())) {
                    loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
                }
            }
            if (!changes.isEmpty()) {
                createNote(loan, command, changes);
                loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
                journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
                loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
            }
            final Set<LoanCharge> loanCharges = loan.getActiveCharges();
            final Map<Long, BigDecimal> disBuLoanCharges = new HashMap<>();
            for (final LoanCharge loanCharge : loanCharges) {
                if (loanCharge.isDueAtDisbursement() && loanCharge.getChargePaymentMode().isPaymentModeAccountTransfer()
                        && loanCharge.isChargePending()) {
                    disBuLoanCharges.put(loanCharge.getId(), loanCharge.amountOutstanding());
                }
            }
            final Locale locale = command.extractLocale();
            final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);
            for (final Map.Entry<Long, BigDecimal> entrySet : disBuLoanCharges.entrySet()) {
                final PortfolioAccountData savingAccountData = this.accountAssociationsReadPlatformService
                        .retriveLoanLinkedAssociation(loan.getId());
                final SavingsAccount fromSavingsAccount = null;
                final boolean isRegularTransaction = true;
                final boolean isExceptionForBalanceCheck = false;
                final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(actualDisbursementDate, entrySet.getValue(),
                        PortfolioAccountType.SAVINGS, PortfolioAccountType.LOAN, savingAccountData.getId(), loan.getId(),
                        "Loan Charge Payment", locale, fmt, null, null, LoanTransactionType.REPAYMENT_AT_DISBURSEMENT.getValue(),
                        entrySet.getKey(), null, AccountTransferType.CHARGE_PAYMENT.getValue(), null, null, ExternalId.empty(), null, null,
                        fromSavingsAccount, isRegularTransaction, isExceptionForBalanceCheck);
                this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
            }
            updateRecurringCalendarDatesForInterestRecalculation(loan);
            loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan,
                    loan.isInterestBearingAndInterestRecalculationEnabled(), true);
            loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());
            businessEventNotifierService.notifyPostBusinessEvent(new LoanDisbursalBusinessEvent(loan));
        }

        return changes;
    }

    @Transactional
    @Override
    public CommandProcessingResult undoGLIMLoanDisbursal(final Long loanId, final JsonCommand command) {
        final Long parentLoanId = loanId;
        GroupLoanIndividualMonitoringAccount parentLoan = glimRepository.findById(parentLoanId).orElseThrow();
        List<Loan> childLoans = this.loanRepository.findByGlimId(loanId);
        CommandProcessingResult result = null;
        int count = 0;
        for (Loan loan : childLoans) {
            result = undoLoanDisbursal(loan.getId(), command);
            if (result.getLoanId() != null) {
                count++;
                // if all the child loans are approved, mark the parent loan as
                // approved
                if (count == parentLoan.getChildAccountsCount()) {
                    parentLoan.setLoanStatus(LoanStatus.APPROVED.getValue());
                    glimRepository.save(parentLoan);
                }
            }
        }
        return result;
    }

    @Transactional
    @Override
    public CommandProcessingResult undoLoanDisbursal(final Long loanId, final JsonCommand command) {

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.charged.off",
                    "Undo Loan: " + loanId + " disbursement is not allowed. Loan Account is Charged-off", loanId);
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanUndoDisbursalBusinessEvent(loan));
        removeLoanCycle(loan);
        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();
        //
        final MonetaryCurrency currency = loan.getCurrency();

        final LocalDate recalculateFrom = null;
        loan.setActualDisbursementDate(null);
        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);

        // Remove post dated checks if added.
        loan.removePostDatedChecks();

        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_DISBURSAL_UNDO);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.LOAN_DISBURSAL_UNDO,
                loan.getDisbursementDate());
        final Map<String, Object> changes = undoDisbursal(loan, scheduleGeneratorDTO, existingTransactionIds,
                existingReversedTransactionIds);

        loanAccrualsProcessingService.reprocessExistingAccruals(loan);

        if (!changes.isEmpty()) {
            if (loan.isTopup() && loan.getClientId() != null) {
                final Long loanIdToClose = loan.getTopupLoanDetails().getLoanIdToClose();
                final LocalDate expectedDisbursementDate = command
                        .localDateValueOfParameterNamed(LoanApiConstants.expectedDisbursementDateParameterName);
                BigDecimal loanOutstanding = this.loanReadPlatformService
                        .retrieveLoanPrePaymentTemplate(LoanTransactionType.REPAYMENT, loanIdToClose, expectedDisbursementDate).getAmount();
                BigDecimal netDisbursalAmount = loan.getApprovedPrincipal().subtract(loanOutstanding);
                loan.adjustNetDisbursalAmount(netDisbursalAmount);
            }
            loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
            this.accountTransfersWritePlatformService.reverseAllTransactions(loanId, PortfolioAccountType.LOAN);
            createNote(loan, command, changes);
            boolean isAccountTransfer = false;
            final AccountingBridgeDataDTO accountingBridgeData = loanAccountingBridgeMapper.deriveAccountingBridgeData(currency.getCode(),
                    existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, loan);
            journalEntryWritePlatformService.createJournalEntriesForLoan(accountingBridgeData);
            loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanUndoDisbursalBusinessEvent(loan));
        }

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loan.getId()) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Transactional
    @Override
    @SuppressFBWarnings("SLF4J_SIGN_ONLY_FORMAT")
    public CommandProcessingResult makeGLIMLoanRepayment(final Long loanId, final JsonCommand command) {

        final Long parentLoanId = loanId;

        glimRepository.findById(parentLoanId).orElseThrow();

        JsonArray repayments = command.arrayOfParameterNamed("formDataArray");
        JsonCommand childCommand;
        CommandProcessingResult result = null;
        JsonObject jsonObject;

        Long[] childLoanId = new Long[repayments.size()];
        for (int i = 0; i < repayments.size(); i++) {
            jsonObject = repayments.get(i).getAsJsonObject();
            log.debug("{}", jsonObject.toString());
            childLoanId[i] = jsonObject.get("loanId").getAsLong();
        }
        int j = 0;
        for (JsonElement element : repayments) {
            childCommand = JsonCommand.fromExistingCommand(command, element);
            result = makeLoanRepayment(LoanTransactionType.REPAYMENT, childLoanId[j++], childCommand, false);
        }
        return result;
    }

    @Transactional
    @Override
    public CommandProcessingResult makeLoanRepayment(final LoanTransactionType repaymentTransactionType, final Long loanId,
            final JsonCommand command, final boolean isRecoveryRepayment) {
        final String chargeRefundChargeType = null;
        return makeLoanRepaymentWithChargeRefundChargeType(repaymentTransactionType, loanId, command, isRecoveryRepayment,
                chargeRefundChargeType);
    }

    private void recalculateLoanWithInterestPaymentWaiverTxn(Loan loan, LoanTransaction newInterestPaymentWaiverTransaction) {
        LocalDate recalculateFrom = null;
        LocalDate transactionDate = newInterestPaymentWaiverTransaction.getTransactionDate();
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            recalculateFrom = transactionDate;
        }
        final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom, null);

        newInterestPaymentWaiverTransaction.updateLoan(loan);

        final boolean isTransactionChronologicallyLatest = loanTransactionService.isChronologicallyLatestRepaymentOrWaiver(loan,
                newInterestPaymentWaiverTransaction);

        if (newInterestPaymentWaiverTransaction.isNotZero()) {
            loan.addLoanTransaction(newInterestPaymentWaiverTransaction);
        }

        final LoanRepaymentScheduleInstallment currentInstallment = loan.fetchLoanRepaymentScheduleInstallmentByDueDate(transactionDate);

        boolean reprocess = true;
        if (!loan.isForeclosure() && isTransactionChronologicallyLatest && DateUtils.isEqualBusinessDate(transactionDate)
                && currentInstallment != null && currentInstallment.getTotalOutstanding(loan.getCurrency())
                        .isEqualTo(newInterestPaymentWaiverTransaction.getAmount(loan.getCurrency()))) {
            reprocess = false;
        }

        if (isTransactionChronologicallyLatest && (!reprocess || !loan.isInterestBearingAndInterestRecalculationEnabled())
                && !loan.isForeclosure()) {
            loanTransactionProcessingService.processLatestTransaction(loan.getTransactionProcessingStrategyCode(),
                    newInterestPaymentWaiverTransaction, new TransactionCtx(loan.getCurrency(), loan.getRepaymentScheduleInstallments(),
                            loan.getActiveCharges(), new MoneyHolder(loan.getTotalOverpaidAsMoney()), null));
            reprocess = false;
            if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
                if (currentInstallment == null || currentInstallment.isNotFullyPaidOff()) {
                    reprocess = true;
                } else {
                    final LoanRepaymentScheduleInstallment nextInstallment = loan
                            .fetchRepaymentScheduleInstallment(currentInstallment.getInstallmentNumber() + 1);
                    if (nextInstallment != null && nextInstallment.getTotalPaidInAdvance(loan.getCurrency()).isGreaterThanZero()) {
                        reprocess = true;
                    }
                }
            }
        }
        if (reprocess) {
            reprocessChangedLoanTransactions(loan, scheduleGeneratorDTO);
        }

        loanLifecycleStateMachine.determineAndTransition(loan, newInterestPaymentWaiverTransaction.getTransactionDate());
    }

    private void reprocessChangedLoanTransactions(Loan loan, ScheduleGeneratorDTO scheduleGeneratorDTO) {
        if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
        } else if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                || loan.hasContractTerminationTransaction())) {
            loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        }

        reprocessLoanTransactionsService.reprocessTransactions(loan);

        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.reprocessExistingAccruals(loan);
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult makeInterestPaymentWaiver(final JsonCommand command) {
        loanTransactionValidator.validateLoanTransactionInterestPaymentWaiver(command);

        final Long loanId = command.getLoanId();
        Loan loan = this.loanAssembler.assembleFrom(loanId);

        businessEventNotifierService.notifyPreBusinessEvent(new LoanTransactionInterestPaymentWaiverPreBusinessEvent(loan));

        // save already existing transaction ids
        final List<Long> existingTransactionIds = new ArrayList<>(loanTransactionRepository.findTransactionIdsByLoan(loan));
        final List<Long> existingReversedTransactionIds = new ArrayList<>(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));

        final String noteText = command.stringValueOfParameterNamed("note");
        final Map<String, Object> changes = new LinkedHashMap<>();

        LoanTransaction newInterestPaymentWaiverTransaction = loanTransactionAssembler.assembleTransactionAndCalculateChanges(loan, command,
                changes);

        recalculateLoanWithInterestPaymentWaiverTxn(loan, newInterestPaymentWaiverTransaction);

        loanTransactionValidator.validateLoanTransactionInterestPaymentWaiverAfterRecalculation(loan);

        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(newInterestPaymentWaiverTransaction);

        loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        saveNote(noteText, loan, newInterestPaymentWaiverTransaction);

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);
        loanAccountDomainService.setLoanDelinquencyTag(loan, newInterestPaymentWaiverTransaction.getTransactionDate());

        // disable all active standing orders linked to this loan if status changes to closed
        loanAccountDomainService.disableStandingInstructionsLinkedToClosedLoan(loan);

        loanAccountDomainService.updateAndSavePostDatedChecksForIndividualAccount(loan, newInterestPaymentWaiverTransaction);
        loanAccountDomainService.updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, newInterestPaymentWaiverTransaction);

        // Finished Notification
        final LoanTransactionBusinessEvent transactionRepaymentEvent = new LoanTransactionInterestPaymentWaiverPostBusinessEvent(
                newInterestPaymentWaiverTransaction);

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(transactionRepaymentEvent);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()) //
                .withLoanId(loan.getId()) //
                .withEntityId(newInterestPaymentWaiverTransaction.getId()) //
                .withEntityExternalId(newInterestPaymentWaiverTransaction.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .with(changes) //
                .build();
    }

    private void saveNote(String noteText, Loan loan, LoanTransaction loanTransaction) {
        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanTransactionNote(loan, loanTransaction, noteText);
            this.noteRepository.save(note);
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult makeLoanRepaymentWithChargeRefundChargeType(final LoanTransactionType repaymentTransactionType,
            final Long loanId, final JsonCommand command, final boolean isRecoveryRepayment, final String chargeRefundChargeType) {

        this.loanUtilService.validateRepaymentTransactionType(repaymentTransactionType);
        this.loanTransactionValidator.validateNewRepaymentTransaction(command.json());

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
        final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("transactionAmount", command.stringValueOfParameterNamed("transactionAmount"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());
        changes.put("paymentTypeId", command.longValueOfParameterNamed("paymentTypeId"));

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
        }
        if (!txnExternalId.isEmpty()) {
            changes.put(LoanApiConstants.externalIdParameterName, txnExternalId);
        }
        Loan loan = this.loanAssembler.assembleFrom(loanId);
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);
        final Boolean isHolidayValidationDone = false;
        final HolidayDetailDTO holidayDetailDto = null;
        boolean isAccountTransfer = false;

        LoanTransaction loanTransaction = this.loanAccountDomainService.makeRepayment(repaymentTransactionType, loan, transactionDate,
                transactionAmount, paymentDetail, noteText, txnExternalId, isRecoveryRepayment, chargeRefundChargeType, isAccountTransfer,
                holidayDetailDto, isHolidayValidationDone);
        loan = loanTransaction.getLoan();
        this.loanAccountDomainService.updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, loanTransaction);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()) //
                .withLoanId(loan.getId()) //
                .withEntityId(loanTransaction.getId()) //
                .withEntityExternalId(loanTransaction.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .with(changes) //
                .build();
    }

    @Transactional
    @Override
    public Map<String, Object> makeLoanBulkRepayment(final CollectionSheetBulkRepaymentCommand bulkRepaymentCommand) {

        final SingleRepaymentCommand[] repaymentCommand = bulkRepaymentCommand.getLoanTransactions();
        final Map<String, Object> changes = new LinkedHashMap<>();
        final boolean isRecoveryRepayment = false;

        if (repaymentCommand == null) {
            return changes;
        }
        List<Long> transactionIds = new ArrayList<>();
        boolean isAccountTransfer = false;
        HolidayDetailDTO holidayDetailDTO = null;
        boolean isHolidayValidationDone = false;
        final boolean allowTransactionsOnHoliday = this.configurationDomainService.allowTransactionsOnHolidayEnabled();
        for (final SingleRepaymentCommand singleLoanRepaymentCommand : repaymentCommand) {
            if (singleLoanRepaymentCommand != null) {
                Loan loan = this.loanRepositoryWrapper.findOneWithNotFoundDetection(singleLoanRepaymentCommand.getLoanId());
                final List<Holiday> holidays = this.holidayRepository.findByOfficeIdAndGreaterThanDate(loan.getOfficeId(),
                        singleLoanRepaymentCommand.getTransactionDate());
                final WorkingDays workingDays = this.workingDaysRepository.findOne();
                final boolean allowTransactionsOnNonWorkingDay = this.configurationDomainService.allowTransactionsOnNonWorkingDayEnabled();
                boolean isHolidayEnabled;
                isHolidayEnabled = this.configurationDomainService.isRescheduleRepaymentsOnHolidaysEnabled();
                holidayDetailDTO = new HolidayDetailDTO(isHolidayEnabled, holidays, workingDays, allowTransactionsOnHoliday,
                        allowTransactionsOnNonWorkingDay);
                loanTransactionValidator.validateRepaymentDateIsOnHoliday(singleLoanRepaymentCommand.getTransactionDate(),
                        holidayDetailDTO.isAllowTransactionsOnHoliday(), holidayDetailDTO.getHolidays());
                loanTransactionValidator.validateRepaymentDateIsOnNonWorkingDay(singleLoanRepaymentCommand.getTransactionDate(),
                        holidayDetailDTO.getWorkingDays(), holidayDetailDTO.isAllowTransactionsOnNonWorkingDay());
                isHolidayValidationDone = true;
                break;
            }

        }
        for (final SingleRepaymentCommand singleLoanRepaymentCommand : repaymentCommand) {
            if (singleLoanRepaymentCommand != null) {
                final Loan loan = this.loanAssembler.assembleFrom(singleLoanRepaymentCommand.getLoanId());
                final PaymentDetail paymentDetail = singleLoanRepaymentCommand.getPaymentDetail();
                ExternalId externalId = singleLoanRepaymentCommand.getExternalId();
                if (externalId.isEmpty() && configurationDomainService.isExternalIdAutoGenerationEnabled()) {
                    externalId = ExternalId.generate();
                }
                if (paymentDetail != null && paymentDetail.getId() == null) {
                    this.paymentDetailWritePlatformService.persistPaymentDetail(paymentDetail);
                }
                final String chargeRefundChargeType = null;
                LoanTransaction loanTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT, loan,
                        bulkRepaymentCommand.getTransactionDate(), singleLoanRepaymentCommand.getTransactionAmount(), paymentDetail,
                        bulkRepaymentCommand.getNote(), externalId, isRecoveryRepayment, chargeRefundChargeType, isAccountTransfer,
                        holidayDetailDTO, isHolidayValidationDone);
                transactionIds.add(loanTransaction.getId());
            }
        }
        changes.put("loanTransactions", transactionIds);
        return changes;
    }

    @Transactional
    @Override
    public CommandProcessingResult adjustLoanTransaction(final Long loanId, final Long transactionId, final JsonCommand command) {

        this.loanTransactionValidator.validateTransaction(command.json());
        LoanTransaction transactionToAdjust = this.loanTransactionRepository.findByIdAndLoanId(command.entityId(), command.getLoanId())
                .orElseThrow(() -> new LoanTransactionNotFoundException(command.entityId(), command.getLoanId()));

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        if (loan.getStatus().isClosed() && loan.getLoanSubStatus() != null && loan.getLoanSubStatus().equals(LoanSubStatus.FORECLOSED)) {
            final String defaultUserMessage = "The loan cannot reopened as it is foreclosed.";
            throw new LoanForeclosureException("loan.cannot.be.reopened.as.it.is.foreclosured", defaultUserMessage, loanId);
        }
        checkClientOrGroupActive(loan);

        businessEventNotifierService.notifyPreBusinessEvent(
                new LoanAdjustTransactionBusinessEvent(new LoanAdjustTransactionBusinessEvent.Data(transactionToAdjust)));
        if (this.accountTransfersReadPlatformService.isAccountTransfer(transactionId, PortfolioAccountType.LOAN)) {
            throw new PlatformServiceUnavailableException("error.msg.loan.transfer.transaction.update.not.allowed",
                    "Loan transaction: " + transactionId + " update not allowed as it involves in account transfer", transactionId);
        }
        if (loan.isClosedWrittenOff()) {
            throw new PlatformServiceUnavailableException("error.msg.loan.written.off.update.not.allowed",
                    "Loan transaction: " + transactionId + " update not allowed as loan status is written off", transactionId);
        }

        if (transactionToAdjust.hasChargebackLoanTransactionRelations()) {
            throw new PlatformServiceUnavailableException("error.msg.loan.transaction.update.not.allowed",
                    "Loan transaction: " + transactionId + " update not allowed as loan transaction is linked to other transactions",
                    transactionId);
        }

        if (transactionToAdjust.isInterestRefund()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.transaction.update.not.allowed",
                    "Interest refund transaction: " + transactionId + " cannot be reversed or adjusted directly", transactionId);
        }

        Long commandId = command.commandId();
        final String noteText = command.stringValueOfParameterNamed("note");
        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
        final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        final boolean isAdjustCommand = (transactionAmount.compareTo(BigDecimal.ZERO) > 0);
        if (isAdjustCommand && !transactionToAdjust.isEditable()) {
            final String errorMessage = "Loan transaction: " + transactionId + " update not allowed as loan transaction is a "
                    + transactionToAdjust.getTypeOf().getCode();
            throw new InvalidLoanTransactionTypeException("transaction", "error.msg.loan.transaction.update.not.allowed", errorMessage);
        }

        // We dont need auto generation for reversal external id... if it is not provided, it remains null (empty)
        final String reversalExternalId = command.stringValueOfParameterNamedAllowingNull(LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME);
        final ExternalId reversalTxnExternalId = ExternalIdFactory.produce(reversalExternalId);

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("transactionAmount", command.stringValueOfParameterNamed("transactionAmount"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());
        changes.put("paymentTypeId", command.longValueOfParameterNamed("paymentTypeId"));

        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createPaymentDetail(command, changes);

        LoanAdjustmentParameter parameter = LoanAdjustmentParameter.builder().transactionAmount(transactionAmount)
                .paymentDetail(paymentDetail).transactionDate(transactionDate).txnExternalId(txnExternalId)
                .reversalTxnExternalId(reversalTxnExternalId).noteText(noteText).build();

        return loanAdjustmentService.adjustLoanTransaction(loan, transactionToAdjust, parameter, commandId, changes);
    }

    @Transactional
    @Override
    public CommandProcessingResult chargebackLoanTransaction(final Long loanId, final Long transactionId, final JsonCommand command) {
        this.loanTransactionValidator.validateChargebackTransaction(command.json());

        LoanTransaction loanTransaction = this.loanTransactionRepository.findByIdAndLoanId(command.entityId(), command.getLoanId())
                .orElseThrow(() -> new LoanTransactionNotFoundException(command.entityId(), command.getLoanId()));

        if (loanTransaction.isReversed()) {
            throw new PlatformServiceUnavailableException("error.msg.loan.chargeback.operation.not.allowed",
                    "Loan transaction:" + transactionId + " chargeback not allowed as loan transaction repayment is reversed",
                    transactionId);
        }

        if (!loanTransaction.isTypeAllowedForChargeback()) {
            throw new PlatformServiceUnavailableException(
                    "error.msg.loan.chargeback.operation.not.allowed", "Loan transaction:" + transactionId
                            + " chargeback not allowed for loan transaction type, its type is " + loanTransaction.getTypeOf().getCode(),
                    transactionId);
        }

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        if (this.accountTransfersReadPlatformService.isAccountTransfer(transactionId, PortfolioAccountType.LOAN)) {
            throw new PlatformServiceUnavailableException("error.msg.loan.transfer.transaction.update.not.allowed",
                    "Loan transaction:" + transactionId + " chargeback not allowed as it involves in account transfer", transactionId);
        }
        if (loan.isClosedWrittenOff()) {
            throw new PlatformServiceUnavailableException("error.msg.loan.chargeback.operation.not.allowed",
                    "Loan transaction:" + transactionId + " chargeback not allowed as loan status is written off", transactionId);
        }
        if (loan.isInterestBearingAndInterestRecalculationEnabled() && !loan.isProgressiveSchedule()) {
            throw new PlatformServiceUnavailableException("error.msg.loan.chargeback.operation.not.allowed",
                    "Loan transaction:" + transactionId + " chargeback not allowed as loan product is interest recalculation enabled",
                    transactionId);
        }
        checkClientOrGroupActive(loan);

        final List<Long> existingTransactionIds = loanTransactionRepository.findTransactionIdsByLoan(loan);
        final List<Long> existingReversedTransactionIds = loanTransactionRepository.findReversedTransactionIdsByLoan(loan);

        businessEventNotifierService.notifyPreBusinessEvent(new LoanChargebackTransactionBusinessEvent(loanTransaction));

        final LocalDate transactionDate = DateUtils.getBusinessLocalDate();
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(LoanApiConstants.TRANSACTION_AMOUNT_PARAMNAME);
        final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionAmount", command.stringValueOfParameterNamed(LoanApiConstants.TRANSACTION_AMOUNT_PARAMNAME));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());
        changes.put("paymentTypeId", command.longValueOfParameterNamed(LoanApiConstants.PAYMENT_TYPE_PARAMNAME));

        final Money transactionAmountAsMoney = Money.of(loan.getCurrency(), transactionAmount);
        PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createPaymentDetail(command, changes);
        if (paymentDetail != null) {
            paymentDetail = this.paymentDetailWritePlatformService.persistPaymentDetail(paymentDetail);
        }
        LoanTransaction newTransaction = LoanTransaction.chargeback(loan, transactionAmountAsMoney, paymentDetail, transactionDate,
                txnExternalId);

        validateLoanTransactionAmountChargeBack(loanTransaction, newTransaction);

        // Store the Loan Transaction Relation
        LoanTransactionRelation loanTransactionRelation = LoanTransactionRelation.linkToTransaction(loanTransaction, newTransaction,
                LoanTransactionRelationTypeEnum.CHARGEBACK);
        this.loanTransactionRelationRepository.save(loanTransactionRelation);

        handleChargebackTransaction(loan, newTransaction);

        newTransaction = this.loanTransactionRepository.saveAndFlush(newTransaction);

        loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        final String noteText = command.stringValueOfParameterNamed(LoanApiConstants.noteParamName);
        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
            Note note = Note.loanTransactionNote(loan, newTransaction, noteText);
            this.noteRepository.save(note);
        }

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        this.loanAccountDomainService.setLoanDelinquencyTag(loan, transactionDate);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanChargebackTransactionBusinessEvent(newTransaction));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(newTransaction.getId()) //
                .withEntityExternalId(newTransaction.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes).build();
    }

    private void validateLoanTransactionAmountChargeBack(LoanTransaction loanTransaction, LoanTransaction chargebackTransaction) {
        BigDecimal actualAmount = BigDecimal.ZERO;
        for (LoanTransactionRelation loanTransactionRelation : loanTransaction.getLoanTransactionRelations()) {
            if (loanTransactionRelation.getRelationType().equals(LoanTransactionRelationTypeEnum.CHARGEBACK)
                    && loanTransactionRelation.getToTransaction().isNotReversed()) {
                actualAmount = actualAmount.add(loanTransactionRelation.getToTransaction().getAmount());
            }
        }
        actualAmount = actualAmount.add(chargebackTransaction.getAmount());
        if (loanTransaction.getAmount() != null && actualAmount.compareTo(loanTransaction.getAmount()) > 0) {
            throw new PlatformServiceUnavailableException("error.msg.loan.chargeback.operation.not.allowed",
                    "Loan transaction:" + loanTransaction.getId() + " chargeback not allowed as loan transaction amount is not enough",
                    loanTransaction.getId());
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult waiveInterestOnLoan(final Long loanId, final JsonCommand command) {

        this.loanTransactionValidator.validateTransaction(command.json());

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("transactionAmount", command.stringValueOfParameterNamed("transactionAmount"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());
        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
        final ExternalId externalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);

        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();

        final Money transactionAmountAsMoney = Money.of(loan.getCurrency(), transactionAmount);
        Money unrecognizedIncome = transactionAmountAsMoney.zero();
        Money interestComponent = transactionAmountAsMoney;
        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            Money receivableInterest = loanBalanceService.getReceivableInterest(loan, transactionDate);
            if (transactionAmountAsMoney.isGreaterThan(receivableInterest)) {
                interestComponent = receivableInterest;
                unrecognizedIncome = transactionAmountAsMoney.minus(receivableInterest);
            }
        }
        final LoanTransaction waiveInterestTransaction = LoanTransaction.waiver(loan.getOffice(), loan, transactionAmountAsMoney,
                transactionDate, interestComponent, unrecognizedIncome, externalId);
        businessEventNotifierService.notifyPreBusinessEvent(new LoanWaiveInterestBusinessEvent(waiveInterestTransaction));
        LocalDate recalculateFrom = null;
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            recalculateFrom = transactionDate;
        }

        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_REPAYMENT_OR_WAIVER);
        loanTransactionValidator.validateActivityNotBeforeLastTransactionDate(loan, waiveInterestTransaction.getTransactionDate(),
                LoanEvent.LOAN_REPAYMENT_OR_WAIVER);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.LOAN_REPAYMENT_OR_WAIVER,
                waiveInterestTransaction.getTransactionDate());
        waiveInterest(loan, waiveInterestTransaction, existingTransactionIds, existingReversedTransactionIds, scheduleGeneratorDTO);

        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.reprocessExistingAccruals(loan);
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }

        this.loanTransactionRepository.saveAndFlush(waiveInterestTransaction);
        loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
            final Note note = Note.loanTransactionNote(loan, waiveInterestTransaction, noteText);
            this.noteRepository.save(note);
        }

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);
        loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanWaiveInterestBusinessEvent(waiveInterestTransaction));
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(waiveInterestTransaction.getId()) //
                .withEntityExternalId(waiveInterestTransaction.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes).build();
    }

    @Transactional
    @Override
    public CommandProcessingResult writeOff(final Long loanId, final JsonCommand command) {
        final AppUser currentUser = getAppUserIfPresent();

        this.loanTransactionValidator.validateTransactionWithNoAmount(command.json());

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());
        LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        if (command.hasParameter("writeoffReasonId")) {
            Long writeoffReasonId = command.longValueOfParameterNamed("writeoffReasonId");
            CodeValue writeoffReason = this.codeValueRepository
                    .findOneByCodeNameAndIdWithNotFoundDetection(LoanApiConstants.WRITEOFFREASONS, writeoffReasonId);
            changes.put("writeoffReasonId", writeoffReasonId);
            loan.updateWriteOffReason(writeoffReason);
        }

        checkClientOrGroupActive(loan);
        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loanId
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loanId);
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanWrittenOffPreBusinessEvent(loan));
        entityDatatableChecksWritePlatformService.runTheCheckForProduct(loanId, EntityTables.LOAN.getName(),
                StatusEnum.WRITE_OFF.getValue(), EntityTables.LOAN.getForeignKeyColumnNameOnDatatable(), loan.productId());

        removeLoanCycle(loan);

        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();

        updateLoanCounters(loan, loan.getDisbursementDate());

        LocalDate recalculateFrom = null;
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            recalculateFrom = command.localDateValueOfParameterNamed("transactionDate");
        }

        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
        final LocalDate writtenOffOnLocalDate = command.localDateValueOfParameterNamed(TRANSACTION_DATE);
        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.WRITE_OFF_OUTSTANDING);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.WRITE_OFF_OUTSTANDING,
                writtenOffOnLocalDate);

        CommandProcessingResultBuilder builder = new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes);

        final Optional<LoanTransaction> loanTransactionOptional = closeAsWrittenOff(loan, command, changes, existingTransactionIds,
                existingReversedTransactionIds, currentUser, scheduleGeneratorDTO);

        if (loanTransactionOptional.isPresent()) {
            final LoanTransaction loanTransaction = loanTransactionOptional.get();
            loanAccrualsProcessingService.reprocessExistingAccruals(loan);
            if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
                loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
            }
            this.loanTransactionRepository.saveAndFlush(loanTransaction);
            saveLoanWithDataIntegrityViolationChecks(loan);
            final String noteText = command.stringValueOfParameterNamed("note");
            if (StringUtils.isNotBlank(noteText)) {
                changes.put("note", noteText);
                final Note note = Note.loanTransactionNote(loan, loanTransaction, noteText);
                this.noteRepository.save(note);
            }
            loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan,
                    loan.isInterestBearingAndInterestRecalculationEnabled(), false);
            loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

            journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
            loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
            businessEventNotifierService.notifyPostBusinessEvent(new LoanWrittenOffPostBusinessEvent(loanTransaction));

            builder.withEntityId(loanTransaction.getId()).withEntityExternalId(loanTransaction.getExternalId());
        }

        return builder.build();
    }

    @Transactional
    @Override
    public CommandProcessingResult closeLoan(final Long loanId, final JsonCommand command) {
        this.loanTransactionValidator.validateTransactionWithNoAmount(command.json());

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loanId
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loanId);
        }

        businessEventNotifierService.notifyPreBusinessEvent(new LoanCloseBusinessEvent(loan));

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());

        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();

        updateLoanCounters(loan, loan.getDisbursementDate());

        LocalDate recalculateFrom = null;
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            recalculateFrom = command.localDateValueOfParameterNamed("transactionDate");
        }

        final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
        final LocalDate closureDate = command.localDateValueOfParameterNamed(TRANSACTION_DATE);
        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_CLOSED);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.REPAID_IN_FULL, closureDate);
        final Optional<LoanTransaction> loanTransactionOptional = close(loan, command, changes, existingTransactionIds,
                existingReversedTransactionIds, scheduleGeneratorDTO);

        loanAccrualsProcessingService.reprocessExistingAccruals(loan);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }

        loanTransactionOptional.ifPresent(this.loanTransactionRepository::saveAndFlush);
        saveLoanWithDataIntegrityViolationChecks(loan);

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
            final Note note = Note.loanNote(loan, noteText);
            this.noteRepository.save(note);
        }

        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);

        loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

        businessEventNotifierService.notifyPostBusinessEvent(new LoanCloseBusinessEvent(loan));

        // Update loan transaction on repayment.
        this.loanAccountDomainService.updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, null);

        // disable all active standing instructions linked to the loan
        this.loanAccountDomainService.disableStandingInstructionsLinkedToClosedLoan(loan);

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);

        CommandProcessingResult result;
        if (loanTransactionOptional.isPresent()) {
            result = new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(loanTransactionOptional.get().getId()) //
                    .withEntityExternalId(loanTransactionOptional.get().getExternalId()) //
                    .withOfficeId(loan.getOfficeId()) //
                    .withClientId(loan.getClientId()) //
                    .withGroupId(loan.getGroupId()) //
                    .withLoanId(loanId) //
                    .with(changes).build();
        } else {
            result = new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(loanId) //
                    .withEntityExternalId(loan.getExternalId()) //
                    .withOfficeId(loan.getOfficeId()) //
                    .withClientId(loan.getClientId()) //
                    .withGroupId(loan.getGroupId()) //
                    .withLoanId(loanId) //
                    .with(changes).build();
        }

        return result;
    }

    @Transactional
    @Override
    public CommandProcessingResult closeAsRescheduled(final Long loanId, final JsonCommand command) {

        this.loanTransactionValidator.validateTransactionWithNoAmount(command.json());

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.charged.off",
                    "Loan: " + loanId + " Close as rescheduled is not allowed. Loan Account is Charged-off", loanId);
        }
        removeLoanCycle(loan);
        businessEventNotifierService.notifyPreBusinessEvent(new LoanCloseAsRescheduleBusinessEvent(loan));

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());

        closeAsMarkedForReschedule(loan, command, changes);

        saveLoanWithDataIntegrityViolationChecks(loan);

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
            final Note note = Note.loanNote(loan, noteText);
            this.noteRepository.save(note);
        }
        businessEventNotifierService.notifyPostBusinessEvent(new LoanCloseAsRescheduleBusinessEvent(loan));

        // disable all active standing instructions linked to the loan
        this.loanAccountDomainService.disableStandingInstructionsLinkedToClosedLoan(loan);

        // Update loan transaction on repayment.
        this.loanAccountDomainService.updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, null);

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanId) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    private void disburseLoanToLoan(final Loan loan, final JsonCommand command, final BigDecimal amount) {
        final LocalDate transactionDate = command.localDateValueOfParameterNamed("actualDisbursementDate");
        final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);
        final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, amount, PortfolioAccountType.LOAN,
                PortfolioAccountType.LOAN, loan.getId(), loan.getTopupLoanDetails().getLoanIdToClose(), "Loan Topup", locale, fmt,
                LoanTransactionType.DISBURSEMENT.getValue(), LoanTransactionType.REPAYMENT.getValue(), txnExternalId, loan, null);
        AccountTransferDetails accountTransferDetails = this.accountTransfersWritePlatformService.repayLoanWithTopup(accountTransferDTO);
        loan.getTopupLoanDetails().setAccountTransferDetails(accountTransferDetails.getId());
        loan.getTopupLoanDetails().setTopupAmount(amount);
    }

    private void disburseLoanToSavings(final Loan loan, final JsonCommand command, final Money amount, final PaymentDetail paymentDetail) {
        final LocalDate transactionDate = command.localDateValueOfParameterNamed("actualDisbursementDate");
        final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);
        final PortfolioAccountData portfolioAccountData = this.accountAssociationsReadPlatformService
                .retriveLoanLinkedAssociation(loan.getId());
        if (portfolioAccountData == null) {
            final String errorMessage = "Disburse Loan with id:" + loan.getId() + " requires linked savings account for payment";
            throw new LinkedAccountRequiredException("loan.disburse.to.savings", errorMessage, loan.getId());
        }
        final SavingsAccount fromSavingsAccount = null;
        final boolean isExceptionForBalanceCheck = false;
        final boolean isRegularTransaction = true;
        final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, amount.getAmount(), PortfolioAccountType.LOAN,
                PortfolioAccountType.SAVINGS, loan.getId(), portfolioAccountData.getId(), "Loan Disbursement", locale, fmt, paymentDetail,
                LoanTransactionType.DISBURSEMENT.getValue(), null, null, null, AccountTransferType.ACCOUNT_TRANSFER.getValue(), null, null,
                txnExternalId, loan, null, fromSavingsAccount, isRegularTransaction, isExceptionForBalanceCheck);
        this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
    }

    @Transactional
    @Override
    public LoanTransaction initiateLoanTransfer(final Loan loan, final LocalDate transferDate) {
        checkClientOrGroupActive(loan);
        validateTransactionsForTransfer(loan, transferDate);

        businessEventNotifierService.notifyPreBusinessEvent(new LoanInitiateTransferBusinessEvent(loan));

        final List<Long> existingTransactionIds = new ArrayList<>(loanTransactionRepository.findTransactionIdsByLoan(loan));
        final List<Long> existingReversedTransactionIds = new ArrayList<>(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
        ExternalId externalId = externalIdFactory.create();
        final LoanTransaction newTransferTransaction = LoanTransaction.initiateTransfer(loan.getOffice(), loan, transferDate, externalId);
        loan.addLoanTransaction(newTransferTransaction);
        loanLifecycleStateMachine.transition(LoanEvent.LOAN_INITIATE_TRANSFER, loan);

        this.loanTransactionRepository.saveAndFlush(newTransferTransaction);
        saveLoanWithDataIntegrityViolationChecks(loan);

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanInitiateTransferBusinessEvent(loan));
        return newTransferTransaction;
    }

    @Transactional
    @Override
    public LoanTransaction acceptLoanTransfer(final Loan loan, final LocalDate transferDate, final Office acceptedInOffice,
            final Staff loanOfficer) {
        businessEventNotifierService.notifyPreBusinessEvent(new LoanAcceptTransferBusinessEvent(loan));
        final List<Long> existingTransactionIds = new ArrayList<>(loanTransactionRepository.findTransactionIdsByLoan(loan));
        final List<Long> existingReversedTransactionIds = new ArrayList<>(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
        ExternalId externalId = externalIdFactory.create();
        final LoanTransaction newTransferAcceptanceTransaction = LoanTransaction.approveTransfer(acceptedInOffice, loan, transferDate,
                externalId);
        loan.addLoanTransaction(newTransferAcceptanceTransaction);
        if (loan.getTotalOverpaid() != null) {
            loanLifecycleStateMachine.transition(LoanEvent.LOAN_OVERPAYMENT, loan);
        } else {
            loanLifecycleStateMachine.transition(LoanEvent.LOAN_REPAYMENT_OR_WAIVER, loan);
        }
        if (loanOfficer != null) {
            loanOfficerService.reassignLoanOfficer(loan, loanOfficer, transferDate);
        }

        this.loanTransactionRepository.saveAndFlush(newTransferAcceptanceTransaction);
        saveLoanWithDataIntegrityViolationChecks(loan);

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanAcceptTransferBusinessEvent(loan));

        return newTransferAcceptanceTransaction;
    }

    @Transactional
    @Override
    public LoanTransaction withdrawLoanTransfer(final Loan loan, final LocalDate transferDate) {
        businessEventNotifierService.notifyPreBusinessEvent(new LoanWithdrawTransferBusinessEvent(loan));

        final List<Long> existingTransactionIds = new ArrayList<>(loanTransactionRepository.findTransactionIdsByLoan(loan));
        final List<Long> existingReversedTransactionIds = new ArrayList<>(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));

        ExternalId externalId = externalIdFactory.create();

        final LoanTransaction newTransferAcceptanceTransaction = LoanTransaction.withdrawTransfer(loan.getOffice(), loan, transferDate,
                externalId);
        loan.addLoanTransaction(newTransferAcceptanceTransaction);
        loanLifecycleStateMachine.transition(LoanEvent.LOAN_WITHDRAW_TRANSFER, loan);

        this.loanTransactionRepository.saveAndFlush(newTransferAcceptanceTransaction);
        saveLoanWithDataIntegrityViolationChecks(loan);

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanWithdrawTransferBusinessEvent(loan));

        return newTransferAcceptanceTransaction;
    }

    @Transactional
    @Override
    public void rejectLoanTransfer(final Loan loan) {
        businessEventNotifierService.notifyPreBusinessEvent(new LoanRejectTransferBusinessEvent(loan));
        loanLifecycleStateMachine.transition(LoanEvent.LOAN_REJECT_TRANSFER, loan);
        saveLoanWithDataIntegrityViolationChecks(loan);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanRejectTransferBusinessEvent(loan));
    }

    @Transactional
    @Override
    public CommandProcessingResult loanReassignment(final Long loanId, final JsonCommand command) {

        this.loanTransactionValidator.validateUpdateOfLoanOfficer(command.json());

        final Long fromLoanOfficerId = command.longValueOfParameterNamed("fromLoanOfficerId");
        final Long toLoanOfficerId = command.longValueOfParameterNamed("toLoanOfficerId");

        final Staff fromLoanOfficer = this.loanAssembler.findLoanOfficerByIdIfProvided(fromLoanOfficerId);
        final Staff toLoanOfficer = this.loanAssembler.findLoanOfficerByIdIfProvided(toLoanOfficerId);
        final LocalDate dateOfLoanOfficerAssignment = command.localDateValueOfParameterNamed("assignmentDate");

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        businessEventNotifierService.notifyPreBusinessEvent(new LoanReassignOfficerBusinessEvent(loan));
        if (!loan.hasLoanOfficer(fromLoanOfficer)) {
            throw new LoanOfficerAssignmentException(loanId, fromLoanOfficerId);
        }

        loanOfficerService.reassignLoanOfficer(loan, toLoanOfficer, dateOfLoanOfficerAssignment);

        saveLoanWithDataIntegrityViolationChecks(loan);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanReassignOfficerBusinessEvent(loan));

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanId) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult bulkLoanReassignment(final JsonCommand command) {

        this.loanTransactionValidator.validateForBulkLoanReassignment(command.json());

        final Long fromLoanOfficerId = command.longValueOfParameterNamed("fromLoanOfficerId");
        final Long toLoanOfficerId = command.longValueOfParameterNamed("toLoanOfficerId");
        final String[] loanIds = command.arrayValueOfParameterNamed("loans");

        final LocalDate dateOfLoanOfficerAssignment = command.localDateValueOfParameterNamed("assignmentDate");

        final Staff fromLoanOfficer = this.loanAssembler.findLoanOfficerByIdIfProvided(fromLoanOfficerId);
        final Staff toLoanOfficer = this.loanAssembler.findLoanOfficerByIdIfProvided(toLoanOfficerId);
        List<Long> lockedLoanIds = new ArrayList<>();

        for (final String loanIdString : loanIds) {
            final Long loanId = Long.valueOf(loanIdString);
            final Loan loan = this.loanAssembler.assembleFrom(loanId);
            if (loanAccountLockService.isLoanHardLocked(loanId)) {
                lockedLoanIds.add(loanId);
            } else {
                businessEventNotifierService.notifyPreBusinessEvent(new LoanReassignOfficerBusinessEvent(loan));
                checkClientOrGroupActive(loan);

                if (!loan.hasLoanOfficer(fromLoanOfficer)) {
                    throw new LoanOfficerAssignmentException(loanId, fromLoanOfficerId);
                }

                loanOfficerService.reassignLoanOfficer(loan, toLoanOfficer, dateOfLoanOfficerAssignment);
                saveLoanWithDataIntegrityViolationChecks(loan);
                businessEventNotifierService.notifyPostBusinessEvent(new LoanReassignOfficerBusinessEvent(loan));
            }
        }
        if (!lockedLoanIds.isEmpty()) {
            throw new LoanAccountLockCannotBeOverruledException("There are hard-lcoked loan accounts: " + lockedLoanIds);
        }
        this.loanRepositoryWrapper.flush();

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult removeLoanOfficer(final Long loanId, final JsonCommand command) {

        final LoanUpdateCommand loanUpdateCommand = this.loanUpdateCommandFromApiJsonDeserializer.commandFromApiJson(command.json());

        loanUpdateCommand.validate();

        final LocalDate dateOfLoanOfficerUnassigned = command.localDateValueOfParameterNamed("unassignedDate");

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);

        if (loan.getLoanOfficer() == null) {
            throw new LoanOfficerUnassignmentException(loanId);
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanRemoveOfficerBusinessEvent(loan));

        loanOfficerValidator.validateUnassignDate(loan, dateOfLoanOfficerUnassigned);
        loan.removeLoanOfficer(dateOfLoanOfficerUnassigned);

        saveLoanWithDataIntegrityViolationChecks(loan);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanRemoveOfficerBusinessEvent(loan));

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanId) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .build();
    }

    @Transactional
    @Override
    public void applyMeetingDateChanges(final Calendar calendar, final Collection<CalendarInstance> loanCalendarInstances) {

        final Boolean rescheduleBasedOnMeetingDates = null;
        final LocalDate presentMeetingDate = null;
        final LocalDate newMeetingDate = null;

        applyMeetingDateChanges(calendar, loanCalendarInstances, rescheduleBasedOnMeetingDates, presentMeetingDate, newMeetingDate);

    }

    @Transactional
    @Override
    public void applyMeetingDateChanges(final Calendar calendar, final Collection<CalendarInstance> loanCalendarInstances,
            final Boolean rescheduleBasedOnMeetingDates, final LocalDate presentMeetingDate, final LocalDate newMeetingDate) {

        final boolean isHolidayEnabled = this.configurationDomainService.isRescheduleRepaymentsOnHolidaysEnabled();
        final WorkingDays workingDays = this.workingDaysRepository.findOne();
        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();
        final Collection<LoanStatus> loanStatuses = new ArrayList<>(
                Arrays.asList(LoanStatus.SUBMITTED_AND_PENDING_APPROVAL, LoanStatus.APPROVED, LoanStatus.ACTIVE));
        final Collection<AccountType> loanTypes = new ArrayList<>(Arrays.asList(AccountType.GROUP, AccountType.JLG));
        final Collection<Long> loanIds = new ArrayList<>(loanCalendarInstances.size());
        // loop through loanCalendarInstances to get loan ids
        for (final CalendarInstance calendarInstance : loanCalendarInstances) {
            loanIds.add(calendarInstance.getEntityId());
        }

        final List<Loan> loans = this.loanRepositoryWrapper.findByIdsAndLoanStatusAndLoanType(loanIds, loanStatuses, loanTypes);
        List<Holiday> holidays;
        final LocalDate recalculateFrom = null;
        // loop through each loan to reschedule the repayment dates
        for (final Loan loan : loans) {
            if (loan != null) {
                if (loan.getExpectedFirstRepaymentOnDate() != null && loan.getExpectedFirstRepaymentOnDate().equals(presentMeetingDate)) {
                    final String defaultUserMessage = "Meeting calendar date update is not supported since its a first repayment date";
                    throw new CalendarParameterUpdateNotSupportedException("meeting.for.first.repayment.date", defaultUserMessage,
                            loan.getExpectedFirstRepaymentOnDate(), presentMeetingDate);
                }

                if (loan.isChargedOff()) {
                    throw new GeneralPlatformDomainRuleException("error.msg.loan.is.charged.off",
                            "Loan: " + loan.getId() + " reschedule is not allowed. Loan Account is Charged-off", loan.getId());
                }

                Boolean isSkipRepaymentOnFirstMonth = false;
                int numberOfDays = 0;
                boolean isSkipRepaymentOnFirstMonthEnabled = configurationDomainService.isSkippingMeetingOnFirstDayOfMonthEnabled();
                if (isSkipRepaymentOnFirstMonthEnabled) {
                    isSkipRepaymentOnFirstMonth = this.loanUtilService.isLoanRepaymentsSyncWithMeeting(loan.group(), calendar);
                    if (isSkipRepaymentOnFirstMonth) {
                        numberOfDays = configurationDomainService.retreivePeriodInNumberOfDaysForSkipMeetingDate().intValue();
                    }
                }

                holidays = this.holidayRepository.findByOfficeIdAndGreaterThanDate(loan.getOfficeId(), loan.getDisbursementDate());
                if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
                    ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
                    loanScheduleService.recalculateScheduleFromLastTransaction(loan, scheduleGeneratorDTO, existingTransactionIds,
                            existingReversedTransactionIds);
                    createAndSaveLoanScheduleArchive(loan, scheduleGeneratorDTO);
                } else if (rescheduleBasedOnMeetingDates != null && rescheduleBasedOnMeetingDates) {
                    updateLoanRepaymentScheduleDates(loan, calendar.getRecurrence(), isHolidayEnabled, holidays, workingDays,
                            presentMeetingDate, newMeetingDate, isSkipRepaymentOnFirstMonth, numberOfDays);
                } else {
                    updateLoanRepaymentScheduleDates(loan, calendar.getStartDateLocalDate(), calendar.getRecurrence(), isHolidayEnabled,
                            holidays, workingDays, isSkipRepaymentOnFirstMonth, numberOfDays);
                }

                loanAccrualsProcessingService.reprocessExistingAccruals(loan);
                if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
                    loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
                }
                saveLoanWithDataIntegrityViolationChecks(loan);
                businessEventNotifierService.notifyPostBusinessEvent(new LoanRescheduledDueCalendarChangeBusinessEvent(loan));
                loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
            }
        }
    }

    private void removeLoanCycle(final Loan loan) {
        final List<Loan> loansToUpdate;
        if (loan.isGroupLoan()) {
            if (loan.loanProduct().isIncludeInBorrowerCycle()) {
                loansToUpdate = this.loanRepositoryWrapper.getGroupLoansToUpdateLoanCounter(loan.getCurrentLoanCounter(), loan.getGroupId(),
                        AccountType.GROUP);
            } else {
                loansToUpdate = this.loanRepositoryWrapper.getGroupLoansToUpdateLoanProductCounter(loan.getLoanProductLoanCounter(),
                        loan.getGroupId(), AccountType.GROUP);
            }

        } else {
            if (loan.loanProduct().isIncludeInBorrowerCycle()) {
                loansToUpdate = this.loanRepositoryWrapper.getClientOrJLGLoansToUpdateLoanCounter(loan.getCurrentLoanCounter(),
                        loan.getClientId());
            } else {
                loansToUpdate = this.loanRepositoryWrapper.getClientLoansToUpdateLoanProductCounter(loan.getLoanProductLoanCounter(),
                        loan.getClientId());
            }

        }
        if (loansToUpdate != null) {
            updateLoanCycleCounter(loansToUpdate, loan);
        }
        loan.updateClientLoanCounter(null);
        loan.updateLoanProductLoanCounter(null);

    }

    private void updateLoanCounters(final Loan loan, final LocalDate actualDisbursementDate) {

        if (loan.isGroupLoan()) {
            final List<Loan> loansToUpdateForLoanCounter = this.loanRepositoryWrapper.getGroupLoansDisbursedAfter(actualDisbursementDate,
                    loan.getGroupId(), AccountType.GROUP);
            final Integer newLoanCounter = getNewGroupLoanCounter(loan);
            final Integer newLoanProductCounter = getNewGroupLoanProductCounter(loan);
            updateLoanCounter(loan, loansToUpdateForLoanCounter, newLoanCounter, newLoanProductCounter);
        } else {
            final List<Loan> loansToUpdateForLoanCounter = this.loanRepositoryWrapper
                    .getClientOrJLGLoansDisbursedAfter(actualDisbursementDate, loan.getClientId());
            final Integer newLoanCounter = getNewClientOrJLGLoanCounter(loan);
            final Integer newLoanProductCounter = getNewClientOrJLGLoanProductCounter(loan);
            updateLoanCounter(loan, loansToUpdateForLoanCounter, newLoanCounter, newLoanProductCounter);
        }
    }

    private Integer getNewGroupLoanCounter(final Loan loan) {

        Integer maxClientLoanCounter = this.loanRepositoryWrapper.getMaxGroupLoanCounter(loan.getGroupId(), AccountType.GROUP);
        if (maxClientLoanCounter == null) {
            maxClientLoanCounter = 1;
        } else {
            maxClientLoanCounter = maxClientLoanCounter + 1;
        }
        return maxClientLoanCounter;
    }

    private Integer getNewGroupLoanProductCounter(final Loan loan) {

        Integer maxLoanProductLoanCounter = this.loanRepositoryWrapper.getMaxGroupLoanProductCounter(loan.loanProduct().getId(),
                loan.getGroupId(), AccountType.GROUP);
        if (maxLoanProductLoanCounter == null) {
            maxLoanProductLoanCounter = 1;
        } else {
            maxLoanProductLoanCounter = maxLoanProductLoanCounter + 1;
        }
        return maxLoanProductLoanCounter;
    }

    private void updateLoanCounter(final Loan loan, final List<Loan> loansToUpdateForLoanCounter, Integer newLoanCounter,
            Integer newLoanProductCounter) {

        final boolean includeInBorrowerCycle = loan.loanProduct().isIncludeInBorrowerCycle();
        for (final Loan loanToUpdate : loansToUpdateForLoanCounter) {
            // Update client loan counter if loan product includeInBorrowerCycle
            // is true
            if (loanToUpdate.loanProduct().isIncludeInBorrowerCycle()) {
                Integer currentLoanCounter = loanToUpdate.getCurrentLoanCounter() == null ? 1 : loanToUpdate.getCurrentLoanCounter();
                if (newLoanCounter > currentLoanCounter) {
                    newLoanCounter = currentLoanCounter;
                }
                loanToUpdate.updateClientLoanCounter(++currentLoanCounter);
            }

            if (Objects.equals(loan.loanProduct().getId(), loanToUpdate.loanProduct().getId())) {
                Integer loanProductLoanCounter = loanToUpdate.getLoanProductLoanCounter();
                if (newLoanProductCounter > loanProductLoanCounter) {
                    newLoanProductCounter = loanProductLoanCounter;
                }
                loanToUpdate.updateLoanProductLoanCounter(++loanProductLoanCounter);
            }
        }

        if (includeInBorrowerCycle) {
            loan.updateClientLoanCounter(newLoanCounter);
        } else {
            loan.updateClientLoanCounter(null);
        }
        loan.updateLoanProductLoanCounter(newLoanProductCounter);
        this.loanRepositoryWrapper.save(loansToUpdateForLoanCounter);
    }

    private Integer getNewClientOrJLGLoanCounter(final Loan loan) {

        Integer maxClientLoanCounter = this.loanRepositoryWrapper.getMaxClientOrJLGLoanCounter(loan.getClientId());
        if (maxClientLoanCounter == null) {
            maxClientLoanCounter = 1;
        } else {
            maxClientLoanCounter = maxClientLoanCounter + 1;
        }
        return maxClientLoanCounter;
    }

    private Integer getNewClientOrJLGLoanProductCounter(final Loan loan) {

        Integer maxLoanProductLoanCounter = this.loanRepositoryWrapper.getMaxClientOrJLGLoanProductCounter(loan.loanProduct().getId(),
                loan.getClientId());
        if (maxLoanProductLoanCounter == null) {
            maxLoanProductLoanCounter = 1;
        } else {
            maxLoanProductLoanCounter = maxLoanProductLoanCounter + 1;
        }
        return maxLoanProductLoanCounter;
    }

    private void updateLoanCycleCounter(final List<Loan> loansToUpdate, final Loan loan) {

        final Integer currentLoanCounter = loan.getCurrentLoanCounter();
        final Integer currentLoanProductCounter = loan.getLoanProductLoanCounter();

        for (final Loan loanToUpdate : loansToUpdate) {
            if (loan.loanProduct().isIncludeInBorrowerCycle()) {
                Integer runningLoanCounter = loanToUpdate.getCurrentLoanCounter();
                if (runningLoanCounter > currentLoanCounter) {
                    loanToUpdate.updateClientLoanCounter(--runningLoanCounter);
                }
            }
            if (Objects.equals(loan.loanProduct().getId(), loanToUpdate.loanProduct().getId())) {
                Integer runningLoanProductCounter = loanToUpdate.getLoanProductLoanCounter();
                if (runningLoanProductCounter > currentLoanProductCounter) {
                    loanToUpdate.updateLoanProductLoanCounter(--runningLoanProductCounter);
                }
            }
        }
        this.loanRepositoryWrapper.save(loansToUpdate);
    }

    private void checkClientOrGroupActive(final Loan loan) {
        final Client client = loan.client();
        if (client != null && client.isNotActive()) {
            throw new ClientNotActiveException(client.getId());
        }
        final Group group = loan.group();
        if (group != null && group.isNotActive()) {
            throw new GroupNotActiveException(group.getId());
        }
    }

    @Override
    public CommandProcessingResult undoWriteOff(Long loanId) {

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();
        if (!loan.isClosedWrittenOff()) {
            throw new PlatformServiceUnavailableException("error.msg.loan.status.not.written.off.update.not.allowed",
                    "Loan :" + loanId + " update not allowed as loan status is not written off", loanId);
        }
        LoanTransaction writeOffTransaction = loan.findWriteOffTransaction();
        if (writeOffTransaction == null) {
            throw new PlatformServiceUnavailableException("error.msg.loan.write.off.transaction.not.found",
                    "Loan :" + loanId + " write off transaction not found", loanId);
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanUndoWrittenOffBusinessEvent(writeOffTransaction));

        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.WRITE_OFF_OUTSTANDING_UNDO);
        undoWrittenOff(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualsProcessingService.reprocessExistingAccruals(loan);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }
        loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);

        this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanUndoWrittenOffBusinessEvent(writeOffTransaction));

        return new CommandProcessingResultBuilder() //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .withEntityId(writeOffTransaction.getId()) //
                .withEntityExternalId(writeOffTransaction.getExternalId()) //
                .build();
    }

    private void validateMultiDisbursementData(final JsonCommand command, LocalDate expectedDisbursementDate,
            boolean isDisallowExpectedDisbursements) {
        final String json = command.json();
        final JsonElement element = this.fromApiJsonHelper.parse(json);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan");
        final JsonArray disbursementDataArray = command.arrayOfParameterNamed(LoanApiConstants.disbursementDataParameterName);

        if (isDisallowExpectedDisbursements) {
            if (!disbursementDataArray.isEmpty()) {
                final String errorMessage = "For this loan product, disbursement details are not allowed";
                throw new MultiDisbursementDataNotAllowedException(LoanApiConstants.disbursementDataParameterName, errorMessage);
            }
        } else {
            if (disbursementDataArray == null || disbursementDataArray.isEmpty()) {
                final String errorMessage = "For this loan product, disbursement details must be provided";
                throw new MultiDisbursementDataRequiredException(LoanApiConstants.disbursementDataParameterName, errorMessage);
            }
        }

        final BigDecimal principal = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed("approvedLoanAmount", element);

        loanApplicationValidator.validateLoanMultiDisbursementDate(element, baseDataValidator, expectedDisbursementDate, principal);
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    private void validateForAddAndDeleteTranche(final Loan loan) {

        BigDecimal totalDisbursedAmount = BigDecimal.ZERO;
        Collection<LoanDisbursementDetails> loanDisburseDetails = loan.getDisbursementDetails();
        for (LoanDisbursementDetails disbursementDetails : loanDisburseDetails) {
            if (disbursementDetails.actualDisbursementDate() != null) {
                totalDisbursedAmount = totalDisbursedAmount.add(disbursementDetails.principal());
            }
        }
        if (totalDisbursedAmount.compareTo(loan.getApprovedPrincipal()) == 0) {
            final String errorMessage = "loan.disbursement.cannot.be.a.edited";
            throw new LoanMultiDisbursementException(errorMessage);
        }
    }

    @Override
    @Transactional
    public CommandProcessingResult addAndDeleteLoanDisburseDetails(Long loanId, JsonCommand command) {

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.charged.off",
                    "Update Loan: " + loanId + " disbursement details is not allowed. Loan Account is Charged-off", loanId);
        }
        final Map<String, Object> actualChanges = new LinkedHashMap<>();
        LocalDate expectedDisbursementDate = loan.getExpectedDisbursedOnLocalDate();
        if (!loan.loanProduct().isMultiDisburseLoan()) {
            final String errorMessage = "loan.product.does.not.support.multiple.disbursals";
            throw new LoanMultiDisbursementException(errorMessage);
        }
        if (loan.isSubmittedAndPendingApproval() || loan.isClosed() || loan.isClosedWrittenOff()
                || loan.getStatus().isClosedObligationsMet() || loan.getStatus().isOverpaid()) {
            final String errorMessage = "cannot.modify.tranches.if.loan.is.pendingapproval.closed.overpaid.writtenoff";
            throw new LoanMultiDisbursementException(errorMessage);
        }
        validateMultiDisbursementData(command, expectedDisbursementDate, loan.loanProduct().isDisallowExpectedDisbursements());

        this.validateForAddAndDeleteTranche(loan);

        loanDisbursementService.updateDisbursementDetails(loan, command, actualChanges);

        if (loan.loanProduct().isDisallowExpectedDisbursements()) {
            if (!loan.getDisbursementDetails().isEmpty()) {
                final String errorMessage = "For this loan product, disbursement details are not allowed";
                throw new MultiDisbursementDataNotAllowedException(LoanApiConstants.disbursementDataParameterName, errorMessage);
            }
        } else {
            if (loan.getDisbursementDetails().isEmpty()) {
                final String errorMessage = "For this loan product, disbursement details must be provided";
                throw new MultiDisbursementDataRequiredException(LoanApiConstants.disbursementDataParameterName, errorMessage);
            }
        }

        if (loan.getDisbursementDetails().size() > loan.loanProduct().maxTrancheCount()) {
            final String errorMessage = "Number of tranche shouldn't be greater than " + loan.loanProduct().maxTrancheCount();
            throw new ExceedingTrancheCountException(LoanApiConstants.disbursementDataParameterName, errorMessage,
                    loan.loanProduct().maxTrancheCount(), loan.getDisbursementDetails().size());
        }
        LoanDisbursementDetails updateDetails = null;
        CommandProcessingResult result = processLoanDisbursementDetail(loan, loanId, command, updateDetails);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanUpdateDisbursementDataBusinessEvent(loan));
        return result;

    }

    private CommandProcessingResult processLoanDisbursementDetail(Loan loan, Long loanId, JsonCommand command,
            LoanDisbursementDetails loanDisbursementDetails) {
        final List<Long> existingTransactionIds = loanTransactionRepository.findTransactionIdsByLoan(loan);
        final List<Long> existingReversedTransactionIds = loanTransactionRepository.findReversedTransactionIdsByLoan(loan);
        final Map<String, Object> changes = new LinkedHashMap<>();
        LocalDate recalculateFrom = null;
        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);

        if (command.entityId() != null) {
            loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_EDIT_MULTI_DISBURSE_DATE);
            updateDisbursementDateAndAmountForTranche(loan, loanDisbursementDetails, command, changes, scheduleGeneratorDTO);
        } else {
            loan.getLoanProductRelatedDetail().setPrincipal(loan.getPrincipalAmountForRepaymentSchedule());

            if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()) {
                loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
            } else if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                    || loan.hasContractTerminationTransaction())) {
                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
                reprocessLoanTransactionsService.processPostDisbursementTransactions(loan);
            }
        }

        loanAccrualsProcessingService.reprocessExistingAccruals(loan);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }

        loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            createLoanScheduleArchive(loan, scheduleGeneratorDTO);
        }
        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);
        this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);

        return new CommandProcessingResultBuilder() //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes).build();
    }

    @Override
    @Transactional
    public CommandProcessingResult updateDisbursementDateAndAmountForTranche(final Long loanId, final Long disbursementId,
            final JsonCommand command) {

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.charged.off",
                    "Update Loan: " + loanId + " disbursement details is not allowed. Loan Account is Charged-off", loanId);
        }
        LoanDisbursementDetails loanDisbursementDetails = loan.fetchLoanDisbursementsById(disbursementId);
        this.loanTransactionValidator.validateUpdateDisbursementDateAndAmount(command.json(), loanDisbursementDetails);

        CommandProcessingResult result = processLoanDisbursementDetail(loan, loanId, command, loanDisbursementDetails);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanUpdateDisbursementDataBusinessEvent(loan));
        return result;

    }

    @Transactional
    @Override
    @Retry(name = "recalculateInterest", fallbackMethod = "fallbackRecalculateInterest")
    public void recalculateInterest(final long loanId) {
        Loan loan = this.loanAssembler.assembleFrom(loanId);
        recalculateInterest(loan);
    }

    @Transactional
    @Override
    public Loan recalculateInterest(Loan loan) {
        businessEventNotifierService.notifyPreBusinessEvent(new LoanInterestRecalculationBusinessEvent(loan));
        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();

        if (loan.isCumulativeSchedule()) {
            LocalDate recalculateFrom = loan.fetchInterestRecalculateFromDate();
            ScheduleGeneratorDTO generatorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
            loanScheduleService.recalculateScheduleFromLastTransaction(loan, generatorDTO, existingTransactionIds,
                    existingReversedTransactionIds);
            loanAccrualsProcessingService.reprocessExistingAccruals(loan);
            if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
                loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
            }

            loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
            loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan,
                    loan.isInterestBearingAndInterestRecalculationEnabled(), false);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanInterestRecalculationBusinessEvent(loan));

            journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
            loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        } else {
            loanScheduleService.recalculateScheduleFromLastTransaction(loan, null, existingTransactionIds, existingReversedTransactionIds,
                    true);
            loanBalanceService.updateLoanSummaryDerivedFields(loan);
            loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanInterestRecalculationBusinessEvent(loan));
        }
        return loan;
    }

    @Override
    public CommandProcessingResult recoverFromGuarantor(final Long loanId) {
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        this.guarantorDomainService.transferFundsFromGuarantor(loan);
        return new CommandProcessingResultBuilder().withLoanId(loanId).withEntityId(loanId).withEntityExternalId(loan.getExternalId())
                .build();
    }

    @SuppressWarnings("unused")
    public void fallbackRecalculateInterest(Throwable t) {
        // NOTE: allow caller to catch the exceptions
        // NOTE: wrap throwable only if really necessary
        throw errorHandler.getMappable(t, null, null, "loan.recalculateinterest");
    }

    @Override
    public void updateOriginalSchedule(Loan loan) {
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            final LocalDate recalculateFrom = null;
            ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
            createLoanScheduleArchive(loan, scheduleGeneratorDTO);
        }
    }

    private void createLoanScheduleArchive(final Loan loan, final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        createAndSaveLoanScheduleArchive(loan, scheduleGeneratorDTO);

    }

    private void regenerateScheduleOnDisbursement(final JsonCommand command, final Loan loan, final boolean recalculateSchedule,
            final ScheduleGeneratorDTO scheduleGeneratorDTO, final LocalDate nextPossibleRepaymentDate,
            final LocalDate rescheduledRepaymentDate) {
        final LocalDate actualDisbursementDate = command.localDateValueOfParameterNamed("actualDisbursementDate");
        BigDecimal emiAmount = command.bigDecimalValueOfParameterNamed(LoanApiConstants.fixedEmiAmountParameterName);

        boolean isEmiAmountChanged = false;
        LoanProduct loanProduct = loan.getLoanProduct();
        if ((loanProduct.isMultiDisburseLoan() || loanProduct.isCanDefineInstallmentAmount()) && emiAmount != null
                && emiAmount.compareTo(loan.retriveLastEmiAmount()) != 0) {
            if (loanProduct.isMultiDisburseLoan()) {
                final LocalDate dateValue = null;
                final boolean isSpecificToInstallment = false;
                final Boolean isChangeEmiIfRepaymentDateSameAsDisbursementDateEnabled = scheduleGeneratorDTO
                        .isChangeEmiIfRepaymentDateSameAsDisbursementDateEnabled();
                LocalDate effectiveDateFrom = actualDisbursementDate;
                if (!isChangeEmiIfRepaymentDateSameAsDisbursementDateEnabled && actualDisbursementDate.equals(nextPossibleRepaymentDate)) {
                    effectiveDateFrom = nextPossibleRepaymentDate.plusDays(1);
                }
                LoanTermVariations loanVariationTerms = new LoanTermVariations(LoanTermVariationType.EMI_AMOUNT.getValue(),
                        effectiveDateFrom, emiAmount, dateValue, isSpecificToInstallment, loan, LoanStatus.ACTIVE.getValue());
                loan.getLoanTermVariations().add(loanVariationTerms);
            } else {
                loan.setFixedEmiAmount(emiAmount);
            }
            isEmiAmountChanged = true;
        }
        if (rescheduledRepaymentDate != null && loanProduct.isMultiDisburseLoan()) {
            final boolean isSpecificToInstallment = false;
            LoanTermVariations loanVariationTerms = new LoanTermVariations(LoanTermVariationType.DUE_DATE.getValue(),
                    nextPossibleRepaymentDate, emiAmount, rescheduledRepaymentDate, isSpecificToInstallment, loan,
                    LoanStatus.ACTIVE.getValue());
            loan.getLoanTermVariations().add(loanVariationTerms);
        }

        if (loan.isActualDisbursedOnDateEarlierOrLaterThanExpected(actualDisbursementDate) || recalculateSchedule || isEmiAmountChanged
                || rescheduledRepaymentDate != null) {
            if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()) {
                loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
            } else if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                    || loan.hasContractTerminationTransaction())) {
                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
            }
        }
        loanAccrualsProcessingService.reprocessExistingAccruals(loan);

        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }

    }

    private List<LoanRepaymentScheduleInstallment> retrieveRepaymentScheduleFromModel(LoanScheduleModel model) {
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();
        for (final LoanScheduleModelPeriod scheduledLoanInstallment : model.getPeriods()) {
            if (scheduledLoanInstallment.isRepaymentPeriod() || scheduledLoanInstallment.isDownPaymentPeriod()) {
                final LoanRepaymentScheduleInstallment installment = new LoanRepaymentScheduleInstallment(null,
                        scheduledLoanInstallment.periodNumber(), scheduledLoanInstallment.periodFromDate(),
                        scheduledLoanInstallment.periodDueDate(), scheduledLoanInstallment.principalDue(),
                        scheduledLoanInstallment.interestDue(), scheduledLoanInstallment.feeChargesDue(),
                        scheduledLoanInstallment.penaltyChargesDue(), scheduledLoanInstallment.isRecalculatedInterestComponent(),
                        scheduledLoanInstallment.getLoanCompoundingDetails());
                installments.add(installment);
            }
        }
        return installments;
    }

    @Override
    public CommandProcessingResult creditBalanceRefund(Long loanId, JsonCommand command) {
        this.loanTransactionValidator.validateNewRefundTransaction(command.json());

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
        final String noteText = command.stringValueOfParameterNamedAllowingNull("note");
        final ExternalId externalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("transactionAmount", command.stringValueOfParameterNamed("transactionAmount"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());

        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
        }
        if (!externalId.isEmpty()) {
            changes.put(LoanApiConstants.externalIdParameterName, externalId);
        }
        changes.put("paymentTypeId", command.longValueOfParameterNamed(LoanApiConstants.PAYMENT_TYPE_PARAMNAME));

        PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createPaymentDetail(command, changes);
        if (paymentDetail != null) {
            paymentDetail = this.paymentDetailWritePlatformService.persistPaymentDetail(paymentDetail);
        }

        final LoanTransaction loanTransaction = this.loanAccountDomainService.creditBalanceRefund(loan, transactionDate, transactionAmount,
                noteText, externalId, paymentDetail);

        return new CommandProcessingResultBuilder() //
                .withEntityId(loanTransaction.getId()) //
                .withEntityExternalId(loanTransaction.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withCommandId(command.commandId()) //
                .with(changes) //
                .build();

    }

    @Override
    @Transactional
    public CommandProcessingResult markLoanAsFraud(Long loanId, JsonCommand command) {
        this.loanTransactionValidator.validateMarkAsFraudLoan(command.json());

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        final Map<String, Object> changes = new LinkedHashMap<>();
        final boolean fraud = command.booleanPrimitiveValueOfParameterNamed(LoanApiConstants.FRAUD_ATTRIBUTE_NAME);
        if (loan.isFraud() != fraud) {
            loan.markAsFraud(fraud);
            this.loanRepository.save(loan);
            changes.put(LoanApiConstants.FRAUD_ATTRIBUTE_NAME, fraud);
        }

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loan.getId()) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Override
    @Transactional
    public CommandProcessingResult makeLoanRefund(Long loanId, JsonCommand command) {

        this.loanTransactionValidator.validateNewRefundTransaction(command.json());

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        ExternalId externalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        // checkRefundDateIsAfterAtLeastOneRepayment(loanId, transactionDate);

        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
        checkIfLoanIsPaidInAdvance(loanId, transactionAmount);

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("transactionAmount", command.stringValueOfParameterNamed("transactionAmount"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());
        changes.put(LoanApiConstants.externalIdParameterName, externalId);

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
        }

        final PaymentDetail paymentDetail = null;

        final CommandProcessingResultBuilder commandProcessingResultBuilder = new CommandProcessingResultBuilder();

        LoanTransaction loanTransaction = this.loanAccountDomainService.makeRefundForActiveLoan(loanId, commandProcessingResultBuilder,
                transactionDate, transactionAmount, paymentDetail, noteText, externalId);

        return commandProcessingResultBuilder //
                .withCommandId(command.commandId()) //
                .withLoanId(loanId) //
                .withEntityId(loanTransaction.getId()) //
                .withEntityExternalId(loanTransaction.getExternalId()) //
                .with(changes) //
                .build();

    }

    private void checkIfLoanIsPaidInAdvance(final Long loanId, final BigDecimal transactionAmount) {
        BigDecimal overpaid = this.loanReadPlatformService.retrieveTotalPaidInAdvance(loanId).getPaidInAdvance();

        if (overpaid == null || overpaid.compareTo(BigDecimal.ZERO) == 0 || transactionAmount.floatValue() > overpaid.floatValue()) {
            if (overpaid == null) {
                overpaid = BigDecimal.ZERO;
            }
            throw new InvalidPaidInAdvanceAmountException(overpaid.toPlainString());
        }
    }

    private AppUser getAppUserIfPresent() {
        AppUser user = null;
        if (this.context != null) {
            user = this.context.getAuthenticatedUserIfPresent();
        }
        return user;
    }

    @Override
    @Transactional
    public CommandProcessingResult undoLastLoanDisbursal(Long loanId, JsonCommand command) {

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        final LocalDate recalculateFromDate = loan.getLastRepaymentDate();
        validateIsMultiDisbursalLoanAndDisbursedMoreThanOneTranche(loan);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.charged.off",
                    "Undo Loan: " + loanId + " last disbursement is not allowed. Loan Account is Charged-off", loanId);
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanUndoLastDisbursalBusinessEvent(loan));

        final MonetaryCurrency currency = loan.getCurrency();
        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();

        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFromDate);

        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_DISBURSAL_UNDO_LAST);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.LOAN_DISBURSAL_UNDO_LAST,
                loan.getDisbursementDate());
        final Map<String, Object> changes = undoLastDisbursal(scheduleGeneratorDTO, existingTransactionIds, existingReversedTransactionIds,
                loan);

        loanAccrualsProcessingService.reprocessExistingAccruals(loan);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }

        if (!changes.isEmpty()) {
            loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
            createNote(loan, command, changes);
            boolean isAccountTransfer = false;
            final AccountingBridgeDataDTO accountingBridgeData = loanAccountingBridgeMapper.deriveAccountingBridgeData(currency.getCode(),
                    existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, loan);
            journalEntryWritePlatformService.createJournalEntriesForLoan(accountingBridgeData);
            loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanUndoLastDisbursalBusinessEvent(loan));
        }

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loan.getId()) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Override
    @Transactional
    public CommandProcessingResult forecloseLoan(final Long loanId, final JsonCommand command) {
        final String json = command.json();
        final JsonElement element = fromApiJsonHelper.parse(json);
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        final LocalDate transactionDate = this.fromApiJsonHelper.extractLocalDateNamed(LoanApiConstants.transactionDateParamName, element);
        final ExternalId externalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);
        this.loanTransactionValidator.validateLoanForeclosure(command.json());
        final Map<String, Object> changes = new LinkedHashMap<>();
        // Got changed to match with the rest of the APIs
        changes.put("dateFormat", command.dateFormat());
        changes.put("transactionDate", command.stringValueOfParameterNamed(LoanApiConstants.transactionDateParamName));
        changes.put("externalId", externalId);

        String noteText = this.fromApiJsonHelper.extractStringNamed(LoanApiConstants.noteParamName, element);
        LoanRescheduleRequest loanRescheduleRequest = null;
        for (LoanDisbursementDetails loanDisbursementDetails : loan.getDisbursementDetails()) {
            if (!DateUtils.isAfter(loanDisbursementDetails.expectedDisbursementDateAsLocalDate(), transactionDate)
                    && loanDisbursementDetails.actualDisbursementDate() == null) {
                final String defaultUserMessage = "The loan with undisbursed tranche before foreclosure cannot be foreclosed.";
                throw new LoanForeclosureException("loan.with.undisbursed.tranche.before.foreclosure.cannot.be.foreclosured",
                        defaultUserMessage, transactionDate);
            }
        }
        this.loanScheduleHistoryWritePlatformService.createAndSaveLoanScheduleArchive(loan.getRepaymentScheduleInstallments(), loan,
                loanRescheduleRequest);

        LoanTransaction foreclosureTransaction = this.loanAccountDomainService.foreCloseLoan(loan, transactionDate, noteText, externalId,
                changes);

        final CommandProcessingResultBuilder commandProcessingResultBuilder = new CommandProcessingResultBuilder();
        return commandProcessingResultBuilder //
                .withLoanId(loanId) //
                .withEntityId(foreclosureTransaction.getId()) //
                .withEntityExternalId(foreclosureTransaction.getExternalId()) //
                .with(changes) //
                .build();
    }

    @Override
    @Transactional
    public CommandProcessingResult chargeOff(JsonCommand command) {
        loanTransactionValidator.validateChargeOffTransaction(command.json());

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put(LoanApiConstants.transactionDateParamName,
                command.stringValueOfParameterNamed(LoanApiConstants.transactionDateParamName));
        changes.put(LoanApiConstants.localeParameterName, command.locale());
        changes.put(LoanApiConstants.dateFormatParameterName, command.dateFormat());
        final LocalDate transactionDate = command.localDateValueOfParameterNamed(LoanApiConstants.transactionDateParamName);
        final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);
        final AppUser currentUser = getAppUserIfPresent();

        Loan loan = loanAssembler.assembleFrom(command.getLoanId());
        final Long loanId = loan.getId();
        if (!loan.isOpen()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.not.active",
                    "Loan: " + loanId + " Charge-off is not allowed. Loan Account is not Active", loanId);
        }
        if (loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.already.charged.off",
                    "Loan: " + loanId + " is already charged-off", loanId);
        }
        if (DateUtils.isBefore(transactionDate, loan.getLastUserTransactionDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.off.is.before.than.the.last.user.transaction",
                    "Loan: " + loanId + " charge-off cannot be executed. User transaction was found after the charge-off transaction date!",
                    loanId);
        }
        if (DateUtils.isDateInTheFuture(transactionDate)) {
            final String errorMessage = "The transaction date cannot be in the future.";
            throw new GeneralPlatformDomainRuleException("error.msg.loan.transaction.cannot.be.a.future.date", errorMessage,
                    transactionDate);
        }

        if (loan.hasMonetaryActivityAfter(transactionDate)) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.monetary.transactions.after.charge.off",
                    "Loan: " + loanId + " charge-off cannot be executed. Loan has monetary activity after the charge-off transaction date!",
                    loanId);
        }

        businessEventNotifierService.notifyPreBusinessEvent(new LoanChargeOffPreBusinessEvent(loan));

        if (command.hasParameter(LoanApiConstants.chargeOffReasonIdParamName)) {
            Long chargeOffReasonId = command.longValueOfParameterNamed(LoanApiConstants.chargeOffReasonIdParamName);
            CodeValue chargeOffReason = this.codeValueRepository
                    .findOneByCodeNameAndIdWithNotFoundDetection(LoanApiConstants.CHARGE_OFF_REASONS, chargeOffReasonId);
            changes.put(LoanApiConstants.chargeOffReasonIdParamName, chargeOffReasonId);
            loan.markAsChargedOff(transactionDate, currentUser, chargeOffReason);
        } else {
            loan.markAsChargedOff(transactionDate, currentUser, null);
        }

        final List<Long> existingTransactionIds = loanTransactionRepository.findTransactionIdsByLoan(loan);
        final List<Long> existingReversedTransactionIds = loanTransactionRepository.findReversedTransactionIdsByLoan(loan);

        final LoanTransaction chargeOffTransaction = LoanTransaction.chargeOff(loan, transactionDate, txnExternalId);

        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            if (loan.isCumulativeSchedule()) {
                final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, null, null);
                loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
            }
            final List<LoanTransaction> loanTransactions = loanTransactionRepository.findNonReversedTransactionsForReprocessingByLoan(loan);
            loanTransactions.add(chargeOffTransaction);
            reprocessLoanTransactionsService.reprocessParticularTransactions(loan, loanTransactions);
            loan.addLoanTransaction(chargeOffTransaction);
        } else {
            reprocessLoanTransactionsService.processLatestTransaction(chargeOffTransaction, loan);
            loan.addLoanTransaction(chargeOffTransaction);
        }
        loanTransactionRepository.saveAndFlush(chargeOffTransaction);

        saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        String noteText = command.stringValueOfParameterNamed(LoanApiConstants.noteParameterName);
        if (StringUtils.isNotBlank(noteText)) {
            changes.put(LoanApiConstants.noteParameterName, noteText);
            final Note note = Note.loanTransactionNote(loan, chargeOffTransaction, noteText);
            this.noteRepository.save(note);
        }

        loan.getLoanTransactions().stream().filter(LoanTransaction::isAccrual)
                .filter(transaction -> loan.getLoanProductRelatedDetail().isInterestRecognitionOnDisbursementDate()
                        ? !DateUtils.isBefore(transaction.getTransactionDate(), transactionDate)
                        : DateUtils.isAfter(transaction.getTransactionDate(), transactionDate))
                .forEach(transaction -> {
                    transaction.reverse();
                    final LoanAdjustTransactionBusinessEvent.Data data = new LoanAdjustTransactionBusinessEvent.Data(transaction);
                    businessEventNotifierService.notifyPostBusinessEvent(new LoanAdjustTransactionBusinessEvent(data));
                });

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanChargeOffPostBusinessEvent(chargeOffTransaction));
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(chargeOffTransaction.getId()) //
                .withEntityExternalId(chargeOffTransaction.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(command.getLoanId()) //
                .with(changes).build();
    }

    @Override
    @Transactional
    public CommandProcessingResult undoChargeOff(JsonCommand command) {
        this.loanTransactionValidator.validateUndoChargeOff(command.json());
        final Long loanId = command.getLoanId();
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        final List<Long> existingTransactionIds = loanTransactionRepository.findTransactionIdsByLoan(loan);
        final List<Long> existingReversedTransactionIds = loanTransactionRepository.findReversedTransactionIdsByLoan(loan);
        checkClientOrGroupActive(loan);
        if (!loan.isOpen()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.not.active",
                    "Loan: " + loanId + " Undo Charge-off is not allowed. Loan Account is not Active", loanId);
        }
        if (!loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.not.charged.off", "Loan: " + loanId + " is not charged-off",
                    loanId);
        }
        LoanTransaction chargedOffTransaction = loan.findChargedOffTransaction();
        if (chargedOffTransaction == null) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.off.transaction.not.found",
                    "Loan: " + loanId + " charge-off transaction was not found", loanId);
        }
        if (!chargedOffTransaction.equals(loan.getLastUserTransaction())) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.off.is.not.the.last.user.transaction",
                    "Loan: " + loanId + " charge-off cannot be undone. User transaction was found after charge-off!", loanId);
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanUndoChargeOffBusinessEvent(chargedOffTransaction));

        // check if reversalExternalId is provided
        final String reversalExternalId = command.stringValueOfParameterNamedAllowingNull(LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME);
        final ExternalId reversalTxnExternalId = ExternalIdFactory.produce(reversalExternalId);

        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(chargedOffTransaction.getLoan(), chargedOffTransaction,
                "reversed");
        chargedOffTransaction.reverse(reversalTxnExternalId);
        chargedOffTransaction.manuallyAdjustedOrReversed();

        loan.liftChargeOff();
        loanTransactionRepository.saveAndFlush(chargedOffTransaction);

        final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, null, null);
        if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
        } else if (loan.isProgressiveSchedule()) {
            loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        }

        reprocessLoanTransactionsService.reprocessTransactions(loan);

        saveLoanWithDataIntegrityViolationChecks(loan);
        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanUndoChargeOffBusinessEvent(chargedOffTransaction));

        return new CommandProcessingResultBuilder() //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .withEntityId(chargedOffTransaction.getId()) //
                .withEntityExternalId(chargedOffTransaction.getExternalId()) //
                .build();
    }

    @Override
    public CommandProcessingResult makeRefund(final Long loanId, final LoanTransactionType loanTransactionType, final JsonCommand command) {
        // API rule validations
        this.loanTransactionValidator.validateRefund(command.json());

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");
        final ExternalId txnExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("transactionDate", command.stringValueOfParameterNamed("transactionDate"));
        changes.put("transactionAmount", command.stringValueOfParameterNamed("transactionAmount"));
        changes.put("locale", command.locale());
        changes.put("dateFormat", command.dateFormat());
        changes.put(LoanApiConstants.externalIdParameterName, txnExternalId);

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        // Build loan schedule generator dto
        LocalDate recalculateFrom = loan.isInterestBearingAndInterestRecalculationEnabled() ? transactionDate : null;
        final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom, null);
        // Domain rule validations
        this.loanTransactionValidator.validateRefund(loan, loanTransactionType, transactionDate, scheduleGeneratorDTO);
        // Create payment details
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);
        // Create note
        createNote(loan, command, changes);
        // Initial transaction ids for journal entry generation
        final List<Long> existingTransactionIds = loanTransactionRepository.findTransactionIdsByLoan(loan);
        final List<Long> existingReversedTransactionIds = loanTransactionRepository.findReversedTransactionIdsByLoan(loan);
        // Create refund transaction(s)
        Pair<LoanTransaction, LoanTransaction> refundTransactions = loanAccountDomainService.makeRefund(loan, scheduleGeneratorDTO,
                loanTransactionType, transactionDate, transactionAmount, paymentDetail, txnExternalId);
        LoanTransaction refundTransaction = refundTransactions.getLeft();
        LoanTransaction interestRefundTransaction = refundTransactions.getRight();
        // Accrual reprocessing
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.reprocessExistingAccruals(loan);
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }

        loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
        // Calculate and apply delinquency tag (if applicable)
        loanAccountDomainService.setLoanDelinquencyTag(loan, transactionDate);
        // disable all active standing orders linked to this loan if status
        // changes to closed
        loanAccountDomainService.disableStandingInstructionsLinkedToClosedLoan(loan);
        //// Mark Post Dated Check as paid., Do we need this for refund?
        loanAccountDomainService.updateAndSavePostDatedChecksForIndividualAccount(loan, refundTransaction);
        if (interestRefundTransaction != null) {
            loanAccountDomainService.updateAndSavePostDatedChecksForIndividualAccount(loan, refundTransaction);
        }
        // Collateral management
        loanAccountDomainService.updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, refundTransaction);
        if (interestRefundTransaction != null) {
            loanAccountDomainService.updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, refundTransaction);
        }
        // Raise business events
        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);

        // Create journal entries for the new transaction(s)
        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));

        Long entityId = refundTransaction.getId();
        ExternalId entityExternalId = refundTransaction.getExternalId();
        Long subEntityId = interestRefundTransaction != null ? interestRefundTransaction.getId() : null;
        ExternalId subEntityExternalId = interestRefundTransaction != null ? interestRefundTransaction.getExternalId() : null;
        return new CommandProcessingResultBuilder().withCommandId(command.commandId()) //
                .withLoanId(loan.getId()) //
                .withEntityId(entityId) //
                .withEntityExternalId(entityExternalId) //
                .withSubEntityId(subEntityId)//
                .withSubEntityExternalId(subEntityExternalId)//
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .with(changes) //
                .build();
    }

    public void handleChargebackTransaction(final Loan loan, LoanTransaction chargebackTransaction) {
        loanTransactionValidator.validateIfTransactionIsChargeback(chargebackTransaction);

        loan.addLoanTransaction(chargebackTransaction);
        if (loan.isInterestBearing() && loan.isInterestRecalculationEnabled()) {
            final List<LoanTransaction> transactions = loanTransactionService.retrieveListOfTransactionsForReprocessing(loan);
            loanTransactionProcessingService.reprocessLoanTransactions(loan.getTransactionProcessingStrategyCode(),
                    loan.getDisbursementDate(), transactions, loan.getCurrency(), loan.getRepaymentScheduleInstallments(),
                    loan.getActiveCharges());
        } else {
            loanTransactionProcessingService.processLatestTransaction(loan.getTransactionProcessingStrategyCode(), chargebackTransaction,
                    new TransactionCtx(loan.getCurrency(), loan.getRepaymentScheduleInstallments(), loan.getActiveCharges(),
                            new MoneyHolder(loan.getTotalOverpaidAsMoney()), null));
        }
        loanLifecycleStateMachine.determineAndTransition(loan, chargebackTransaction.getTransactionDate());
    }

    private void validateIsMultiDisbursalLoanAndDisbursedMoreThanOneTranche(Loan loan) {
        if (!loan.isMultiDisburmentLoan()) {
            final String errorMessage = "loan.product.does.not.support.multiple.disbursals.cannot.undo.last.disbursal";
            throw new LoanMultiDisbursementException(errorMessage);
        }
        int trancheDisbursedCount = 0;
        for (LoanDisbursementDetails disbursementDetails : loan.getDisbursementDetails()) {
            if (disbursementDetails.actualDisbursementDate() != null) {
                trancheDisbursedCount++;
            }
        }
        if (trancheDisbursedCount <= 1) {
            final String errorMessage = "tranches.should.be.disbursed.more.than.one.to.undo.last.disbursal";
            throw new LoanMultiDisbursementException(errorMessage);
        }
    }

    private void syncExpectedDateWithActualDisbursementDate(final Loan loan, LocalDate actualDisbursementDate) {
        if (!loan.getExpectedDisbursedOnLocalDate().equals(actualDisbursementDate)) {
            throw new DateMismatchException(actualDisbursementDate, loan.getExpectedDisbursedOnLocalDate());
        }
    }

    private void validateTransactionsForTransfer(final Loan loan, final LocalDate transferDate) {
        for (LoanTransaction transaction : loan.getLoanTransactions()) {
            if ((DateUtils.isEqual(transferDate, transaction.getTransactionDate())
                    && DateUtils.isEqual(transferDate, transaction.getSubmittedOnDate()))
                    || DateUtils.isBefore(transferDate, transaction.getTransactionDate())) {
                throw new GeneralPlatformDomainRuleException(TransferApiConstants.transferClientLoanException,
                        TransferApiConstants.transferClientLoanExceptionMessage, transaction.getTransactionDate(), transferDate);
            }
        }
    }

    private Map<String, Object> undoDisbursal(final Loan loan, final ScheduleGeneratorDTO scheduleGeneratorDTO,
            final List<Long> existingTransactionIds, final List<Long> existingReversedTransactionIds) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>();
        final LoanStatus currentStatus = loan.getStatus();
        final LoanStatus statusEnum = this.loanLifecycleStateMachine.dryTransition(LoanEvent.LOAN_DISBURSAL_UNDO, loan);
        existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
        existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
        if (!statusEnum.hasStateOf(currentStatus)) {
            this.loanLifecycleStateMachine.transition(LoanEvent.LOAN_DISBURSAL_UNDO, loan); // tis will update the loan
                                                                                            // status
            actualChanges.put(PARAM_STATUS, LoanEnumerations.status(loan.getLoanStatus()));

            final LocalDate actualDisbursementDate = loan.getDisbursementDate();
            final boolean isScheduleRegenerateRequired = loan.isActualDisbursedOnDateEarlierOrLaterThanExpected(actualDisbursementDate);
            loan.setActualDisbursementDate(null);
            loan.setDisbursedBy(null);
            loan.setLastClosedBusinessDate(null);
            final boolean isDisbursedAmountChanged = !MathUtil.isEqualTo(loan.getApprovedPrincipal(),
                    loan.getLoanRepaymentScheduleDetail().getPrincipal().getAmount());
            loan.getLoanRepaymentScheduleDetail().setPrincipal(loan.getApprovedPrincipal());
            // Remove All the Disbursement Details If the Loan Product is disabled and exists one
            if (loan.loanProduct().isDisallowExpectedDisbursements() && !loan.getDisbursementDetails().isEmpty()) {
                for (LoanDisbursementDetails disbursementDetail : loan.getAllDisbursementDetails()) {
                    disbursementDetail.reverse();
                }
            } else {
                for (final LoanDisbursementDetails details : loan.getDisbursementDetails()) {
                    details.updateActualDisbursementDate(null);
                }
            }
            final boolean isEmiAmountChanged = !loan.getLoanTermVariations().isEmpty();
            updateLoanToPreDisbursalState(loan);
            if (isScheduleRegenerateRequired || isDisbursedAmountChanged || isEmiAmountChanged
                    || loan.isInterestBearingAndInterestRecalculationEnabled()) {
                // clear off actual disbusrement date so schedule regeneration
                // uses expected date.

                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
                if (isDisbursedAmountChanged) {
                    loan.updateSummaryWithTotalFeeChargesDueAtDisbursement(loan.deriveSumTotalOfChargesDueAtDisbursement());
                }
            } else if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
                for (final LoanRepaymentScheduleInstallment period : loan.getRepaymentScheduleInstallments()) {
                    period.resetAccrualComponents();
                }
            }

            if (loan.isTopup()) {
                loan.getLoanTopupDetails().setAccountTransferDetails(null);
                loan.getLoanTopupDetails().setTopupAmount(null);
            }

            loan.adjustNetDisbursalAmount(loan.getApprovedPrincipal());
            actualChanges.put(ACTUAL_DISBURSEMENT_DATE, "");
            loanBalanceService.updateLoanSummaryDerivedFields(loan);
        }

        return actualChanges;
    }

    public void updateLoanToPreDisbursalState(final Loan loan) {
        loan.setActualDisbursementDate(null);
        loan.setDisbursedBy(null);
        loan.setAccruedTill(null);
        reverseExistingTransactions(loan);
        BigDecimal principalForScheduleRegeneration = loan.getApprovedPrincipal();

        for (final LoanCharge charge : loan.getActiveCharges()) {
            if (charge.isOverdueInstallmentCharge()) {
                charge.setActive(false);
            } else {
                charge.resetToOriginal(loan.loanCurrency());
            }
        }

        loan.getLoanRepaymentScheduleDetail().setPrincipal(principalForScheduleRegeneration);

        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            currentInstallment.resetDerivedComponents();
        }

        for (LoanTermVariations variations : loan.getLoanTermVariations()) {
            if (variations.getOnLoanStatus().equals(LoanStatus.ACTIVE.getValue())) {
                variations.markAsInactive();
            }
        }

        final LoanRepaymentScheduleProcessingWrapper wrapper = new LoanRepaymentScheduleProcessingWrapper();
        wrapper.reprocess(loan.getCurrency(), loan.getDisbursementDate(), loan.getRepaymentScheduleInstallments(), loan.getActiveCharges());

        loanBalanceService.refreshSummaryAndBalancesForDisbursedLoan(loan);
    }

    private void reverseExistingTransactions(final Loan loan) {
        final Collection<LoanTransaction> retainTransactions = new ArrayList<>();
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(transaction.getLoan(), transaction, "reversed");
            transaction.reverse();
            if (transaction.getId() != null) {
                retainTransactions.add(transaction);
            }
        }
        loan.getLoanTransactions().retainAll(retainTransactions);
    }

    private Optional<LoanTransaction> closeAsWrittenOff(final Loan loan, final JsonCommand command, final Map<String, Object> changes,
            final List<Long> existingTransactionIds, final List<Long> existingReversedTransactionIds, final AppUser currentUser,
            final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        closeDisbursements(loan, scheduleGeneratorDTO);

        final LocalDate writtenOffOnLocalDate = command.localDateValueOfParameterNamed(TRANSACTION_DATE);
        loan.setClosedOnDate(writtenOffOnLocalDate);
        loan.setWrittenOffOnDate(writtenOffOnLocalDate);
        loan.setClosedBy(currentUser);
        final LoanStatus statusEnum = loanLifecycleStateMachine.dryTransition(LoanEvent.WRITE_OFF_OUTSTANDING, loan);

        if (statusEnum.hasStateOf(loan.getStatus())) {
            return Optional.empty();
        }

        loanLifecycleStateMachine.transition(LoanEvent.WRITE_OFF_OUTSTANDING, loan);
        changes.put(PARAM_STATUS, LoanEnumerations.status(loan.getLoanStatus()));

        existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
        existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));

        final String txnExternalId = command.stringValueOfParameterNamedAllowingNull(EXTERNAL_ID);

        ExternalId externalId = ExternalIdFactory.produce(txnExternalId);

        if (externalId.isEmpty() && TemporaryConfigurationServiceContainer.isExternalIdAutoGenerationEnabled()) {
            externalId = ExternalId.generate();
        }

        changes.put(CLOSED_ON_DATE, command.stringValueOfParameterNamed(TRANSACTION_DATE));
        changes.put(WRITTEN_OFF_ON_DATE, command.stringValueOfParameterNamed(TRANSACTION_DATE));
        changes.put("externalId", externalId);

        if (DateUtils.isBefore(writtenOffOnLocalDate, loan.getDisbursementDate())) {
            final String errorMessage = "The date on which a loan is written off cannot be before the loan disbursement date: "
                    + loan.getDisbursementDate().toString();
            throw new InvalidLoanStateTransitionException("writeoff", "cannot.be.before.submittal.date", errorMessage,
                    writtenOffOnLocalDate, loan.getDisbursementDate());
        }

        if (DateUtils.isDateInTheFuture(writtenOffOnLocalDate)) {
            final String errorMessage = "The date on which a loan is written off cannot be in the future.";
            throw new InvalidLoanStateTransitionException("writeoff", "cannot.be.a.future.date", errorMessage, writtenOffOnLocalDate);
        }

        final LoanTransaction loanTransaction = LoanTransaction.writeoff(loan, loan.getOffice(), writtenOffOnLocalDate, externalId);
        LocalDate lastTransactionDate = loan.getLastUserTransactionDate();
        if (DateUtils.isAfter(lastTransactionDate, writtenOffOnLocalDate)) {
            final String errorMessage = "The date of the writeoff transaction must occur on or before previous transactions.";
            throw new InvalidLoanStateTransitionException("writeoff", "must.occur.on.or.after.other.transaction.dates", errorMessage,
                    writtenOffOnLocalDate);
        }

        loan.addLoanTransaction(loanTransaction);
        loanTransactionProcessingService.processLatestTransaction(loan.getTransactionProcessingStrategyCode(), loanTransaction,
                new TransactionCtx(loan.getCurrency(), loan.getRepaymentScheduleInstallments(), loan.getActiveCharges(),
                        new MoneyHolder(loan.getTotalOverpaidAsMoney()), null));

        loanBalanceService.updateLoanSummaryDerivedFields(loan);

        return Optional.of(loanTransaction);
    }

    private void closeDisbursements(final Loan loan, final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        if (loan.isDisbursementAllowed() && loan.atLeastOnceDisbursed()) {
            loan.getLoanRepaymentScheduleDetail().setPrincipal(loan.getDisbursedAmount());
            loan.removeDisbursementDetail();
            loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
            if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()) {
                loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
            } else if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                    || loan.hasContractTerminationTransaction())) {
                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
            }
            reprocessLoanTransactionsService.reprocessTransactions(loan);
            LocalDate lastLoanTransactionDate = loan.getLatestTransactionDate();
            loanLifecycleStateMachine.determineAndTransition(loan, lastLoanTransactionDate);
        }
    }

    private Optional<LoanTransaction> close(final Loan loan, final JsonCommand command, final Map<String, Object> changes,
            final List<Long> existingTransactionIds, final List<Long> existingReversedTransactionIds,
            final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
        existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));

        final LocalDate closureDate = command.localDateValueOfParameterNamed(TRANSACTION_DATE);
        final String txnExternalId = command.stringValueOfParameterNamedAllowingNull(EXTERNAL_ID);

        ExternalId externalId = ExternalIdFactory.produce(txnExternalId);
        if (externalId.isEmpty() && TemporaryConfigurationServiceContainer.isExternalIdAutoGenerationEnabled()) {
            externalId = ExternalId.generate();
        }

        loan.setClosedOnDate(closureDate);
        changes.put(CLOSED_ON_DATE, command.stringValueOfParameterNamed(TRANSACTION_DATE));

        if (DateUtils.isBefore(closureDate, loan.getDisbursementDate())) {
            final String errorMessage = "The date on which a loan is closed cannot be before the loan disbursement date: "
                    + loan.getDisbursementDate().toString();
            throw new InvalidLoanStateTransitionException("close", "cannot.be.before.submittal.date", errorMessage, closureDate,
                    loan.getDisbursementDate());
        }

        if (DateUtils.isDateInTheFuture(closureDate)) {
            final String errorMessage = "The date on which a loan is closed cannot be in the future.";
            throw new InvalidLoanStateTransitionException("close", "cannot.be.a.future.date", errorMessage, closureDate);
        }
        closeDisbursements(loan, scheduleGeneratorDTO);

        LoanTransaction loanTransaction = null;
        if (loan.isOpen()) {
            final Money totalOutstanding = loan.getSummary().getTotalOutstanding(loan.getCurrency());
            if (totalOutstanding.isGreaterThanZero() && loan.getInArrearsTolerance().isGreaterThanOrEqualTo(totalOutstanding)) {

                loan.setClosedOnDate(closureDate);
                final LoanStatus statusEnum = loanLifecycleStateMachine.dryTransition(LoanEvent.REPAID_IN_FULL, loan);
                if (!statusEnum.hasStateOf(loan.getStatus())) {
                    loanLifecycleStateMachine.transition(LoanEvent.REPAID_IN_FULL, loan);
                    changes.put(PARAM_STATUS, LoanEnumerations.status(loan.getLoanStatus()));
                }
                changes.put("externalId", externalId);
                loanTransaction = LoanTransaction.writeoff(loan, loan.getOffice(), closureDate, externalId);
                final boolean isLastTransaction = loanTransactionRepository.isChronologicallyLatest(loanTransaction.getTransactionDate(),
                        loan);
                if (!isLastTransaction) {
                    final String errorMessage = "The closing date of the loan must be on or after latest transaction date.";
                    throw new InvalidLoanStateTransitionException("close.loan", "must.occur.on.or.after.latest.transaction.date",
                            errorMessage, closureDate);
                }

                loan.addLoanTransaction(loanTransaction);
                loanTransactionProcessingService.processLatestTransaction(loan.getTransactionProcessingStrategyCode(), loanTransaction,
                        new TransactionCtx(loan.getCurrency(), loan.getRepaymentScheduleInstallments(), loan.getActiveCharges(),
                                new MoneyHolder(loan.getTotalOverpaidAsMoney()), null));

                loanBalanceService.updateLoanSummaryDerivedFields(loan);
            } else if (totalOutstanding.isGreaterThanZero()) {
                final String errorMessage = "A loan with money outstanding cannot be closed";
                throw new InvalidLoanStateTransitionException("close", "loan.has.money.outstanding", errorMessage,
                        totalOutstanding.toString());
            }
        }

        if (loanBalanceService.isOverPaid(loan)) {
            final Money totalLoanOverpayment = loanBalanceService.calculateTotalOverpayment(loan);
            if (totalLoanOverpayment.isGreaterThanZero() && loan.getInArrearsTolerance().isGreaterThanOrEqualTo(totalLoanOverpayment)) {
                // TODO - KW - technically should set somewhere that this loan
                // has 'overpaid' amount
                loan.setClosedOnDate(closureDate);
                final LoanStatus statusEnum = loanLifecycleStateMachine.dryTransition(LoanEvent.REPAID_IN_FULL, loan);
                if (!statusEnum.hasStateOf(loan.getStatus())) {
                    loanLifecycleStateMachine.transition(LoanEvent.REPAID_IN_FULL, loan);
                    changes.put(PARAM_STATUS, LoanEnumerations.status(loan.getLoanStatus()));
                }
            } else if (totalLoanOverpayment.isGreaterThanZero()) {
                final String errorMessage = "The loan is marked as 'Overpaid' and cannot be moved to 'Closed (obligations met).";
                throw new InvalidLoanStateTransitionException("close", "loan.is.overpaid", errorMessage, totalLoanOverpayment.toString());
            }
        }

        return Optional.ofNullable(loanTransaction);
    }

    private void updateDisbursementDateAndAmountForTranche(final Loan loan, final LoanDisbursementDetails disbursementDetails,
            final JsonCommand command, final Map<String, Object> actualChanges, final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        final Locale locale = command.extractLocale();
        final BigDecimal principal = command.bigDecimalValueOfParameterNamed(LoanApiConstants.updatedDisbursementPrincipalParameterName,
                locale);
        final LocalDate expectedDisbursementDate = command
                .localDateValueOfParameterNamed(LoanApiConstants.updatedDisbursementDateParameterName);
        disbursementDetails.updateExpectedDisbursementDateAndAmount(expectedDisbursementDate, principal);
        actualChanges.put(LoanApiConstants.expectedDisbursementDateParameterName,
                command.stringValueOfParameterNamed(LoanApiConstants.expectedDisbursementDateParameterName));
        actualChanges.put(LoanApiConstants.disbursementIdParameterName,
                command.stringValueOfParameterNamed(LoanApiConstants.disbursementIdParameterName));
        actualChanges.put(LoanApiConstants.disbursementPrincipalParameterName,
                command.bigDecimalValueOfParameterNamed(LoanApiConstants.disbursementPrincipalParameterName, locale));

        loan.getLoanRepaymentScheduleDetail().setPrincipal(loan.getPrincipalAmountForRepaymentSchedule());

        if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
        } else if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                || loan.hasContractTerminationTransaction())) {
            loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        }

        reprocessLoanTransactionsService.reprocessTransactions(loan);
    }

    private Map<String, Object> undoLastDisbursal(final ScheduleGeneratorDTO scheduleGeneratorDTO, final List<Long> existingTransactionIds,
            final List<Long> existingReversedTransactionIds, final Loan loan) {
        final Map<String, Object> actualChanges = new LinkedHashMap<>();
        List<LoanTransaction> loanTransactions = loan.retrieveListOfTransactionsByType(LoanTransactionType.DISBURSEMENT);
        loanTransactions.sort(Comparator.comparing(LoanTransaction::getId));
        final LoanTransaction lastDisbursalTransaction = loanTransactions.getLast();
        final LocalDate lastTransactionDate = lastDisbursalTransaction.getTransactionDate();

        existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
        existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));

        loanTransactions = loanTransactionRepository.findNonReversedMonetaryTransactionsByLoan(loan);
        Collections.reverse(loanTransactions);
        for (final LoanTransaction previousTransaction : loanTransactions) {
            if (DateUtils.isBefore(lastTransactionDate, previousTransaction.getTransactionDate())
                    && (previousTransaction.isRepaymentLikeType() || previousTransaction.isWaiver()
                            || previousTransaction.isChargePayment())) {
                throw new UndoLastTrancheDisbursementException(previousTransaction.getId());
            }
            if (previousTransaction.getId().compareTo(lastDisbursalTransaction.getId()) < 0) {
                break;
            }
        }
        final LoanDisbursementDetails disbursementDetail = loan.getDisbursementDetails(lastTransactionDate,
                lastDisbursalTransaction.getAmount());
        loanBalanceService.updateLoanToLastDisbursalState(loan, disbursementDetail);
        loan.getLoanTermVariations()
                .removeIf(loanTermVariations -> (loanTermVariations.getTermType().isDueDateVariation()
                        && DateUtils.isAfter(loanTermVariations.fetchDateValue(), lastTransactionDate))
                        || (loanTermVariations.getTermType().isEMIAmountVariation()
                                && DateUtils.isEqual(loanTermVariations.getTermApplicableFrom(), lastTransactionDate))
                        || DateUtils.isAfter(loanTermVariations.getTermApplicableFrom(), lastTransactionDate));
        reverseExistingTransactionsTillLastDisbursal(loan, lastDisbursalTransaction);
        loanScheduleService.recalculateSchedule(loan, scheduleGeneratorDTO);
        actualChanges.put("undolastdisbursal", "true");
        actualChanges.put("disbursedAmount", loan.getDisbursedAmount());
        loanLifecycleStateMachine.determineAndTransition(loan, loan.getLastUserTransactionDate());

        return actualChanges;
    }

    private void waiveInterest(final Loan loan, final LoanTransaction waiveInterestTransaction, final List<Long> existingTransactionIds,
            final List<Long> existingReversedTransactionIds, final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
        existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));

        loanDownPaymentHandlerService.handleRepaymentOrRecoveryOrWaiverTransaction(loan, waiveInterestTransaction, null,
                scheduleGeneratorDTO);
    }

    private void undoWrittenOff(final Loan loan, final List<Long> existingTransactionIds, final List<Long> existingReversedTransactionIds) {
        existingTransactionIds.addAll(loanTransactionRepository.findTransactionIdsByLoan(loan));
        existingReversedTransactionIds.addAll(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
        final LoanTransaction writeOffTransaction = loan.findWriteOffTransaction();
        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(writeOffTransaction.getLoan(), writeOffTransaction,
                "reversed");
        writeOffTransaction.reverse();
        loanLifecycleStateMachine.transition(LoanEvent.WRITE_OFF_OUTSTANDING_UNDO, loan);
        if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                || loan.hasContractTerminationTransaction())) {
            final ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, null);
            loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        }
        reprocessLoanTransactionsService.reprocessTransactions(loan);
    }

    /**
     * Reverse only disbursement, accruals, and repayments at disbursal transactions
     */
    public void reverseExistingTransactionsTillLastDisbursal(final Loan loan, final LoanTransaction lastDisbursalTransaction) {
        LoanCharge chargeToDeactivate = null;

        for (final LoanCharge charge : loan.getCharges()) {
            if (charge.isTrancheDisbursementCharge()) {
                if (charge.getTrancheDisbursementCharge() != null
                        && charge.getTrancheDisbursementCharge().getloanDisbursementDetails() != null) {

                    LocalDate expectedDate = charge.getTrancheDisbursementCharge().getloanDisbursementDetails().expectedDisbursementDate();
                    LocalDate actualDate = charge.getTrancheDisbursementCharge().getloanDisbursementDetails().actualDisbursementDate();

                    if ((actualDate != null && actualDate.equals(lastDisbursalTransaction.getTransactionDate()))
                            || (expectedDate != null && expectedDate.equals(lastDisbursalTransaction.getTransactionDate()))) {
                        chargeToDeactivate = charge;
                        break;
                    }
                }
            }
        }

        if (chargeToDeactivate != null) {
            chargeToDeactivate.resetPaidAmount(loan.getCurrency());
            chargeToDeactivate.setActive(false);
        }

        // Now reverse all relevant transactions
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (!DateUtils.isBefore(transaction.getTransactionDate(), lastDisbursalTransaction.getTransactionDate())
                    && transaction.getId().compareTo(lastDisbursalTransaction.getId()) >= 0
                    && transaction.isAllowTypeTransactionAtTheTimeOfLastUndo()) {
                loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(transaction.getLoan(), transaction, "reversed");
                transaction.reverse();
            }
        }

        loanBalanceService.updateLoanSummaryDerivedFields(loan);

        if (loan.isAutoRepaymentForDownPaymentEnabled()) {
            // identify down-payment amount for the transaction
            BigDecimal disbursedAmountPercentageForDownPayment = loan.getLoanRepaymentScheduleDetail()
                    .getDisbursedAmountPercentageForDownPayment();
            Money downPaymentMoney = Money.of(loan.getCurrency(),
                    MathUtil.percentageOf(lastDisbursalTransaction.getAmount(), disbursedAmountPercentageForDownPayment, 19));

            // find the latest matching down-payment transaction based on date, amount and transaction type
            Optional<LoanTransaction> downPaymentTransaction = loan.getLoanTransactions().stream()
                    .filter(tr -> tr.getTransactionDate().equals(lastDisbursalTransaction.getTransactionDate())
                            && tr.getTypeOf().isDownPayment() && tr.getAmount().compareTo(downPaymentMoney.getAmount()) == 0)
                    .max(Comparator.comparing(LoanTransaction::getId));

            // reverse the down-payment transaction
            downPaymentTransaction.ifPresent(tr -> {
                loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(tr.getLoan(), tr, "reversed");
                tr.reverse();
            });
        }
    }

    public void closeAsMarkedForReschedule(final Loan loan, final JsonCommand command, final Map<String, Object> changes) {
        final LocalDate rescheduledOn = command.localDateValueOfParameterNamed(TRANSACTION_DATE);

        loan.setClosedOnDate(rescheduledOn);
        final LoanStatus statusEnum = loanLifecycleStateMachine.dryTransition(LoanEvent.LOAN_RESCHEDULE, loan);
        if (!statusEnum.hasStateOf(loan.getStatus())) {
            loanLifecycleStateMachine.transition(LoanEvent.LOAN_RESCHEDULE, loan);
            changes.put(PARAM_STATUS, LoanEnumerations.status(loan.getLoanStatus()));
        }

        loan.setRescheduledOnDate(rescheduledOn);
        changes.put(CLOSED_ON_DATE, command.stringValueOfParameterNamed(TRANSACTION_DATE));
        changes.put("rescheduledOnDate", command.stringValueOfParameterNamed(TRANSACTION_DATE));

        loanTransactionValidator.validateLoanRescheduleDate(loan);
    }

    private boolean canDisburse(final Loan loan) {
        final LoanStatus statusEnum = this.loanLifecycleStateMachine.dryTransition(LoanEvent.LOAN_DISBURSED, loan);

        boolean isMultiTrancheDisburse = false;
        LoanStatus actualLoanStatus = loan.getStatus();
        if ((actualLoanStatus.isActive() || actualLoanStatus.isClosedObligationsMet() || actualLoanStatus.isOverpaid())
                && loan.isAllTranchesNotDisbursed()) {
            isMultiTrancheDisburse = true;
        }
        return !statusEnum.hasStateOf(actualLoanStatus) || isMultiTrancheDisburse;
    }

    private void updateLoanRepaymentScheduleDates(final Loan loan, final LocalDate meetingStartDate, final String recuringRule,
            final boolean isHolidayEnabled, final List<Holiday> holidays, final WorkingDays workingDays,
            final boolean isSkipRepaymentonfirstdayofmonth, final Integer numberofDays) {
        // first repayment's from date is same as disbursement date.
        LocalDate tmpFromDate = loan.getDisbursementDate();
        final PeriodFrequencyType repaymentPeriodFrequencyType = loan.getLoanRepaymentScheduleDetail().getRepaymentPeriodFrequencyType();
        final Integer loanRepaymentInterval = loan.getLoanRepaymentScheduleDetail().getRepayEvery();
        final String frequency = CalendarUtils.getMeetingFrequencyFromPeriodFrequencyType(repaymentPeriodFrequencyType);

        LocalDate newRepaymentDate;
        LocalDate latestRepaymentDate = null;
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        for (final LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment : installments) {
            LocalDate oldDueDate = loanRepaymentScheduleInstallment.getDueDate();

            // FIXME: AA this won't update repayment dates before current date.
            if (DateUtils.isAfter(oldDueDate, meetingStartDate) && DateUtils.isDateInTheFuture(oldDueDate)) {
                newRepaymentDate = CalendarUtils.getNewRepaymentMeetingDate(recuringRule, meetingStartDate, oldDueDate,
                        loanRepaymentInterval, frequency, workingDays, isSkipRepaymentonfirstdayofmonth, numberofDays);

                final LocalDate maxDateLimitForNewRepayment = getMaxDateLimitForNewRepayment(repaymentPeriodFrequencyType,
                        loanRepaymentInterval, tmpFromDate);

                if (DateUtils.isAfter(newRepaymentDate, maxDateLimitForNewRepayment)) {
                    newRepaymentDate = CalendarUtils.getNextRepaymentMeetingDate(recuringRule, meetingStartDate, tmpFromDate,
                            loanRepaymentInterval, frequency, workingDays, isSkipRepaymentonfirstdayofmonth, numberofDays);
                }

                if (isHolidayEnabled) {
                    newRepaymentDate = HolidayUtil.getRepaymentRescheduleDateToIfHoliday(newRepaymentDate, holidays);
                }
                if (DateUtils.isBefore(latestRepaymentDate, newRepaymentDate)) {
                    latestRepaymentDate = newRepaymentDate;
                }

                loanRepaymentScheduleInstallment.updateDueDate(newRepaymentDate);
                // reset from date to get actual daysInPeriod
                loanRepaymentScheduleInstallment.updateFromDate(tmpFromDate);
                tmpFromDate = newRepaymentDate;// update with new repayment date
            } else {
                tmpFromDate = oldDueDate;
            }
        }
        if (latestRepaymentDate != null) {
            loan.setExpectedMaturityDate(latestRepaymentDate);
        }
    }

    private LocalDate getMaxDateLimitForNewRepayment(final PeriodFrequencyType periodFrequencyType, final Integer loanRepaymentInterval,
            final LocalDate startDate) {
        LocalDate dueRepaymentPeriodDate = startDate;
        final int repaidEvery = 2 * loanRepaymentInterval;
        switch (periodFrequencyType) {
            case DAYS -> dueRepaymentPeriodDate = startDate.plusDays(repaidEvery);
            case WEEKS -> dueRepaymentPeriodDate = startDate.plusWeeks(repaidEvery);
            case MONTHS -> dueRepaymentPeriodDate = startDate.plusMonths(repaidEvery);
            case YEARS -> dueRepaymentPeriodDate = startDate.plusYears(repaidEvery);
            case INVALID, WHOLE_TERM -> {
            }
        }
        return dueRepaymentPeriodDate.minusDays(1);// get 2n-1 range date from startDate
    }

    private void updateLoanRepaymentScheduleDates(final Loan loan, final String recurringRule, final boolean isHolidayEnabled,
            final List<Holiday> holidays, final WorkingDays workingDays, final LocalDate presentMeetingDate, final LocalDate newMeetingDate,
            final boolean isSkipRepaymentOnFirstDayOfMonth, final Integer numberOfDays) {
        // first repayment's from date is same as disbursement date.
        // meetingStartDate is used as seedDate Capture the seedDate from user and use the seedDate as meetingStart date

        LocalDate tmpFromDate = loan.getDisbursementDate();
        final PeriodFrequencyType repaymentPeriodFrequencyType = loan.getLoanRepaymentScheduleDetail().getRepaymentPeriodFrequencyType();
        final Integer loanRepaymentInterval = loan.getLoanRepaymentScheduleDetail().getRepayEvery();
        final String frequency = CalendarUtils.getMeetingFrequencyFromPeriodFrequencyType(repaymentPeriodFrequencyType);

        LocalDate newRepaymentDate;
        boolean isFirstTime = true;
        LocalDate latestRepaymentDate = null;
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        for (final LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment : installments) {
            LocalDate oldDueDate = loanRepaymentScheduleInstallment.getDueDate();
            if (!DateUtils.isBefore(oldDueDate, presentMeetingDate)) {
                if (isFirstTime) {
                    isFirstTime = false;
                    newRepaymentDate = newMeetingDate;
                } else {
                    // tmpFromDate.plusDays(1) is done to make sure
                    // getNewRepaymentMeetingDate method returns next meeting
                    // date and not the same as tmpFromDate
                    newRepaymentDate = CalendarUtils.getNewRepaymentMeetingDate(recurringRule, tmpFromDate, tmpFromDate.plusDays(1),
                            loanRepaymentInterval, frequency, workingDays, isSkipRepaymentOnFirstDayOfMonth, numberOfDays);
                }

                if (isHolidayEnabled) {
                    newRepaymentDate = HolidayUtil.getRepaymentRescheduleDateToIfHoliday(newRepaymentDate, holidays);
                }
                if (DateUtils.isBefore(latestRepaymentDate, newRepaymentDate)) {
                    latestRepaymentDate = newRepaymentDate;
                }
                loanRepaymentScheduleInstallment.updateDueDate(newRepaymentDate);
                // reset from date to get actual daysInPeriod

                loanRepaymentScheduleInstallment.updateFromDate(tmpFromDate);

                tmpFromDate = newRepaymentDate;// update with new repayment date
            } else {
                tmpFromDate = oldDueDate;
            }
        }
        if (latestRepaymentDate != null) {
            loan.setExpectedMaturityDate(latestRepaymentDate);
        }
    }

}
