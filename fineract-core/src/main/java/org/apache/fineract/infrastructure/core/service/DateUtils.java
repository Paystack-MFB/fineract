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
package org.apache.fineract.infrastructure.core.service;

import static java.time.temporal.ChronoUnit.DAYS;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.JsonParserHelper;

public final class DateUtils {

    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
    public static final String DEFAULT_DATETIME_FORMAT = DEFAULT_DATE_FORMAT + " HH:mm:ss";
    public static final DateTimeFormatter DEFAULT_DATE_FORMATTER = DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT);
    public static final DateTimeFormatter DEFAULT_DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DEFAULT_DATETIME_FORMAT);

    private DateUtils() {

    }

    // DateTime

    public static ZoneId getSystemZoneId() {
        return ZoneId.systemDefault();
    }

    public static ZoneId getDateTimeZoneOfTenant() {
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        return ZoneId.of(tenant.getTimezoneId());
    }

    public static LocalDate getLocalDateOfTenant() {
        return LocalDate.now(getDateTimeZoneOfTenant());
    }

    public static LocalDateTime getLocalDateTimeOfTenant() {
        return getLocalDateTimeOfTenant(null);
    }

    public static LocalDateTime getLocalDateTimeOfTenant(ChronoUnit truncate) {
        LocalDateTime now = LocalDateTime.now(getDateTimeZoneOfTenant());
        return truncate == null ? now : now.truncatedTo(truncate);
    }

    public static OffsetDateTime getOffsetDateTimeOfTenant() {
        return getOffsetDateTimeOfTenant(null);
    }

    public static OffsetDateTime getOffsetDateTimeOfTenant(ChronoUnit truncate) {
        OffsetDateTime now = OffsetDateTime.now(getDateTimeZoneOfTenant());
        return truncate == null ? now : now.truncatedTo(truncate);
    }

    @NotNull
    public static OffsetDateTime getOffsetDateTimeOfTenantFromLocalDate(@NotNull final LocalDate date) {
        return OffsetDateTime.of(date.atStartOfDay(), getOffsetDateTimeOfTenant().getOffset());
    }

    public static LocalDateTime getLocalDateTimeOfSystem() {
        return getLocalDateTimeOfSystem(null);
    }

    public static LocalDateTime getLocalDateTimeOfSystem(ChronoUnit truncate) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        return truncate == null ? now : now.truncatedTo(truncate);
    }

    public static LocalDateTime getAuditLocalDateTime() {
        return LocalDateTime.now(ZoneId.of("UTC"));
    }

    public static OffsetDateTime getAuditOffsetDateTime() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static int compare(LocalDateTime first, LocalDateTime second) {
        return compare(first, second, null);
    }

    public static int compare(LocalDateTime first, LocalDateTime second, ChronoUnit truncate) {
        if (first == null) {
            return second == null ? 0 : -1;
        }
        if (second == null) {
            return 1;
        }
        return truncate == null ? first.compareTo(second) : first.truncatedTo(truncate).compareTo(second.truncatedTo(truncate));
    }

    public static boolean isEqual(LocalDateTime first, LocalDateTime second) {
        return isEqual(first, second, null);
    }

    public static boolean isEqual(LocalDateTime first, LocalDateTime second, ChronoUnit truncate) {
        return compare(first, second, truncate) == 0;
    }

    public static boolean isEqualTenantDateTime(LocalDateTime dateTime, ChronoUnit truncate) {
        return isEqual(dateTime, getLocalDateTimeOfTenant(), truncate);
    }

    public static boolean isEqualSystemDateTime(LocalDateTime dateTime, ChronoUnit truncate) {
        return isEqual(dateTime, getLocalDateTimeOfSystem(), truncate);
    }

    public static boolean isBefore(LocalDateTime first, LocalDateTime second) {
        return isBefore(first, second, null);
    }

    public static boolean isBefore(LocalDateTime first, LocalDateTime second, ChronoUnit truncate) {
        return compare(first, second, truncate) < 0;
    }

    public static boolean isAfter(LocalDateTime first, LocalDateTime second) {
        return isAfter(first, second, null);
    }

    public static boolean isAfter(LocalDateTime first, LocalDateTime second, ChronoUnit truncate) {
        return compare(first, second, truncate) > 0;
    }

    public static boolean isAfterTenantDateTime(LocalDateTime dateTime) {
        return isAfterTenantDateTime(dateTime, null);
    }

    public static boolean isAfterTenantDateTime(LocalDateTime dateTime, ChronoUnit truncate) {
        return isAfter(dateTime, getLocalDateTimeOfTenant(), truncate);
    }

    public static boolean isAfterSystemDateTime(LocalDateTime dateTime, ChronoUnit truncate) {
        return isAfter(dateTime, getLocalDateTimeOfSystem(), truncate);
    }

    public static int compare(OffsetDateTime first, OffsetDateTime second) {
        return compare(first, second, null);
    }

    public static int compare(OffsetDateTime first, OffsetDateTime second, ChronoUnit truncate) {
        return compare(first, second, truncate, true);
    }

    public static int compareWithNullsLast(OffsetDateTime first, OffsetDateTime second) {
        return compare(first, second, null, false);
    }

    public static int compareWithNullsLast(@NotNull Optional<OffsetDateTime> first, @NotNull Optional<OffsetDateTime> second) {
        return compareWithNullsLast(first.orElse(null), second.orElse(null));
    }

    public static int compare(OffsetDateTime first, OffsetDateTime second, ChronoUnit truncate, boolean nullFirst) {
        if (first == null) {
            return second == null ? 0 : (nullFirst ? -1 : 1);
        }
        if (second == null) {
            return nullFirst ? 1 : -1;
        }
        first = first.withOffsetSameInstant(ZoneOffset.UTC);
        second = second.withOffsetSameInstant(ZoneOffset.UTC);
        return truncate == null ? first.compareTo(second) : first.truncatedTo(truncate).compareTo(second.truncatedTo(truncate));
    }

    public static boolean isEqual(OffsetDateTime first, OffsetDateTime second) {
        return isEqual(first, second, null);
    }

    public static boolean isEqual(OffsetDateTime first, OffsetDateTime second, ChronoUnit truncate) {
        return compare(first, second, truncate) == 0;
    }

    public static boolean isEqualTenantDateTime(OffsetDateTime dateTime, ChronoUnit truncate) {
        return isEqual(dateTime, getOffsetDateTimeOfTenant(), truncate);
    }

    public static boolean isBefore(OffsetDateTime first, OffsetDateTime second) {
        return isBefore(first, second, null);
    }

    public static boolean isBefore(OffsetDateTime first, OffsetDateTime second, ChronoUnit truncate) {
        return compare(first, second, truncate) < 0;
    }

    public static boolean isBeforeTenantDateTime(OffsetDateTime dateTime, ChronoUnit truncate) {
        return isBefore(dateTime, getOffsetDateTimeOfTenant(), truncate);
    }

    public static boolean isAfter(OffsetDateTime first, OffsetDateTime second) {
        return isAfter(first, second, null);
    }

    public static boolean isAfter(OffsetDateTime first, OffsetDateTime second, ChronoUnit truncate) {
        return compare(first, second, truncate) > 0;
    }

    public static boolean isAfterTenantDateTime(OffsetDateTime dateTime, ChronoUnit truncate) {
        return isAfter(dateTime, getOffsetDateTimeOfTenant(), truncate);
    }

    // Date

    public static LocalDate getBusinessLocalDate() {
        return ThreadLocalContextUtil.getBusinessDate();
    }

    public static boolean isEqualTenantDate(LocalDate date) {
        return isEqual(date, getLocalDateOfTenant());
    }

    public static boolean isBeforeTenantDate(LocalDate date) {
        return isBefore(date, getLocalDateOfTenant());
    }

    public static boolean isEqualBusinessDate(LocalDate date) {
        return isEqual(date, getBusinessLocalDate());
    }

    public static boolean isBeforeBusinessDate(LocalDate date) {
        return isBefore(date, getBusinessLocalDate());
    }

    public static boolean isAfterBusinessDate(LocalDate date) {
        return isAfter(date, getBusinessLocalDate());
    }

    public static boolean isDateInTheFuture(final LocalDate localDate) {
        return isAfterBusinessDate(localDate);
    }

    public static boolean isDateInRangeFromInclusiveToExclusive(final LocalDate fromInclusive, final LocalDate upToNotInclusive,
            final LocalDate target) {
        return (DateUtils.isEqual(target, fromInclusive) || DateUtils.isAfter(target, fromInclusive))
                && DateUtils.isBefore(target, upToNotInclusive);
    }

    public static int compare(LocalDate first, LocalDate second) {
        return compare(first, second, true);
    }

    /**
     * Comparing dates. Null will be considered as last elements
     *
     * @param first
     * @param second
     * @return
     */
    public static int compareWithNullsLast(LocalDate first, LocalDate second) {
        return compare(first, second, false);
    }

    public static int compare(LocalDate first, LocalDate second, boolean nullFirst) {
        return first == null ? (second == null ? 0 : (nullFirst ? -1 : 1))
                : (second == null ? (nullFirst ? 1 : -1) : first.compareTo(second));
    }

    public static boolean isEqual(LocalDate first, LocalDate second) {
        return first == null ? second == null : (second != null && first.isEqual(second));
    }

    public static boolean isBefore(LocalDate first, LocalDate second) {
        return second != null && (first == null || first.isBefore(second));
    }

    public static boolean isAfter(LocalDate first, LocalDate second) {
        return first != null && (second == null || first.isAfter(second));
    }

    public static boolean isAfterInclusive(LocalDate first, LocalDate second) {
        return isAfter(first, second) || isEqual(first, second);
    }

    public static long getDifference(LocalDate first, LocalDate second, @NotNull ChronoUnit unit) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("Dates must not be null to get difference");
        }
        return unit.between(first, second);
    }

    public static int getExactDifference(LocalDate first, LocalDate second, @NotNull ChronoUnit unit) {
        return Math.toIntExact(getDifference(first, second, unit));
    }

    public static long getDifferenceInDays(LocalDate first, LocalDate second) {
        return getDifference(first, second, DAYS);
    }

    public static int getExactDifferenceInDays(LocalDate first, LocalDate second) {
        return getExactDifference(first, second, DAYS);
    }

    public static LocalDate minusDays(LocalDate first, int days) {
        return first == null ? null : first.minusDays(days);
    }

    // Parse, format

    public static LocalDate parseLocalDate(String stringDate) {
        return parseLocalDate(stringDate, null);
    }

    public static LocalDate parseLocalDate(String stringDate, String format) {
        return parseLocalDate(stringDate, format, null);
    }

    public static LocalDate parseLocalDate(String stringDate, String format, Locale locale) {
        if (stringDate == null) {
            return null;
        }
        DateTimeFormatter formatter = getDateFormatter(format, locale);
        try {
            return LocalDate.parse(stringDate, formatter);
        } catch (final DateTimeParseException e) {
            final List<ApiParameterError> errors = List.of(ApiParameterError.parameterError("validation.msg.invalid.date.pattern",
                    "The parameter date (" + stringDate + ") format is invalid", "date", stringDate));
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.", errors, e);
        }
    }

    public static LocalDate toLocalDate(String local, String date, String dateFormat) {
        Locale locale = Locale.forLanguageTag(local);
        String patternISO = dateFormat.replace("y", "u");
        DateTimeFormatter formatter = new DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern(patternISO)
                .optionalStart().appendPattern(" HH:mm:ss").optionalEnd().parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0).parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0).toFormatter(locale)
                .withResolverStyle(ResolverStyle.STRICT);
        return LocalDateTime.parse(date, formatter).toLocalDate();
    }

    public static String format(LocalDate date) {
        return format(date, null);
    }

    public static String format(LocalDate date, String format) {
        return format(date, format, null);
    }

    public static String format(LocalDate date, String format, Locale locale) {
        return date == null ? null : date.format(getDateFormatter(format, locale));
    }

    public static String format(LocalDateTime dateTime) {
        return format(dateTime, null);
    }

    public static String format(LocalDateTime dateTime, String format) {
        return format(dateTime, format, null);
    }

    public static String format(LocalDateTime dateTime, String format, Locale locale) {
        return dateTime == null ? null : dateTime.format(getDateTimeFormatter(format, locale));
    }

    /**
     * Checks if a specific date falls within a given range (inclusive).
     *
     * @param targetDate
     *            the date to be checked
     * @param fromDate
     *            the start date of the range
     * @param toDate
     *            the end date of the range
     * @return true if targetDate is within range or equal to start/end dates, otherwise false
     */
    public static boolean isDateWithinRange(LocalDate targetDate, LocalDate fromDate, LocalDate toDate) {
        if (targetDate == null || fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Dates must not be null");
        }
        return isDateInRangeInclusive(targetDate, fromDate, toDate);
    }

    public static boolean isDateInRangeInclusive(LocalDate targetDate, LocalDate fromDate, LocalDate toDate) {
        return fromDate != null && !DateUtils.isBefore(targetDate, fromDate) && !DateUtils.isAfter(targetDate, toDate);
    }

    public static boolean isDateInRangeExclusive(LocalDate targetDate, LocalDate fromDate, LocalDate toDate) {
        return fromDate != null && DateUtils.isAfter(targetDate, fromDate) && DateUtils.isBefore(targetDate, toDate);
    }

    public static boolean isDateInRangeFromExclusiveToInclusive(LocalDate targetDate, LocalDate fromDate, LocalDate toDate) {
        return fromDate != null && DateUtils.isAfter(targetDate, fromDate) && !DateUtils.isAfter(targetDate, toDate);
    }

    @NotNull
    private static DateTimeFormatter getDateFormatter(String format, Locale locale) {
        DateTimeFormatter formatter = DEFAULT_DATE_FORMATTER;
        if (format != null || locale != null) {
            if (format == null) {
                format = DEFAULT_DATE_FORMAT;
            }
            formatter = locale == null ? DateTimeFormatter.ofPattern(format) : DateTimeFormatter.ofPattern(format, locale);
        }
        return formatter;
    }

    @NotNull
    private static DateTimeFormatter getDateTimeFormatter(String format, Locale locale) {
        DateTimeFormatter formatter = DEFAULT_DATETIME_FORMATTER;
        if (format != null || locale != null) {
            if (format == null) {
                format = DEFAULT_DATETIME_FORMAT;
            }
            formatter = locale == null ? DateTimeFormatter.ofPattern(format) : DateTimeFormatter.ofPattern(format, locale);
        }
        return formatter;
    }

    public static LocalDateTime convertDateTimeStringToLocalDateTime(String dateTimeStr, String dateFormat, String localeStr,
            LocalTime fallbackTime) {
        if (dateTimeStr == null || dateTimeStr.isBlank()) {
            return null;
        }
        final Locale locale = localeStr == null ? null : JsonParserHelper.localeFromString(localeStr);
        DateTimeFormatter formatter = getDateFormatter(dateFormat, locale);
        TemporalAccessor parsed = formatter.parse(dateTimeStr);

        boolean hasTime = parsed.isSupported(ChronoField.HOUR_OF_DAY) && parsed.isSupported(ChronoField.MINUTE_OF_HOUR);

        try {
            if (hasTime) {
                return LocalDateTime.from(parsed);
            } else {
                LocalDate date = LocalDate.from(parsed);
                return LocalDateTime.of(date, fallbackTime);
            }
        } catch (final DateTimeParseException e) {
            final List<ApiParameterError> errors = List.of(ApiParameterError.parameterError("validation.msg.invalid.date.pattern",
                    "The parameter date (" + dateTimeStr + ") format is invalid", "date", dateTimeStr));
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.", errors, e);
        }
    }
}
