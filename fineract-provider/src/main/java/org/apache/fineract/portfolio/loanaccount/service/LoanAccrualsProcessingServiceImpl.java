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

import static org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper.fetchFirstNormalInstallmentNumber;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper.isAfterPeriod;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper.isBeforePeriod;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper.isInPeriod;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction.accrualAdjustment;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction.accrueTransaction;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.config.TaskExecutorConstant;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanAdjustTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanAccrualAdjustmentTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanAccrualTransactionCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.data.AccountingBridgeDataDTO;
import org.apache.fineract.portfolio.loanaccount.data.AccountingBridgeLoanTransactionDTO;
import org.apache.fineract.portfolio.loanaccount.data.AccrualBalances;
import org.apache.fineract.portfolio.loanaccount.data.AccrualChargeData;
import org.apache.fineract.portfolio.loanaccount.data.AccrualPeriodData;
import org.apache.fineract.portfolio.loanaccount.data.AccrualPeriodsData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInterestRecalcualtionAdditionalDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInterestRecalculationDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionComparator;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionToRepaymentScheduleMapping;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleGeneratorFactory;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanAccountingBridgeMapper;
import org.apache.fineract.portfolio.loanproduct.domain.InterestRecalculationCompoundingMethod;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoanAccrualsProcessingServiceImpl implements LoanAccrualsProcessingService {

    private static final Predicate<LoanTransaction> ACCRUAL_PREDICATE = t -> t.isNotReversed()
            && (t.isAccrual() || t.isAccrualAdjustment());

    private static final String ACCRUAL_ON_CHARGE_SUBMITTED_ON_DATE = "submitted-date";
    private final ExternalIdFactory externalIdFactory;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final ConfigurationDomainService configurationDomainService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final LoanAccrualTransactionBusinessEventService loanAccrualTransactionBusinessEventService;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final LoanTransactionRepository loanTransactionRepository;
    private final LoanScheduleGeneratorFactory loanScheduleFactory;

    @Qualifier(TaskExecutorConstant.CONFIGURABLE_TASK_EXECUTOR_BEAN_NAME)
    private final ThreadPoolTaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final LoanAccountingBridgeMapper loanAccountingBridgeMapper;
    private final LoanChargeService loanChargeService;
    private final LoanBalanceService loanBalanceService;

    /**
     * method adds accrual for batch job "Add Periodic Accrual Transactions" and add accruals api for Loan
     */
    @Override
    @Transactional
    public void addPeriodicAccruals(@NotNull LocalDate tillDate) throws JobExecutionException {
        List<Loan> loans = loanRepositoryWrapper.findLoansForPeriodicAccrual(AccountingRuleType.ACCRUAL_PERIODIC, tillDate,
                !isChargeOnDueDate());
        List<Throwable> errors = new ArrayList<>();
        for (Loan loan : loans) {
            try {
                addPeriodicAccruals(tillDate, loan);
            } catch (Exception e) {
                log.error("Failed to add accrual for loan {}", loan.getId(), e);
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
    }

    /**
     * method adds accrual for Loan COB business step
     */
    @Override
    @Transactional
    public void addPeriodicAccruals(@NotNull final LocalDate tillDate, @NotNull final Loan loan) {
        if (loan.isClosed() || loan.getStatus().isOverpaid()) {
            return;
        }
        final boolean chargeOnDueDate = isChargeOnDueDate();
        addAccruals(loan, tillDate, true, false, true, chargeOnDueDate);
    }

    /**
     * method adds accrual for batch job "Add Accrual Transactions"
     */
    @Override
    @Transactional
    public void addAccruals(@NotNull LocalDate tillDate) throws JobExecutionException {
        final boolean chargeOnDueDate = isChargeOnDueDate();
        List<Loan> loans = loanRepositoryWrapper.findLoansForAddAccrual(AccountingRuleType.ACCRUAL_PERIODIC, tillDate, !chargeOnDueDate);

        List<Future<?>> loanTasks = new ArrayList<>();

        FineractContext context = ThreadLocalContextUtil.getContext();

        loans.forEach(outerLoan -> {
            loanTasks.add(taskExecutor.submit(() -> {
                try {
                    ThreadLocalContextUtil.init(context);
                    transactionTemplate.executeWithoutResult(status -> {
                        Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(outerLoan.getId());
                        try {
                            log.debug("Adding accruals for loan '{}'", loan.getId());
                            addAccruals(loan, tillDate, false, false, true, chargeOnDueDate);
                            log.debug("Successfully processed loan: '{}' for accrual entries", loan.getId());
                        } catch (Exception e) {
                            log.error("Failed to add accrual for loan {}", loan.getId(), e);
                            throw new RuntimeException("Failed to add accrual for loan " + loan.getId(), e);
                        }
                    });
                } finally {
                    ThreadLocalContextUtil.reset();
                }
            }));
        });

        List<Throwable> errors = new ArrayList<>();
        for (Future<?> task : loanTasks) {
            try {
                task.get();
            } catch (Exception e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
    }

    /**
     * method updates accrual derived fields on installments and reverse the unprocessed transactions for loan
     * reschedule
     */
    @Override
    public void reprocessExistingAccruals(@NotNull Loan loan) {
        List<LoanTransaction> accrualTransactions = retrieveListOfAccrualTransactions(loan);
        if (!accrualTransactions.isEmpty()) {
            if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
                reprocessPeriodicAccruals(loan, accrualTransactions);
            } else if (loan.isNoneOrCashOrUpfrontAccrualAccountingEnabledOnLoanProduct()) {
                reprocessNonPeriodicAccruals(loan, accrualTransactions);
            }
        }
    }

    /**
     * method calculates accruals for loan with interest recalculation on loan schedule when interest is recalculated
     */
    @Override
    @Transactional
    public void processAccrualsOnInterestRecalculation(@NotNull Loan loan, boolean isInterestRecalculationEnabled, boolean addJournal) {
        if (isProgressiveAccrual(loan)) {
            return;
        }
        LocalDate accruedTill = loan.getAccruedTill();
        if (!isInterestRecalculationEnabled || accruedTill == null) {
            return;
        }
        try {
            final boolean chargeOnDueDate = isChargeOnDueDate();
            addAccruals(loan, accruedTill, true, false, addJournal, chargeOnDueDate);
        } catch (Exception e) {
            String globalisationMessageCode = "error.msg.accrual.exception";
            throw new GeneralPlatformDomainRuleException(globalisationMessageCode, e.getMessage(), e);
        }
    }

    @Transactional
    @Override
    public void addIncomePostingAndAccruals(Long loanId) throws LoanNotFoundException {
        if (loanId == null) {
            return;
        }
        Loan loan = this.loanRepositoryWrapper.findOneWithNotFoundDetection(loanId, true);
        if (isProgressiveAccrual(loan)) {
            return;
        }
        final List<Long> existingTransactionIds = new ArrayList<>(loanTransactionRepository.findTransactionIdsByLoan(loan));
        final List<Long> existingReversedTransactionIds = new ArrayList<>(loanTransactionRepository.findReversedTransactionIdsByLoan(loan));
        processIncomePostingAndAccruals(loan);
        this.loanRepositoryWrapper.saveAndFlush(loan);
        postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);
    }

    /**
     * method calculates accruals for loan with interest recalculation and compounding to be posted as income
     */
    @Override
    public void processIncomePostingAndAccruals(@NotNull Loan loan) {
        if (isProgressiveAccrual(loan)) {
            return;
        }
        LoanInterestRecalculationDetails recalculationDetails = loan.getLoanInterestRecalculationDetails();
        if (recalculationDetails == null || !recalculationDetails.isCompoundingToBePostedAsTransaction()) {
            return;
        }
        LocalDate lastCompoundingDate = loan.getDisbursementDate();
        List<LoanInterestRecalcualtionAdditionalDetails> compoundingDetails = extractInterestRecalculationAdditionalDetails(loan);
        List<LoanTransaction> incomeTransactions = retrieveListOfIncomePostingTransactions(loan);
        List<LoanTransaction> accrualTransactions = retrieveListOfAccrualTransactions(loan);
        for (LoanInterestRecalcualtionAdditionalDetails compoundingDetail : compoundingDetails) {
            if (!DateUtils.isBeforeBusinessDate(compoundingDetail.getEffectiveDate())) {
                break;
            }
            LoanTransaction incomeTransaction = getTransactionForDate(incomeTransactions, compoundingDetail.getEffectiveDate());
            LoanTransaction accrualTransaction = getTransactionForDate(accrualTransactions, compoundingDetail.getEffectiveDate());
            addUpdateIncomeAndAccrualTransaction(loan, compoundingDetail, lastCompoundingDate, incomeTransaction, accrualTransaction);
            lastCompoundingDate = compoundingDetail.getEffectiveDate();
        }
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        LoanRepaymentScheduleInstallment lastInstallment = LoanRepaymentScheduleInstallment.getLastNonDownPaymentInstallment(installments);
        reverseTransactionsAfter(incomeTransactions, lastInstallment.getDueDate(), false);
        reverseTransactionsAfter(accrualTransactions, lastInstallment.getDueDate(), false);
    }

    /**
     * method calculates accruals for loan on loan closure
     */
    @Override
    public void processAccrualsOnLoanClosure(@NonNull final Loan loan, final boolean addJournal) {
        // check and process accruals for loan WITHOUT interest recalculation details and compounding posted as income
        final boolean chargeOnDueDate = isChargeOnDueDate();
        addAccruals(loan, loan.getLastLoanRepaymentScheduleInstallment().getDueDate(), false, true, addJournal, chargeOnDueDate);
        if (isProgressiveAccrual(loan)) {
            return;
        }
        // check and process accruals for loan WITH interest recalculation details and compounding posted as income
        processIncomeAndAccrualTransactionOnLoanClosure(loan);
    }

    /**
     * method calculates accruals for loan on loan fore closure
     */
    @Override
    public void processAccrualsOnLoanForeClosure(@NotNull Loan loan, @NotNull LocalDate foreClosureDate,
            @NotNull List<LoanTransaction> newAccrualTransactions) {
        // TODO implement progressive accrual case
        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()
                && (loan.getAccruedTill() == null || !DateUtils.isEqual(foreClosureDate, loan.getAccruedTill()))) {
            final LoanRepaymentScheduleInstallment foreCloseDetail = loanBalanceService.fetchLoanForeclosureDetail(loan, foreClosureDate);
            MonetaryCurrency currency = loan.getCurrency();
            reverseTransactionsAfter(retrieveListOfAccrualTransactions(loan), foreClosureDate, false);

            HashMap<String, Object> incomeDetails = new HashMap<>();

            determineReceivableIncomeForeClosure(loan, foreClosureDate, incomeDetails);

            Money interestPortion = foreCloseDetail.getInterestCharged(currency).minus((Money) incomeDetails.get(Loan.INTEREST));
            Money feePortion = foreCloseDetail.getFeeChargesCharged(currency).minus((Money) incomeDetails.get(Loan.FEE));
            Money penaltyPortion = foreCloseDetail.getPenaltyChargesCharged(currency).minus((Money) incomeDetails.get(Loan.PENALTIES));
            Money total = interestPortion.plus(feePortion).plus(penaltyPortion);

            if (total.isGreaterThanZero()) {
                createAccrualTransactionAndUpdateChargesPaidBy(loan, foreClosureDate, newAccrualTransactions, currency, interestPortion,
                        feePortion, penaltyPortion, total);
            }
        }
    }

    // PeriodicAccruals

    private void addAccruals(@NotNull final Loan loan, @NotNull LocalDate tillDate, final boolean periodic, final boolean isFinal,
            final boolean addJournal, final boolean chargeOnDueDate) {
        if ((!isFinal && !loan.isOpen()) || loan.isNpa() || loan.isChargedOff()
                || !loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            return;
        }

        final LoanInterestRecalculationDetails recalculationDetails = loan.getLoanInterestRecalculationDetails();
        if (recalculationDetails != null && recalculationDetails.isCompoundingToBePostedAsTransaction()) {
            return;
        }
        final List<LoanTransaction> existingAccruals = retrieveListOfAccrualTransactions(loan);
        final LocalDate lastDueDate = loan.getLastLoanRepaymentScheduleInstallment().getDueDate();
        reverseTransactionsAfter(existingAccruals, lastDueDate, addJournal);
        ensureAccrualTransactionMappings(loan, existingAccruals, chargeOnDueDate);
        if (DateUtils.isAfter(tillDate, lastDueDate)) {
            tillDate = lastDueDate;
        }

        final boolean progressiveAccrual = isProgressiveAccrual(loan);
        final LocalDate accruedTill = loan.getAccruedTill();
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        final LocalDate accrualDate = isFinal
                ? (progressiveAccrual ? (DateUtils.isBefore(lastDueDate, businessDate) ? lastDueDate : businessDate)
                        : getFinalAccrualTransactionDate(loan))
                : tillDate;
        if (progressiveAccrual && accruedTill != null && !DateUtils.isAfter(tillDate, accruedTill)) {
            if (isFinal) {
                reverseTransactionsAfter(existingAccruals, accrualDate, addJournal);
            } else if (isExistAccrualAfterDate(existingAccruals, accrualDate) && hasNoActiveChargeOnDate(loan, accrualDate)) {
                return;
            }
        }

        final AccrualPeriodsData accrualPeriods = calculateAccrualAmounts(loan, tillDate, periodic, isFinal, chargeOnDueDate);
        final boolean mergeTransactions = isFinal || progressiveAccrual;
        final MonetaryCurrency currency = loan.getLoanProductRelatedDetail().getCurrency();
        List<LoanTransaction> accrualTransactions = new ArrayList<>();
        Money totalInterestPortion = null;
        LoanTransaction mergeAccrualTransaction = null;
        LoanTransaction mergeAdjustTransaction = null;
        for (AccrualPeriodData period : accrualPeriods.getPeriods()) {
            final Money interestAccruable = MathUtil.nullToZero(period.getInterestAccruable(), currency);
            final Money interestPortion = MathUtil.minus(interestAccruable, period.getInterestAccrued());
            final Money feeAccruable = MathUtil.nullToZero(period.getFeeAccruable(), currency);
            final Money feePortion = MathUtil.minus(feeAccruable, period.getFeeAccrued());
            final Money penaltyAccruable = MathUtil.nullToZero(period.getPenaltyAccruable(), currency);
            final Money penaltyPortion = MathUtil.minus(penaltyAccruable, period.getPenaltyAccrued());
            if (MathUtil.isEmpty(interestPortion) && MathUtil.isEmpty(feePortion) && MathUtil.isEmpty(penaltyPortion)) {
                continue;
            }
            if (mergeTransactions) {
                totalInterestPortion = MathUtil.plus(totalInterestPortion, interestPortion);
                if (progressiveAccrual) {
                    final Money feeAdjustmentPortion = MathUtil.negate(feePortion);
                    final Money penaltyAdjustmentPortion = MathUtil.negate(penaltyPortion);
                    mergeAdjustTransaction = createOrMergeAccrualTransaction(loan, mergeAdjustTransaction, accrualDate, period,
                            accrualTransactions, null, feeAdjustmentPortion, penaltyAdjustmentPortion, true);
                }
                mergeAccrualTransaction = createOrMergeAccrualTransaction(loan, mergeAccrualTransaction, accrualDate, period,
                        accrualTransactions, null, feePortion, penaltyPortion, false);
            } else {
                final LocalDate dueDate = period.getDueDate();
                if (!isFinal && DateUtils.isAfter(dueDate, tillDate) && DateUtils.isBefore(tillDate, accruedTill)) {
                    continue;
                }
                final LocalDate periodAccrualDate = DateUtils.isBefore(dueDate, accrualDate) ? dueDate : accrualDate;
                final LoanTransaction accrualTransaction = addAccrualTransaction(loan, periodAccrualDate, period, interestPortion,
                        feePortion, penaltyPortion, false);
                if (accrualTransaction != null) {
                    accrualTransactions.add(accrualTransaction);
                }
            }
            final LoanRepaymentScheduleInstallment installment = loan.fetchRepaymentScheduleInstallment(period.getInstallmentNumber());
            installment.updateAccrualPortion(interestAccruable, feeAccruable, penaltyAccruable);
        }
        if (mergeTransactions && !MathUtil.isEmpty(totalInterestPortion)) {
            if (progressiveAccrual) {
                final Money interestAdjustmentPortion = MathUtil.negate(totalInterestPortion);
                createOrMergeAccrualTransaction(loan, mergeAdjustTransaction, accrualDate, null, accrualTransactions,
                        interestAdjustmentPortion, null, null, true);
            }
            createOrMergeAccrualTransaction(loan, mergeAccrualTransaction, accrualDate, null, accrualTransactions, totalInterestPortion,
                    null, null, false);
        }
        if (accrualTransactions.isEmpty()) {
            return;
        }

        if (!isFinal || progressiveAccrual) {
            loan.setAccruedTill(isFinal ? accrualDate : tillDate);
        }

        accrualTransactions = loanTransactionRepository.saveAll(accrualTransactions);
        loanTransactionRepository.flush();

        if (addJournal) {
            final List<AccountingBridgeLoanTransactionDTO> newTransactionDTOs = new ArrayList<>();
            for (LoanTransaction accrualTransaction : accrualTransactions) {
                final LoanTransactionBusinessEvent businessEvent = accrualTransaction.isAccrual()
                        ? new LoanAccrualTransactionCreatedBusinessEvent(accrualTransaction)
                        : new LoanAccrualAdjustmentTransactionBusinessEvent(accrualTransaction);
                businessEventNotifierService.notifyPostBusinessEvent(businessEvent);
                final AccountingBridgeLoanTransactionDTO transactionDTO = loanAccountingBridgeMapper
                        .mapToLoanTransactionData(accrualTransaction, currency.getCode());
                newTransactionDTOs.add(transactionDTO);
            }
            final AccountingBridgeDataDTO accountingBridgeData = new AccountingBridgeDataDTO(loan.getId(), loan.getLoanProduct().getId(),
                    loan.getOfficeId(), loan.getCurrencyCode(), loan.getSummary().getTotalInterestCharged(),
                    loan.isNoneOrCashOrUpfrontAccrualAccountingEnabledOnLoanProduct(),
                    loan.isUpfrontAccrualAccountingEnabledOnLoanProduct(), loan.isPeriodicAccrualAccountingEnabledOnLoanProduct(), false,
                    false, false, null, false, newTransactionDTOs);
            this.journalEntryWritePlatformService.createJournalEntriesForLoan(accountingBridgeData);
        }
    }

    private boolean isExistAccrualAfterDate(List<LoanTransaction> existingAccruals, LocalDate accrualDate) {
        return existingAccruals.stream().anyMatch(t -> !t.isReversed() && !DateUtils.isBefore(t.getDateOf(), accrualDate));
    }

    private boolean hasNoActiveChargeOnDate(Loan loan, LocalDate accrualDate) {
        return loan.getLoanCharges(t -> t.isActive() && DateUtils.isEqual(t.getDueDate(), accrualDate)).isEmpty();
    }

    private AccrualPeriodsData calculateAccrualAmounts(@NotNull final Loan loan, @NotNull final LocalDate tillDate, final boolean periodic,
            final boolean isFinal, final boolean chargeOnDueDate) {
        final LoanProductRelatedDetail productDetail = loan.getLoanProductRelatedDetail();
        final MonetaryCurrency currency = productDetail.getCurrency();
        final LoanScheduleGenerator scheduleGenerator = loanScheduleFactory.create(productDetail.getLoanScheduleType(),
                productDetail.getInterestMethod());
        final int firstInstallmentNumber = fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());
        final LocalDate interestCalculationTillDate = loan.isProgressiveSchedule()
                && loan.getLoanProductRelatedDetail().isInterestRecognitionOnDisbursementDate() ? tillDate.plusDays(1L) : tillDate;
        final List<LoanRepaymentScheduleInstallment> installments = isFinal ? loan.getRepaymentScheduleInstallments()
                : getInstallmentsToAccrue(loan, interestCalculationTillDate, periodic, chargeOnDueDate);
        final AccrualPeriodsData accrualPeriods = AccrualPeriodsData.create(installments, firstInstallmentNumber, currency);
        for (LoanRepaymentScheduleInstallment installment : installments) {
            addInterestAccrual(loan, interestCalculationTillDate, scheduleGenerator, installment, accrualPeriods);
            addChargeAccrual(loan, tillDate, chargeOnDueDate, installment, accrualPeriods);
        }
        return accrualPeriods;
    }

    @NotNull
    private List<LoanRepaymentScheduleInstallment> getInstallmentsToAccrue(@NotNull final Loan loan, @NotNull final LocalDate tillDate,
            final boolean periodic, final boolean chargeOnDueDate) {
        final LocalDate organisationStartDate = this.configurationDomainService.retrieveOrganisationStartDate();
        final int firstInstallmentNumber = fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());
        return loan.getRepaymentScheduleInstallments(i -> !i.isDownPayment()
                && (!chargeOnDueDate || (periodic ? !isBeforePeriod(tillDate, i, i.getInstallmentNumber().equals(firstInstallmentNumber))
                        : isFullPeriod(tillDate, i)))
                && !isAfterPeriod(organisationStartDate, i));
    }

    private void addInterestAccrual(@NotNull final Loan loan, @NotNull final LocalDate tillDate,
            final LoanScheduleGenerator scheduleGenerator, @NotNull final LoanRepaymentScheduleInstallment installment,
            @NotNull final AccrualPeriodsData accrualPeriods) {
        if (installment.isAdditional() || installment.isReAged()) {
            return;
        }
        final AccrualPeriodData period = accrualPeriods.getPeriodByInstallmentNumber(installment.getInstallmentNumber());
        final MonetaryCurrency currency = accrualPeriods.getCurrency();
        Money interest = null;
        final boolean isPastPeriod = isAfterPeriod(tillDate, installment);
        final boolean isInPeriod = isInPeriod(tillDate, installment, false);
        if (isPastPeriod || loan.isClosed() || loanBalanceService.isOverPaid(loan)) {
            interest = installment.getInterestCharged(currency).minus(installment.getCreditedInterest());
        } else {
            if (isInPeriod) { // first period first day is not accrued
                interest = scheduleGenerator.getPeriodInterestTillDate(installment, tillDate);
            }
        }
        period.setInterestAmount(interest);
        Money accruable = null;
        Money transactionWaived = null;
        if (!MathUtil.isEmpty(interest)) {
            transactionWaived = MathUtil.toMoney(calcInterestTransactionWaivedAmount(installment, tillDate), currency);
            Money unrecognizedWaived = MathUtil.toMoney(calcInterestUnrecognizedWaivedAmount(installment, accrualPeriods, tillDate),
                    currency);
            // unrecognized maximum is the waived portion which is not covered by waiver transactions
            unrecognizedWaived = MathUtil.min(unrecognizedWaived,
                    MathUtil.minusToZero(installment.getInterestWaived(currency), transactionWaived), false);
            period.setUnrecognizedWaive(unrecognizedWaived);
            final Money waived = isPastPeriod ? installment.getInterestWaived(currency)
                    : MathUtil.plus(transactionWaived, unrecognizedWaived);
            accruable = MathUtil.minusToZero(period.getInterestAmount(), waived);
        }
        period.setInterestAccruable(accruable);
        final Money transactionAccrued = MathUtil.toMoney(calcInterestAccruedAmount(installment, accrualPeriods, tillDate), currency);
        period.setTransactionAccrued(transactionAccrued);
        final Money accrued = MathUtil.minusToZero(transactionAccrued, transactionWaived);
        period.setInterestAccrued(accrued);
    }

    @NotNull
    private BigDecimal calcInterestTransactionWaivedAmount(@NotNull LoanRepaymentScheduleInstallment installment,
            @NotNull LocalDate tillDate) {
        Predicate<LoanTransaction> transactionPredicate = t -> !t.isReversed() && t.isInterestWaiver()
                && !DateUtils.isAfter(t.getTransactionDate(), tillDate);
        return installment.getLoanTransactionToRepaymentScheduleMappings().stream()
                .filter(tm -> transactionPredicate.test(tm.getLoanTransaction()))
                .map(LoanTransactionToRepaymentScheduleMapping::getInterestPortion).reduce(BigDecimal.ZERO, MathUtil::add);
    }

    @NotNull
    private BigDecimal calcInterestUnrecognizedWaivedAmount(@NotNull LoanRepaymentScheduleInstallment installment,
            @NotNull AccrualPeriodsData accrualPeriods, @NotNull LocalDate tillDate) {
        // unrecognized amount of the transaction is not mapped to installments
        LocalDate dueDate = installment.getDueDate();
        LocalDate toDate = DateUtils.isBefore(dueDate, tillDate) ? dueDate : tillDate;
        Predicate<LoanTransaction> transactionPredicate = t -> !t.isReversed() && t.isInterestWaiver()
                && !DateUtils.isAfter(t.getTransactionDate(), toDate);
        Loan loan = installment.getLoan();
        BigDecimal totalUnrecognized = loan.getLoanTransactions().stream().filter(transactionPredicate)
                .map(LoanTransaction::getUnrecognizedIncomePortion).reduce(BigDecimal.ZERO, MathUtil::add);
        // total unrecognized amount from previous periods
        BigDecimal prevUnrecognized = accrualPeriods.getPeriods().stream()
                .filter(p -> p.getInstallmentNumber() < installment.getInstallmentNumber())
                .map(p -> MathUtil.toBigDecimal(p.getUnrecognizedWaive())).reduce(BigDecimal.ZERO, MathUtil::add);
        // unrecognized amount left for this period (and maybe more)
        return MathUtil.min(installment.getInterestWaived(), MathUtil.subtractToZero(totalUnrecognized, prevUnrecognized), false);
    }

    @NotNull
    private BigDecimal calcInterestAccruedAmount(@NotNull LoanRepaymentScheduleInstallment installment,
            @NotNull AccrualPeriodsData accrualPeriods, @NotNull LocalDate tillDate) {
        Loan loan = installment.getLoan();
        if (isProgressiveAccrual(loan)) {
            BigDecimal totalAccrued = loan.getLoanTransactions().stream().filter(ACCRUAL_PREDICATE)
                    .map(t -> t.isAccrual() ? t.getInterestPortion() : MathUtil.negate(t.getInterestPortion()))
                    .reduce(BigDecimal.ZERO, MathUtil::add);
            BigDecimal prevAccrued = accrualPeriods.getPeriods().stream()
                    .filter(p -> p.getInstallmentNumber() < installment.getInstallmentNumber())
                    .map(p -> MathUtil.toBigDecimal(p.getTransactionAccrued())).reduce(BigDecimal.ZERO, MathUtil::add);
            BigDecimal accrued = MathUtil.subtractToZero(totalAccrued, prevAccrued);
            // if this is the current-last period, all the remaining accrued amount is added
            return isInPeriod(tillDate, installment, false) ? accrued : MathUtil.min(installment.getInterestAccrued(), accrued, false);
        } else {
            return isFullPeriod(tillDate, installment) ? installment.getInterestAccrued()
                    : loan.getLoanTransactions().stream()
                            .filter(t -> !t.isReversed() && t.isAccrual() && isInPeriod(t.getTransactionDate(), installment, false))
                            .map(LoanTransaction::getInterestPortion).reduce(BigDecimal.ZERO, MathUtil::add);
        }
    }

    private void addChargeAccrual(@NotNull final Loan loan, @NotNull final LocalDate tillDate, final boolean chargeOnDueDate,
            @NotNull final LoanRepaymentScheduleInstallment installment, @NotNull final AccrualPeriodsData accrualPeriods) {
        final AccrualPeriodData period = accrualPeriods.getPeriodByInstallmentNumber(installment.getInstallmentNumber());
        final LocalDate dueDate = installment.getDueDate();
        final Collection<LoanCharge> loanCharges = loan
                .getLoanCharges(lc -> !lc.isDueAtDisbursement() && (lc.isInstalmentFee() ? !DateUtils.isBefore(tillDate, dueDate)
                        : isChargeDue(lc, tillDate, chargeOnDueDate, installment, period.isFirstPeriod())));
        for (LoanCharge loanCharge : loanCharges) {
            if (loanCharge.isActive()) {
                addChargeAccrual(loanCharge, tillDate, chargeOnDueDate, installment, accrualPeriods);
            }
        }
    }

    private void addChargeAccrual(@NotNull final LoanCharge loanCharge, @NotNull final LocalDate tillDate, final boolean chargeOnDueDate,
            @NotNull final LoanRepaymentScheduleInstallment installment, @NotNull final AccrualPeriodsData accrualPeriods) {
        final MonetaryCurrency currency = accrualPeriods.getCurrency();
        final Integer firstInstallmentNumber = accrualPeriods.getFirstInstallmentNumber();
        final boolean installmentFee = loanCharge.isInstalmentFee();
        final LoanRepaymentScheduleInstallment dueInstallment = (installmentFee || chargeOnDueDate) ? installment
                : loanCharge.getLoan().getRepaymentScheduleInstallment(
                        i -> isInPeriod(loanCharge.getDueDate(), i, i.getInstallmentNumber().equals(firstInstallmentNumber)));
        final AccrualPeriodData duePeriod = accrualPeriods.getPeriodByInstallmentNumber(dueInstallment.getInstallmentNumber());
        final boolean isFullPeriod = isFullPeriod(tillDate, dueInstallment);

        Money chargeAmount;
        Money waived;
        Collection<LoanChargePaidBy> paidBys;
        Long installmentChargeId = null;
        if (installmentFee) {
            final LoanInstallmentCharge installmentCharge = loanCharge.getInstallmentLoanCharge(dueInstallment.getInstallmentNumber());
            if (installmentCharge == null) {
                return;
            }
            chargeAmount = installmentCharge.getAmount(currency);
            paidBys = loanCharge.getLoanChargePaidBy(pb -> dueInstallment.getInstallmentNumber().equals(pb.getInstallmentNumber()));
            waived = isFullPeriod ? installmentCharge.getAmountWaived(currency)
                    : MathUtil.toMoney(calcChargeWaivedAmount(paidBys, tillDate), currency);
            installmentChargeId = installmentCharge.getId();
        } else {
            chargeAmount = loanCharge.getAmount(currency);
            paidBys = loanCharge.getLoanChargePaidBySet();
            waived = isFullPeriod ? loanCharge.getAmountWaived(currency)
                    : MathUtil.toMoney(calcChargeWaivedAmount(paidBys, tillDate), currency);
        }
        final AccrualChargeData chargeData = new AccrualChargeData(loanCharge.getId(), installmentChargeId, loanCharge.isPenaltyCharge())
                .setChargeAmount(chargeAmount);
        chargeData.setChargeAccruable(MathUtil.minusToZero(chargeAmount, waived));

        final Money unrecognizedWaived = MathUtil.toMoney(calcChargeUnrecognizedWaivedAmount(paidBys, tillDate), currency);
        final Money transactionWaived = MathUtil.minusToZero(waived, unrecognizedWaived);
        final Money transactionAccrued = MathUtil.toMoney(calcChargeAccruedAmount(paidBys), currency);
        chargeData.setTransactionAccrued(transactionAccrued);
        chargeData.setChargeAccrued(MathUtil.minusToZero(transactionAccrued, transactionWaived));

        duePeriod.addCharge(chargeData);
    }

    @NotNull
    private BigDecimal calcChargeWaivedAmount(@NotNull final Collection<LoanChargePaidBy> loanChargePaidBy,
            @NotNull final LocalDate tillDate) {
        return loanChargePaidBy.stream().filter(pb -> {
            final LoanTransaction t = pb.getLoanTransaction();
            return !t.isReversed() && t.isWaiveCharge() && !DateUtils.isAfter(t.getTransactionDate(), tillDate);
        }).map(LoanChargePaidBy::getAmount).reduce(BigDecimal.ZERO, MathUtil::add);
    }

    @NotNull
    private BigDecimal calcChargeUnrecognizedWaivedAmount(@NotNull final Collection<LoanChargePaidBy> loanChargePaidBy,
            @NotNull final LocalDate tillDate) {
        return loanChargePaidBy.stream().filter(pb -> {
            final LoanTransaction t = pb.getLoanTransaction();
            return !t.isReversed() && t.isWaiveCharge() && !DateUtils.isAfter(t.getTransactionDate(), tillDate);
        }).map(pb -> pb.getLoanTransaction().getUnrecognizedIncomePortion()).reduce(BigDecimal.ZERO, MathUtil::add);
    }

    @NotNull
    private BigDecimal calcChargeAccruedAmount(@NotNull final Collection<LoanChargePaidBy> loanChargePaidBy) {
        return loanChargePaidBy.stream().filter(pb -> ACCRUAL_PREDICATE.test(pb.getLoanTransaction()))
                .map(pb -> pb.getLoanTransaction().isAccrual() ? pb.getAmount() : MathUtil.negate(pb.getAmount()))
                .reduce(BigDecimal.ZERO, MathUtil::add);
    }

    private boolean isChargeDue(@NotNull final LoanCharge loanCharge, @NotNull final LocalDate tillDate, boolean chargeOnDueDate,
            final LoanRepaymentScheduleInstallment installment, final boolean isFirstPeriod) {
        final LocalDate fromDate = installment.getFromDate();
        final LocalDate dueDate = installment.getDueDate();
        final LocalDate toDate = DateUtils.isBefore(dueDate, tillDate) ? dueDate : tillDate;
        chargeOnDueDate = chargeOnDueDate || loanCharge.getDueLocalDate().isBefore(loanCharge.getSubmittedOnDate());
        return chargeOnDueDate ? loanCharge.isDueInPeriod(fromDate, toDate, isFirstPeriod)
                : isInPeriod(loanCharge.getSubmittedOnDate(), fromDate, toDate, isFirstPeriod);
    }

    private LoanTransaction createOrMergeAccrualTransaction(@NotNull final Loan loan, LoanTransaction transaction,
            final LocalDate transactionDate, final AccrualPeriodData accrualPeriod, final List<LoanTransaction> accrualTransactions,
            final Money interest, final Money fee, final Money penalty, final boolean adjustment) {
        if (transaction == null) {
            transaction = addAccrualTransaction(loan, transactionDate, accrualPeriod, interest, fee, penalty, adjustment);
            if (transaction != null) {
                accrualTransactions.add(transaction);
            }
        } else {
            mergeAccrualTransaction(transaction, accrualPeriod, interest, fee, penalty, adjustment);
        }
        return transaction;
    }

    private LoanTransaction addAccrualTransaction(@NotNull Loan loan, @NotNull LocalDate transactionDate, AccrualPeriodData accrualPeriod,
            Money interestPortion, Money feePortion, Money penaltyPortion, boolean adjustment) {
        interestPortion = MathUtil.negativeToZero(interestPortion);
        BigDecimal interest = MathUtil.toBigDecimal(interestPortion);
        feePortion = MathUtil.negativeToZero(feePortion);
        BigDecimal fee = MathUtil.toBigDecimal(feePortion);
        penaltyPortion = MathUtil.negativeToZero(penaltyPortion);
        BigDecimal penalty = MathUtil.toBigDecimal(penaltyPortion);
        BigDecimal amount = MathUtil.add(interest, fee, penalty);
        if (!MathUtil.isGreaterThanZero(amount)) {
            return null;
        }
        LoanTransaction transaction = adjustment
                ? accrualAdjustment(loan, loan.getOffice(), transactionDate, amount, interest, fee, penalty, externalIdFactory.create())
                : accrueTransaction(loan, loan.getOffice(), transactionDate, amount, interest, fee, penalty, externalIdFactory.create());
        loan.addLoanTransaction(transaction);

        // update repayment schedule portions
        addTransactionMappings(transaction, accrualPeriod, adjustment);
        return transaction;
    }

    private void mergeAccrualTransaction(@NotNull final LoanTransaction transaction, final AccrualPeriodData accrualPeriod,
            Money interestPortion, Money feePortion, Money penaltyPortion, final boolean adjustment) {
        interestPortion = MathUtil.negativeToZero(interestPortion);
        feePortion = MathUtil.negativeToZero(feePortion);
        penaltyPortion = MathUtil.negativeToZero(penaltyPortion);
        if (MathUtil.isEmpty(interestPortion) && MathUtil.isEmpty(feePortion) && MathUtil.isEmpty(penaltyPortion)) {
            return;
        }

        transaction.updateComponentsAndTotal(null, interestPortion, feePortion, penaltyPortion);
        // update repayment schedule portions
        addTransactionMappings(transaction, accrualPeriod, adjustment);
    }

    private void addTransactionMappings(@NotNull final LoanTransaction transaction, final AccrualPeriodData accrualPeriod,
            final boolean adjustment) {
        if (accrualPeriod == null) {
            return;
        }
        final Loan loan = transaction.getLoan();
        final Integer installmentNumber = accrualPeriod.getInstallmentNumber();
        final LoanRepaymentScheduleInstallment installment = loan.fetchRepaymentScheduleInstallment(installmentNumber);

        // add charges paid by mappings
        addPaidByMappings(transaction, installment, accrualPeriod, adjustment);
    }

    private void addPaidByMappings(@NotNull final LoanTransaction transaction, final LoanRepaymentScheduleInstallment installment,
            final AccrualPeriodData accrualPeriod, final boolean adjustment) {
        final Loan loan = installment.getLoan();
        final MonetaryCurrency currency = loan.getCurrency();
        for (AccrualChargeData accrualCharge : accrualPeriod.getCharges()) {
            final Money chargeAccruable = MathUtil.nullToZero(accrualCharge.getChargeAccruable(), currency);
            Money chargePortion = MathUtil.minus(chargeAccruable, accrualCharge.getChargeAccrued());
            chargePortion = MathUtil.negativeToZero(adjustment ? MathUtil.negate(chargePortion) : chargePortion);
            if (MathUtil.isEmpty(chargePortion)) {
                continue;
            }
            final BigDecimal chargeAmount = MathUtil.toBigDecimal(chargePortion);
            final LoanCharge loanCharge = loanChargeService.fetchLoanChargesById(loan, accrualCharge.getLoanChargeId());
            final LoanChargePaidBy paidBy = new LoanChargePaidBy(transaction, loanCharge, chargeAmount, installment.getInstallmentNumber());
            loanCharge.getLoanChargePaidBySet().add(paidBy);
            transaction.getLoanChargesPaid().add(paidBy);
            final Long installmentChargeId = accrualCharge.getLoanInstallmentChargeId();
            if (installmentChargeId != null) {
                final LoanInstallmentCharge installmentCharge = new LoanInstallmentCharge(chargeAmount, loanCharge, installment);
                loanCharge.getLoanInstallmentCharge().add(installmentCharge);
                installment.getInstallmentCharges().add(installmentCharge);
            }
        }
    }

    private boolean isFullPeriod(@NotNull final LocalDate tillDate, @NotNull final LoanRepaymentScheduleInstallment installment) {
        return isAfterPeriod(tillDate, installment) || DateUtils.isEqual(tillDate, installment.getDueDate());
    }

    // ReprocessAccruals

    private void reprocessPeriodicAccruals(Loan loan, final List<LoanTransaction> accrualTransactions) {
        if (loan.isChargedOff()) {
            return;
        }
        final boolean isChargeOnDueDate = isChargeOnDueDate();
        ensureAccrualTransactionMappings(loan, accrualTransactions, isChargeOnDueDate);
        LoanRepaymentScheduleInstallment lastInstallment = loan.getLastLoanRepaymentScheduleInstallment();
        LocalDate lastDueDate = lastInstallment.getDueDate();
        if (isProgressiveAccrual(loan)) {
            AccrualBalances accrualBalances = new AccrualBalances();
            accrualTransactions.forEach(lt -> {
                switch (lt.getTypeOf()) {
                    case ACCRUAL -> {
                        accrualBalances.setFeePortion(MathUtil.add(accrualBalances.getFeePortion(), lt.getFeeChargesPortion()));
                        accrualBalances.setPenaltyPortion(MathUtil.add(accrualBalances.getPenaltyPortion(), lt.getPenaltyChargesPortion()));
                        accrualBalances.setInterestPortion(MathUtil.add(accrualBalances.getInterestPortion(), lt.getInterestPortion()));
                    }
                    case ACCRUAL_ADJUSTMENT -> {
                        accrualBalances.setFeePortion(MathUtil.subtract(accrualBalances.getFeePortion(), lt.getFeeChargesPortion()));
                        accrualBalances
                                .setPenaltyPortion(MathUtil.subtract(accrualBalances.getPenaltyPortion(), lt.getPenaltyChargesPortion()));
                        accrualBalances
                                .setInterestPortion(MathUtil.subtract(accrualBalances.getInterestPortion(), lt.getInterestPortion()));
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + lt.getTypeOf());
                }
            });
            for (LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
                BigDecimal maximumAccruableInterest = MathUtil.nullToZero(installment.getInterestCharged());
                BigDecimal maximumAccruableFee = MathUtil.nullToZero(installment.getFeeChargesCharged());
                BigDecimal maximumAccruablePenalty = MathUtil.nullToZero(installment.getPenaltyCharges());

                if (MathUtil.isLessThanOrEqualTo(maximumAccruableInterest, accrualBalances.getInterestPortion())) {
                    installment.setInterestAccrued(maximumAccruableInterest);
                    accrualBalances.setInterestPortion(accrualBalances.getInterestPortion().subtract(maximumAccruableInterest));
                } else {
                    installment.setInterestAccrued(accrualBalances.getInterestPortion());
                    accrualBalances.setInterestPortion(BigDecimal.ZERO);
                }

                if (MathUtil.isLessThanOrEqualTo(maximumAccruableFee, accrualBalances.getFeePortion())) {
                    installment.setFeeAccrued(maximumAccruableFee);
                    accrualBalances.setFeePortion(accrualBalances.getFeePortion().subtract(maximumAccruableFee));
                } else {
                    installment.setFeeAccrued(accrualBalances.getFeePortion());
                    accrualBalances.setFeePortion(BigDecimal.ZERO);
                }

                if (MathUtil.isLessThanOrEqualTo(maximumAccruablePenalty, accrualBalances.getPenaltyPortion())) {
                    installment.setPenaltyAccrued(maximumAccruablePenalty);
                    accrualBalances.setPenaltyPortion(accrualBalances.getPenaltyPortion().subtract(maximumAccruablePenalty));
                } else {
                    installment.setPenaltyAccrued(accrualBalances.getPenaltyPortion());
                    accrualBalances.setPenaltyPortion(BigDecimal.ZERO);
                }
            }
        } else {
            List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
            boolean isBasedOnSubmittedOnDate = !isChargeOnDueDate;
            for (LoanRepaymentScheduleInstallment installment : installments) {
                checkAndUpdateAccrualsForInstallment(loan, accrualTransactions, installments, isBasedOnSubmittedOnDate, installment);
            }
        }
        // reverse accruals after last installment
        reverseTransactionsAfter(accrualTransactions, lastDueDate, false);
    }

    private void reprocessNonPeriodicAccruals(Loan loan, final List<LoanTransaction> accrualTransactions) {
        if (isProgressiveAccrual(loan)) {
            return;
        }
        final Money interestApplied = Money.of(loan.getCurrency(), loan.getSummary().getTotalInterestCharged());
        ExternalId externalId = ExternalId.empty();
        boolean isExternalIdAutoGenerationEnabled = configurationDomainService.isExternalIdAutoGenerationEnabled();

        for (LoanTransaction accrualTransaction : accrualTransactions) {
            if (accrualTransaction.getInterestPortion(loan.getCurrency()).isGreaterThanZero()) {
                if (accrualTransaction.getInterestPortion(loan.getCurrency()).isNotEqualTo(interestApplied)) {
                    accrualTransaction.reverse();
                    if (isExternalIdAutoGenerationEnabled) {
                        externalId = ExternalId.generate();
                    }
                    final LoanTransaction interestAccrualTransaction = LoanTransaction.accrueInterest(loan.getOffice(), loan,
                            interestApplied, loan.getDisbursementDate(), externalId);
                    loan.addLoanTransaction(interestAccrualTransaction);
                }
            } else {
                Set<LoanChargePaidBy> chargePaidBies = accrualTransaction.getLoanChargesPaid();
                for (final LoanChargePaidBy chargePaidBy : chargePaidBies) {
                    LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                    Money chargeAmount = loanCharge.getAmount(loan.getCurrency());
                    if (chargeAmount.isNotEqualTo(accrualTransaction.getAmount(loan.getCurrency()))) {
                        accrualTransaction.reverse();
                        loanChargeService.handleChargeAppliedTransaction(loan, loanCharge, accrualTransaction.getTransactionDate());
                    }
                }
            }
        }
    }

    private void checkAndUpdateAccrualsForInstallment(Loan loan, List<LoanTransaction> accrualTransactions,
            List<LoanRepaymentScheduleInstallment> installments, boolean isBasedOnSubmittedOnDate,
            LoanRepaymentScheduleInstallment installment) {
        MonetaryCurrency currency = loan.getCurrency();
        Money zero = Money.zero(currency);
        Money interest = zero;
        Money fee = zero;
        Money penalty = zero;
        for (LoanTransaction accrualTransaction : accrualTransactions) {
            LocalDate transactionDateForRange = getDateForRangeCalculation(accrualTransaction, isBasedOnSubmittedOnDate);
            boolean isInPeriod = isInPeriod(transactionDateForRange, installment, installments);
            if (isInPeriod) {
                interest = MathUtil.plus(interest, accrualTransaction.getInterestPortion(currency));
                fee = MathUtil.plus(fee, accrualTransaction.getFeeChargesPortion(currency));
                penalty = MathUtil.plus(penalty, accrualTransaction.getPenaltyChargesPortion(currency));
                if (hasIncomeAmountChangedForInstallment(loan, installment, interest, fee, penalty, accrualTransaction)) {
                    interest = interest.minus(accrualTransaction.getInterestPortion(currency));
                    fee = fee.minus(accrualTransaction.getFeeChargesPortion(currency));
                    penalty = penalty.minus(accrualTransaction.getPenaltyChargesPortion(currency));
                    accrualTransaction.reverse();
                }
            }
        }
        installment.updateAccrualPortion(interest, fee, penalty);
    }

    private boolean hasIncomeAmountChangedForInstallment(Loan loan, LoanRepaymentScheduleInstallment installment, Money interest, Money fee,
            Money penalty, LoanTransaction loanTransaction) {
        // if installment income amount is changed or if loan is interest bearing and interest income not accrued
        return installment.getFeeChargesCharged(loan.getCurrency()).isLessThan(fee)
                || installment.getInterestCharged(loan.getCurrency()).isLessThan(interest)
                || installment.getPenaltyChargesCharged(loan.getCurrency()).isLessThan(penalty)
                || (loan.isInterestBearing() && DateUtils.isEqual(loan.getAccruedTill(), loanTransaction.getTransactionDate())
                        && !DateUtils.isEqual(loan.getAccruedTill(), installment.getDueDate()));
    }

    private LocalDate getDateForRangeCalculation(LoanTransaction loanTransaction, boolean isChargeAccrualBasedOnSubmittedOnDate) {
        // check config for charge accrual date and return date
        return isChargeAccrualBasedOnSubmittedOnDate && !loanTransaction.getLoanChargesPaid().isEmpty()
                ? loanTransaction.getLoanChargesPaid().stream().findFirst().get().getLoanCharge().getEffectiveDueDate()
                : loanTransaction.getTransactionDate();
    }

    // IncomePosting

    private List<LoanInterestRecalcualtionAdditionalDetails> extractInterestRecalculationAdditionalDetails(Loan loan) {
        List<LoanInterestRecalcualtionAdditionalDetails> retDetails = new ArrayList<>();
        List<LoanRepaymentScheduleInstallment> repaymentSchedule = loan.getRepaymentScheduleInstallments();
        if (null != repaymentSchedule) {
            for (LoanRepaymentScheduleInstallment installment : repaymentSchedule) {
                if (null != installment.getLoanCompoundingDetails()) {
                    retDetails.addAll(installment.getLoanCompoundingDetails());
                }
            }
        }
        retDetails.sort(Comparator.comparing(LoanInterestRecalcualtionAdditionalDetails::getEffectiveDate));
        return retDetails;
    }

    private void addUpdateIncomeAndAccrualTransaction(Loan loan, LoanInterestRecalcualtionAdditionalDetails compoundingDetail,
            LocalDate lastCompoundingDate, LoanTransaction existingIncomeTransaction, LoanTransaction existingAccrualTransaction) {
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal penalties = BigDecimal.ZERO;
        HashMap<String, Object> feeDetails = new HashMap<>();

        if (loan.getLoanInterestRecalculationDetails().getInterestRecalculationCompoundingMethod()
                .equals(InterestRecalculationCompoundingMethod.INTEREST)) {
            interest = compoundingDetail.getAmount();
        } else if (loan.getLoanInterestRecalculationDetails().getInterestRecalculationCompoundingMethod()
                .equals(InterestRecalculationCompoundingMethod.FEE)) {
            determineFeeDetails(loan, lastCompoundingDate, compoundingDetail.getEffectiveDate(), feeDetails);
            fee = (BigDecimal) feeDetails.get(Loan.FEE);
            penalties = (BigDecimal) feeDetails.get(Loan.PENALTIES);
        } else if (loan.getLoanInterestRecalculationDetails().getInterestRecalculationCompoundingMethod()
                .equals(InterestRecalculationCompoundingMethod.INTEREST_AND_FEE)) {
            determineFeeDetails(loan, lastCompoundingDate, compoundingDetail.getEffectiveDate(), feeDetails);
            fee = (BigDecimal) feeDetails.get(Loan.FEE);
            penalties = (BigDecimal) feeDetails.get(Loan.PENALTIES);
            interest = compoundingDetail.getAmount().subtract(fee).subtract(penalties);
        }

        ExternalId externalId = ExternalId.empty();
        if (configurationDomainService.isExternalIdAutoGenerationEnabled()) {
            externalId = ExternalId.generate();
        }

        createUpdateIncomePostingTransaction(loan, compoundingDetail, existingIncomeTransaction, interest, fee, penalties, externalId);
        createUpdateAccrualTransaction(loan, compoundingDetail, existingAccrualTransaction, interest, fee, penalties, feeDetails,
                externalId);
        loan.updateLoanOutstandingBalances();
    }

    private void createUpdateIncomePostingTransaction(Loan loan, LoanInterestRecalcualtionAdditionalDetails compoundingDetail,
            LoanTransaction existingIncomeTransaction, BigDecimal interest, BigDecimal fee, BigDecimal penalties, ExternalId externalId) {
        if (existingIncomeTransaction == null) {
            LoanTransaction transaction = LoanTransaction.incomePosting(loan, loan.getOffice(), compoundingDetail.getEffectiveDate(),
                    compoundingDetail.getAmount(), interest, fee, penalties, externalId);
            loan.addLoanTransaction(transaction);
        } else if (existingIncomeTransaction.getAmount(loan.getCurrency()).getAmount().compareTo(compoundingDetail.getAmount()) != 0) {
            existingIncomeTransaction.reverse();
            LoanTransaction transaction = LoanTransaction.incomePosting(loan, loan.getOffice(), compoundingDetail.getEffectiveDate(),
                    compoundingDetail.getAmount(), interest, fee, penalties, externalId);
            loan.addLoanTransaction(transaction);
        }
    }

    private void createUpdateAccrualTransaction(Loan loan, LoanInterestRecalcualtionAdditionalDetails compoundingDetail,
            LoanTransaction existingAccrualTransaction, BigDecimal interest, BigDecimal fee, BigDecimal penalties,
            HashMap<String, Object> feeDetails, ExternalId externalId) {
        if (configurationDomainService.isExternalIdAutoGenerationEnabled()) {
            externalId = ExternalId.generate();
        }

        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            if (existingAccrualTransaction == null
                    || !MathUtil.isEqualTo(existingAccrualTransaction.getAmount(), compoundingDetail.getAmount())) {
                if (existingAccrualTransaction != null) {
                    existingAccrualTransaction.reverse();
                }
                LoanTransaction accrual = LoanTransaction.accrueTransaction(loan, loan.getOffice(), compoundingDetail.getEffectiveDate(),
                        compoundingDetail.getAmount(), interest, fee, penalties, externalId);
                updateLoanChargesPaidBy(loan, accrual, feeDetails, null);
                loan.addLoanTransaction(accrual);
            }
        }
    }

    // LoanClosure

    private void processIncomeAndAccrualTransactionOnLoanClosure(Loan loan) {
        // TODO analyze progressive accrual case
        if (loan.getLoanInterestRecalculationDetails() != null
                && loan.getLoanInterestRecalculationDetails().isCompoundingToBePostedAsTransaction()
                && loan.getStatus().isClosedObligationsMet() && !loan.isNpa() && !loan.isChargedOff()) {

            LocalDate closedDate = loan.getClosedOnDate();
            reverseTransactionsOnOrAfter(retrieveListOfIncomePostingTransactions(loan), closedDate);
            reverseTransactionsOnOrAfter(retrieveListOfAccrualTransactions(loan), closedDate);

            HashMap<String, BigDecimal> cumulativeIncomeFromInstallments = new HashMap<>();
            determineCumulativeIncomeFromInstallments(loan, cumulativeIncomeFromInstallments);
            HashMap<String, BigDecimal> cumulativeIncomeFromIncomePosting = new HashMap<>();
            determineCumulativeIncomeDetails(loan, retrieveListOfIncomePostingTransactions(loan), cumulativeIncomeFromIncomePosting);

            BigDecimal interestToPost = cumulativeIncomeFromInstallments.get(Loan.INTEREST)
                    .subtract(cumulativeIncomeFromIncomePosting.get(Loan.INTEREST));
            BigDecimal feeToPost = cumulativeIncomeFromInstallments.get(Loan.FEE).subtract(cumulativeIncomeFromIncomePosting.get(Loan.FEE));
            BigDecimal penaltyToPost = cumulativeIncomeFromInstallments.get(Loan.PENALTY)
                    .subtract(cumulativeIncomeFromIncomePosting.get(Loan.PENALTY));
            BigDecimal amountToPost = interestToPost.add(feeToPost).add(penaltyToPost);

            createIncomePostingAndAccrualTransactionOnLoanClosure(loan, closedDate, interestToPost, feeToPost, penaltyToPost, amountToPost);
        }
        loan.updateLoanOutstandingBalances();
    }

    private void determineCumulativeIncomeFromInstallments(Loan loan, HashMap<String, BigDecimal> cumulativeIncomeFromInstallments) {
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        for (LoanRepaymentScheduleInstallment installment : installments) {
            interest = interest.add(installment.getInterestCharged(loan.getCurrency()).getAmount());
            fee = fee.add(installment.getFeeChargesCharged(loan.getCurrency()).getAmount());
            penalty = penalty.add(installment.getPenaltyChargesCharged(loan.getCurrency()).getAmount());
        }
        cumulativeIncomeFromInstallments.put(Loan.INTEREST, interest);
        cumulativeIncomeFromInstallments.put(Loan.FEE, fee);
        cumulativeIncomeFromInstallments.put(Loan.PENALTY, penalty);
    }

    private void determineCumulativeIncomeDetails(Loan loan, List<LoanTransaction> transactions,
            HashMap<String, BigDecimal> incomeDetailsMap) {
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;
        for (LoanTransaction transaction : transactions) {
            interest = interest.add(transaction.getInterestPortion(loan.getCurrency()).getAmount());
            fee = fee.add(transaction.getFeeChargesPortion(loan.getCurrency()).getAmount());
            penalty = penalty.add(transaction.getPenaltyChargesPortion(loan.getCurrency()).getAmount());
        }
        incomeDetailsMap.put(Loan.INTEREST, interest);
        incomeDetailsMap.put(Loan.FEE, fee);
        incomeDetailsMap.put(Loan.PENALTY, penalty);
    }

    private void createIncomePostingAndAccrualTransactionOnLoanClosure(Loan loan, LocalDate closedDate, BigDecimal interestToPost,
            BigDecimal feeToPost, BigDecimal penaltyToPost, BigDecimal amountToPost) {
        ExternalId externalId = ExternalId.empty();
        boolean isExternalIdAutoGenerationEnabled = configurationDomainService.isExternalIdAutoGenerationEnabled();

        if (isExternalIdAutoGenerationEnabled) {
            externalId = ExternalId.generate();
        }
        LoanTransaction finalIncomeTransaction = LoanTransaction.incomePosting(loan, loan.getOffice(), closedDate, amountToPost,
                interestToPost, feeToPost, penaltyToPost, externalId);
        loan.addLoanTransaction(finalIncomeTransaction);

        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            List<LoanTransaction> updatedAccrualTransactions = retrieveListOfAccrualTransactions(loan);
            LocalDate lastAccruedDate = loan.getDisbursementDate();
            if (!updatedAccrualTransactions.isEmpty()) {
                lastAccruedDate = updatedAccrualTransactions.get(updatedAccrualTransactions.size() - 1).getTransactionDate();
            }
            HashMap<String, Object> feeDetails = new HashMap<>();
            determineFeeDetails(loan, lastAccruedDate, closedDate, feeDetails);
            if (isExternalIdAutoGenerationEnabled) {
                externalId = ExternalId.generate();
            }
            LoanTransaction finalAccrual = LoanTransaction.accrueTransaction(loan, loan.getOffice(), closedDate, amountToPost,
                    interestToPost, feeToPost, penaltyToPost, externalId);
            updateLoanChargesPaidBy(loan, finalAccrual, feeDetails, null);
            loan.addLoanTransaction(finalAccrual);
        }
    }

    // LoanForClosure

    private void determineReceivableIncomeForeClosure(Loan loan, final LocalDate tillDate, Map<String, Object> incomeDetails) {
        MonetaryCurrency currency = loan.getCurrency();
        Money receivableInterest = Money.zero(currency);
        Money receivableFee = Money.zero(currency);
        Money receivablePenalty = Money.zero(currency);
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (transaction.isNotReversed() && !transaction.isRepaymentAtDisbursement() && !transaction.isDisbursement()
                    && !DateUtils.isAfter(transaction.getTransactionDate(), tillDate)) {
                if (transaction.isAccrual()) {
                    receivableInterest = receivableInterest.plus(transaction.getInterestPortion(currency));
                    receivableFee = receivableFee.plus(transaction.getFeeChargesPortion(currency));
                    receivablePenalty = receivablePenalty.plus(transaction.getPenaltyChargesPortion(currency));
                } else if (transaction.isRepaymentLikeType() || transaction.isChargePayment() || transaction.isAccrualAdjustment()) {
                    receivableInterest = receivableInterest.minus(transaction.getInterestPortion(currency));
                    receivableFee = receivableFee.minus(transaction.getFeeChargesPortion(currency));
                    receivablePenalty = receivablePenalty.minus(transaction.getPenaltyChargesPortion(currency));
                }
            }
            if (receivableInterest.isLessThanZero()) {
                receivableInterest = receivableInterest.zero();
            }
            if (receivableFee.isLessThanZero()) {
                receivableFee = receivableFee.zero();
            }
            if (receivablePenalty.isLessThanZero()) {
                receivablePenalty = receivablePenalty.zero();
            }
        }

        incomeDetails.put(Loan.INTEREST, receivableInterest);
        incomeDetails.put(Loan.FEE, receivableFee);
        incomeDetails.put(Loan.PENALTIES, receivablePenalty);
    }

    private void createAccrualTransactionAndUpdateChargesPaidBy(Loan loan, LocalDate foreClosureDate,
            List<LoanTransaction> newAccrualTransactions, MonetaryCurrency currency, Money interestPortion, Money feePortion,
            Money penaltyPortion, Money total) {
        ExternalId accrualExternalId = externalIdFactory.create();
        LoanTransaction accrualTransaction = LoanTransaction.accrueTransaction(loan, loan.getOffice(), foreClosureDate, total.getAmount(),
                interestPortion.getAmount(), feePortion.getAmount(), penaltyPortion.getAmount(), accrualExternalId);
        LocalDate fromDate = loan.getDisbursementDate();
        if (loan.getAccruedTill() != null) {
            fromDate = loan.getAccruedTill();
        }
        newAccrualTransactions.add(accrualTransaction);
        loan.addLoanTransaction(accrualTransaction);
        Set<LoanChargePaidBy> accrualCharges = accrualTransaction.getLoanChargesPaid();
        for (LoanCharge loanCharge : loan.getActiveCharges()) {
            boolean isDue = loanCharge.isDueInPeriod(fromDate, foreClosureDate, DateUtils.isEqual(fromDate, loan.getDisbursementDate()));
            if (loanCharge.isActive() && !loanCharge.isPaid() && (isDue || loanCharge.isInstalmentFee())) {
                final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(accrualTransaction, loanCharge,
                        loanCharge.getAmountOutstanding(currency).getAmount(), null);
                accrualCharges.add(loanChargePaidBy);
                loanCharge.getLoanChargePaidBySet().add(loanChargePaidBy);
            }
        }
    }

    private void postJournalEntries(final Loan loan, final List<Long> existingTransactionIds,
            final List<Long> existingReversedTransactionIds) {
        final MonetaryCurrency currency = loan.getCurrency();
        boolean isAccountTransfer = false;
        final AccountingBridgeDataDTO accountingBridgeData = loanAccountingBridgeMapper.deriveAccountingBridgeData(currency.getCode(),
                existingTransactionIds, existingReversedTransactionIds, isAccountTransfer, loan);
        journalEntryWritePlatformService.createJournalEntriesForLoan(accountingBridgeData);
    }

    private void ensureAccrualTransactionMappings(final Loan loan, final List<LoanTransaction> existingAccrualTransactions,
            final boolean chargeOnDueDate) {
        final List<LoanTransaction> transactions = existingAccrualTransactions.stream()
                .filter(t -> !MathUtil.isEmpty(t.getFeeChargesPortion()) || !MathUtil.isEmpty(t.getPenaltyChargesPortion())).toList();

        if (transactions.isEmpty()) {
            return;
        }

        final int firstInstallmentNumber = fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());
        for (LoanTransaction transaction : transactions) {
            for (LoanChargePaidBy paidBy : transaction.getLoanChargesPaid()) {
                if (paidBy.getInstallmentNumber() == null) {
                    final LoanCharge loanCharge = paidBy.getLoanCharge();
                    final LocalDate chargeDate = (chargeOnDueDate || loanCharge.isInstalmentFee()) ? transaction.getTransactionDate()
                            : loanCharge.getDueDate();
                    final LoanRepaymentScheduleInstallment installment = loan.getRepaymentScheduleInstallment(
                            i -> isInPeriod(chargeDate, i, i.getInstallmentNumber().equals(firstInstallmentNumber)));
                    paidBy.setInstallmentNumber(installment.getInstallmentNumber());
                }
            }
        }
    }

    private List<LoanTransaction> retrieveListOfAccrualTransactions(final Loan loan) {
        return loan.getLoanTransactions().stream().filter(ACCRUAL_PREDICATE).sorted(LoanTransactionComparator.INSTANCE)
                .collect(Collectors.toList());
    }

    private List<LoanTransaction> retrieveListOfIncomePostingTransactions(final Loan loan) {
        return loan.getLoanTransactions().stream() //
                .filter(transaction -> transaction.isNotReversed() && transaction.isIncomePosting()) //
                .sorted(LoanTransactionComparator.INSTANCE).collect(Collectors.toList());
    }

    private LoanTransaction getTransactionForDate(final List<LoanTransaction> transactions, final LocalDate effectiveDate) {
        for (LoanTransaction loanTransaction : transactions) {
            if (DateUtils.isEqual(effectiveDate, loanTransaction.getTransactionDate())) {
                return loanTransaction;
            }
        }
        return null;
    }

    private boolean isChargeOnDueDate() {
        final String chargeAccrualDateType = configurationDomainService.getAccrualDateConfigForCharge();
        return !ACCRUAL_ON_CHARGE_SUBMITTED_ON_DATE.equalsIgnoreCase(chargeAccrualDateType);
    }

    private void determineFeeDetails(Loan loan, LocalDate fromDate, LocalDate toDate, Map<String, Object> feeDetails) {
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal penalties = BigDecimal.ZERO;

        List<Integer> installments = new ArrayList<>();
        List<LoanRepaymentScheduleInstallment> repaymentSchedule = loan.getRepaymentScheduleInstallments();
        for (LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment : repaymentSchedule) {
            if (DateUtils.isAfter(loanRepaymentScheduleInstallment.getDueDate(), fromDate)
                    && !DateUtils.isAfter(loanRepaymentScheduleInstallment.getDueDate(), toDate)) {
                installments.add(loanRepaymentScheduleInstallment.getInstallmentNumber());
            }
        }

        List<LoanCharge> loanCharges = new ArrayList<>();
        List<LoanInstallmentCharge> loanInstallmentCharges = new ArrayList<>();
        for (LoanCharge loanCharge : loan.getActiveCharges()) {
            boolean isDue = loanCharge.isDueInPeriod(fromDate, toDate, DateUtils.isEqual(fromDate, loan.getDisbursementDate()));
            if (isDue) {
                if (loanCharge.isPenaltyCharge() && !loanCharge.isInstalmentFee()) {
                    penalties = penalties.add(loanCharge.amount());
                    loanCharges.add(loanCharge);
                } else if (!loanCharge.isInstalmentFee()) {
                    fee = fee.add(loanCharge.amount());
                    loanCharges.add(loanCharge);
                }
            } else if (loanCharge.isInstalmentFee()) {
                for (LoanInstallmentCharge installmentCharge : loanCharge.installmentCharges()) {
                    if (installments.contains(installmentCharge.getRepaymentInstallment().getInstallmentNumber())) {
                        fee = fee.add(installmentCharge.getAmount());
                        loanInstallmentCharges.add(installmentCharge);
                    }
                }
            }
        }

        feeDetails.put(Loan.FEE, fee);
        feeDetails.put(Loan.PENALTIES, penalties);
        feeDetails.put("loanCharges", loanCharges);
        feeDetails.put("loanInstallmentCharges", loanInstallmentCharges);
    }

    private void updateLoanChargesPaidBy(Loan loan, LoanTransaction accrual, Map<String, Object> feeDetails,
            LoanRepaymentScheduleInstallment installment) {
        @SuppressWarnings("unchecked")
        List<LoanCharge> loanCharges = (List<LoanCharge>) feeDetails.get("loanCharges");
        @SuppressWarnings("unchecked")
        List<LoanInstallmentCharge> loanInstallmentCharges = (List<LoanInstallmentCharge>) feeDetails.get("loanInstallmentCharges");
        if (loanCharges != null) {
            for (LoanCharge loanCharge : loanCharges) {
                Integer installmentNumber = null == installment ? null : installment.getInstallmentNumber();
                final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(accrual, loanCharge,
                        loanCharge.getAmount(loan.getCurrency()).getAmount(), installmentNumber);
                accrual.getLoanChargesPaid().add(loanChargePaidBy);
            }
        }
        if (loanInstallmentCharges != null) {
            for (LoanInstallmentCharge loanInstallmentCharge : loanInstallmentCharges) {
                Integer installmentNumber = null == loanInstallmentCharge.getInstallment() ? null
                        : loanInstallmentCharge.getInstallment().getInstallmentNumber();
                final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(accrual, loanInstallmentCharge.getLoanCharge(),
                        loanInstallmentCharge.getAmount(loan.getCurrency()).getAmount(), installmentNumber);
                accrual.getLoanChargesPaid().add(loanChargePaidBy);
            }
        }
    }

    private void reverseTransactionsAfter(final List<LoanTransaction> accrualTransactions, final LocalDate effectiveDate,
            final boolean addEvent) {
        for (LoanTransaction accrualTransaction : accrualTransactions) {
            if (!accrualTransaction.isReversed() && DateUtils.isAfter(accrualTransaction.getTransactionDate(), effectiveDate)) {
                reverseAccrual(accrualTransaction, addEvent);
            }
        }
    }

    private void reverseTransactionsOnOrAfter(final List<LoanTransaction> accrualTransactions, final LocalDate date) {
        for (LoanTransaction accrualTransaction : accrualTransactions) {
            if (!accrualTransaction.isReversed() && !DateUtils.isBefore(accrualTransaction.getTransactionDate(), date)) {
                reverseAccrual(accrualTransaction, false);
            }
        }
    }

    private void reverseAccrual(final LoanTransaction transaction, final boolean addEvent) {
        transaction.reverse();
        if (addEvent) {
            final LoanAdjustTransactionBusinessEvent.Data data = new LoanAdjustTransactionBusinessEvent.Data(transaction);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanAdjustTransactionBusinessEvent(data));
        }
    }

    private LocalDate getFinalAccrualTransactionDate(final Loan loan) {
        return switch (loan.getStatus()) {
            case CLOSED_OBLIGATIONS_MET -> loan.getClosedOnDate();
            case OVERPAID -> loan.getOverpaidOnDate();
            default -> throw new IllegalStateException("Unexpected value: " + loan.getStatus());
        };
    }

    public boolean isProgressiveAccrual(@NotNull Loan loan) {
        return loan.isProgressiveSchedule();
    }
}
