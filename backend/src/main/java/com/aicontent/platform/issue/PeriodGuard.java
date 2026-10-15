package com.aicontent.platform.issue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code check of the <b>target period</b> before an automatic SAME_ISSUE: two earnings reports (or two monthly
 * statistics releases) of the same actor for different periods are different events, however similar the wording.
 * The model's own comparison cannot be trusted for this (it marked "2분기 vs 3분기" as NOT_APPLICABLE), so the code
 * extracts the periods itself and its verdict overrides the model.
 *
 * <ul>
 *   <li>Applies only to period-type events: the text must name an earnings/statistics topic <i>and</i> a period
 *       (quarter, half, month, full year). Event dates ("10월 14일") and bare years ("2026년 지방선거") are not periods.</li>
 *   <li>Comparison periods ("2분기 대비", "작년 3분기보다", "전년 동기") are separated from the target period, so
 *       "3분기 실적, 2분기 대비 개선" has one target (3분기) and is not ambiguous for that reason alone.</li>
 *   <li>{@link Verdict#CONFLICT}: both sides have one clear target and they differ -> block the automatic merge.</li>
 *   <li>{@link Verdict#UNCLEAR}: the rule applies but a target period is missing, several targets remain, or the
 *       units differ (quarter vs month) -> block too (REVIEW).</li>
 *   <li>The same quarter without a year on one side is <b>not</b> a conflict, but it is never used as evidence that
 *       the events are the same either: the guard only blocks, it never confirms.</li>
 * </ul>
 */
public final class PeriodGuard {

    public enum Kind { QUARTER, QUARTER_RANGE, HALF, MONTH, YEAR }

    public enum Verdict { NOT_APPLICABLE, CONSISTENT, CONFLICT, UNCLEAR }

    /** {@code value}: quarter 1-4, half 1-2, month 1-12, year 0; {@code toValue} only for ranges ("1~3분기"). */
    public record Period(Kind kind, Integer year, int value, int toValue, String evidence) {
        String label() {
            String y = year == null ? "연도 미상 " : year + "년 ";
            return switch (kind) {
                case QUARTER -> y + value + "분기";
                case QUARTER_RANGE -> y + value + "~" + toValue + "분기 누적";
                case HALF -> y + (value == 1 ? "상반기" : "하반기");
                case MONTH -> y + value + "월";
                case YEAR -> (year == null ? "연도 미상" : year + "년") + " 연간";
            };
        }

        boolean sameSlot(Period o) {
            return kind == o.kind && value == o.value && toValue == o.toValue;
        }
    }

    public record Extraction(boolean periodTopic, String topicEvidence, List<Period> targets, List<Period> comparisons) {
        boolean hasAnyPeriod() {
            return !targets.isEmpty() || !comparisons.isEmpty();
        }
    }

    public record Result(Verdict verdict, String reason) {
        public boolean blocksAutoMerge() {
            return verdict == Verdict.CONFLICT || verdict == Verdict.UNCLEAR;
        }
    }

    /** Earnings and statistics topics whose identity depends on the period they cover. */
    private static final Pattern TOPIC = Pattern.compile(
            "실적|영업이익|영업손실|순이익|순손실|당기순|매출|어닝|고용동향|취업자|실업률|고용률|물가|수출|수입|무역수지|경상수지"
                    + "|산업활동|생산지수|성장률|국내총생산|GDP|통계|지표|판매량|출생아|인구동향|가계부채|거래량|거래액|카드승인액");

    private static final String YEAR = "(?:(\\d{4})\\s*년\\s*)?";
    private static final Pattern QUARTER_RANGE = Pattern.compile(YEAR + "([1-4])\\s*[~∼\\-]\\s*([1-4])\\s*분기");
    private static final Pattern QUARTER = Pattern.compile(YEAR + "(?<![~∼\\-\\d])([1-4])\\s*분기");
    private static final Pattern HALF = Pattern.compile(YEAR + "(상|하)\\s*반기");
    private static final Pattern YEAR_TOTAL = Pattern.compile("(\\d{4})\\s*년\\s*(?:연간|한\\s*해|전체\\s*실적|결산)");
    /** A month that is not a date ("10월 14일") nor a point inside a month ("9월 말", "9월 중순"). */
    private static final Pattern MONTH = Pattern.compile(YEAR + "(?<!\\d)(1[0-2]|[1-9])\\s*월(?!\\s*\\d{1,2}\\s*일)(?!\\s*(?:말|초|중순|초순|하순|중에|께))");

    /** After a period: "대비", "보다", "에 비해", "와/과 비교" (optionally after a closing bracket or particle). */
    private static final Pattern COMPARISON_AFTER = Pattern.compile("^\\s*(?:\\)|\\])?\\s*(?:실적|기록|수치|치)?\\s*(?:대비|보다|에\\s*비해|와\\s*비교|과\\s*비교|比)");
    /** Before a period: "작년/지난해/전년/직전/전분기 ..." marks the prior period used for comparison. */
    private static final Pattern COMPARISON_BEFORE = Pattern.compile("(?:작년|지난해|전년|전년도|직전|전\\s*분기|지난)\\s*$");

    private PeriodGuard() {
    }

    public static Extraction extract(String text) {
        String t = text == null ? "" : text;
        Matcher topic = TOPIC.matcher(t);
        boolean periodTopic = topic.find();
        String topicEvidence = periodTopic ? topic.group() : null;

        List<Period> found = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        collect(t, QUARTER_RANGE, Kind.QUARTER_RANGE, found, spans);
        collect(t, QUARTER, Kind.QUARTER, found, spans);
        collect(t, HALF, Kind.HALF, found, spans);
        collect(t, YEAR_TOTAL, Kind.YEAR, found, spans);
        boolean quarterOrHalf = found.stream().anyMatch(p -> p.kind() != Kind.YEAR);
        if (!quarterOrHalf) {
            // months are periods only for statistics without a quarter/half ("9월 고용동향"); "3분기(7~9월)" stays a quarter
            collect(t, MONTH, Kind.MONTH, found, spans);
        }

        List<Period> targets = new ArrayList<>();
        List<Period> comparisons = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            int[] span = spans.get(i);
            String before = t.substring(Math.max(0, span[0] - 6), span[0]);
            String after = t.substring(span[1], Math.min(t.length(), span[1] + 12));
            boolean comparison = COMPARISON_AFTER.matcher(after).find() || COMPARISON_BEFORE.matcher(before).find();
            (comparison ? comparisons : targets).add(found.get(i));
        }
        return new Extraction(periodTopic, topicEvidence, merge(targets), merge(comparisons));
    }

    private static void collect(String t, Pattern p, Kind kind, List<Period> found, List<int[]> spans) {
        Matcher m = p.matcher(t);
        while (m.find()) {
            int start = m.start(), end = m.end();
            boolean overlaps = spans.stream().anyMatch(s -> start < s[1] && end > s[0]);
            if (overlaps) {
                continue;
            }
            Period period = switch (kind) {
                case QUARTER_RANGE -> new Period(kind, year(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group().trim());
                case QUARTER -> new Period(kind, year(m.group(1)), Integer.parseInt(m.group(2)), 0, m.group().trim());
                case HALF -> new Period(kind, year(m.group(1)), "상".equals(m.group(2)) ? 1 : 2, 0, m.group().trim());
                case YEAR -> new Period(kind, year(m.group(1)), 0, 0, m.group().trim());
                case MONTH -> new Period(kind, year(m.group(1)), Integer.parseInt(m.group(2)), 0, m.group().trim());
            };
            found.add(period);
            spans.add(new int[] {start, end});
        }
    }

    private static Integer year(String s) {
        return s == null ? null : Integer.parseInt(s);
    }

    /** One entry per period slot; a slot seen with and without a year keeps the year; two different years stay apart. */
    private static List<Period> merge(List<Period> periods) {
        Map<String, Period> bySlot = new LinkedHashMap<>();
        List<Period> extraYears = new ArrayList<>();
        for (Period p : periods) {
            String key = p.kind() + ":" + p.value() + ":" + p.toValue();
            Period seen = bySlot.get(key);
            if (seen == null) {
                bySlot.put(key, p);
            } else if (seen.year() == null && p.year() != null) {
                bySlot.put(key, p);
            } else if (seen.year() != null && p.year() != null && !seen.year().equals(p.year())
                    && extraYears.stream().noneMatch(x -> x.sameSlot(p) && Objects.equals(x.year(), p.year()))) {
                extraYears.add(p);
            }
        }
        List<Period> out = new ArrayList<>(bySlot.values());
        out.addAll(extraYears);
        return out;
    }

    /** Compares the article with the candidate issue the model wants to merge it into. */
    public static Result compare(String articleText, String candidateText) {
        Extraction a = extract(articleText);
        Extraction c = extract(candidateText);
        boolean applies = (a.periodTopic() && a.hasAnyPeriod()) || (c.periodTopic() && c.hasAnyPeriod());
        if (!applies) {
            return new Result(Verdict.NOT_APPLICABLE, null);
        }
        String evidence = "기사 대상 기간=" + describe(a) + ", 후보 대상 기간=" + describe(c);
        if (a.targets().isEmpty() || c.targets().isEmpty()) {
            return new Result(Verdict.UNCLEAR, "기간 규칙: 대상 기간을 한쪽에서 찾지 못함 → 검수 (" + evidence + ")");
        }
        if (a.targets().size() > 1 || c.targets().size() > 1) {
            return new Result(Verdict.UNCLEAR, "기간 규칙: 대상 기간이 여러 개 → 검수 (" + evidence + ")");
        }
        Period pa = a.targets().get(0);
        Period pc = c.targets().get(0);
        if (pa.kind() != pc.kind()) {
            return new Result(Verdict.UNCLEAR, "기간 규칙: 기간 단위가 다름 → 검수 (" + evidence + ")");
        }
        if (!pa.sameSlot(pc) || (pa.year() != null && pc.year() != null && !pa.year().equals(pc.year()))) {
            return new Result(Verdict.CONFLICT, "기간 규칙: 대상 기간이 다름(" + pa.label() + " ≠ " + pc.label() + ") → 자동 병합 차단, 검수 (" + evidence + ")");
        }
        String note = pa.year() == null || pc.year() == null ? " — 연도 미확인이라 같은 사건의 근거로 쓰지 않음" : "";
        return new Result(Verdict.CONSISTENT, "기간 규칙: 대상 기간 일치(" + pa.label() + " / " + pc.label() + ")" + note);
    }

    private static String describe(Extraction e) {
        if (e.targets().isEmpty() && e.comparisons().isEmpty()) {
            return "없음";
        }
        StringBuilder sb = new StringBuilder();
        for (Period p : e.targets()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(p.label()).append(" ['").append(p.evidence()).append("']");
        }
        if (e.targets().isEmpty()) {
            sb.append("없음");
        }
        if (!e.comparisons().isEmpty()) {
            sb.append(" / 비교 기간 ");
            for (int i = 0; i < e.comparisons().size(); i++) {
                Period p = e.comparisons().get(i);
                sb.append(i == 0 ? "" : ", ").append(p.label()).append(" ['").append(p.evidence()).append("']");
            }
        }
        return sb.toString();
    }
}
