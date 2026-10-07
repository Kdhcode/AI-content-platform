package com.aicontent.platform.news;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Lenient parser for the publication time reported by a source. A value that cannot be parsed yields
 * {@code Optional.empty()}; the article is still stored (raw string kept) because losing a real article
 * over a date format is worse than a missing timestamp.
 */
public final class PublishedAtParser {

    private PublishedAtParser() {}

    private static final List<DateTimeFormatter> ZONED = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.RFC_1123_DATE_TIME);

    private static final List<DateTimeFormatter> LOCAL = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

    /** @param defaultZone applied to values that carry no zone information */
    public static Optional<OffsetDateTime> parse(String raw, ZoneId defaultZone) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();

        if (s.chars().allMatch(Character::isDigit) && (s.length() == 10 || s.length() == 13)) {
            long value = Long.parseLong(s);
            Instant instant = s.length() == 13 ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
            return Optional.of(instant.atOffset(ZoneOffset.UTC));
        }
        try {
            return Optional.of(Instant.parse(s).atOffset(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        for (DateTimeFormatter f : ZONED) {
            try {
                return Optional.of(ZonedDateTime.parse(s, f).toOffsetDateTime());
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        for (DateTimeFormatter f : LOCAL) {
            try {
                return Optional.of(LocalDateTime.parse(s, f).atZone(defaultZone).toOffsetDateTime());
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        try {
            return Optional.of(LocalDate.parse(s).atStartOfDay(defaultZone).toOffsetDateTime());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
