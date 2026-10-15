package com.aicontent.platform.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.issue.PeriodGuard.Kind;
import com.aicontent.platform.issue.PeriodGuard.Verdict;
import org.junit.jupiter.api.Test;

/** Texts are made up; they copy the shapes seen in real headlines. */
class PeriodGuardTest {

    private static Verdict verdict(String article, String candidate) {
        return PeriodGuard.compare(article, candidate).verdict();
    }

    @Test
    void differentQuartersOfTheSameCompanyConflict() {
        var r = PeriodGuard.compare("예시전자, 2026년 3분기 영업이익 8% 증가", "예시전자 2026년 2분기 영업이익 12% 감소");
        assertThat(r.verdict()).isEqualTo(Verdict.CONFLICT);
        assertThat(r.blocksAutoMerge()).isTrue();
        assertThat(r.reason()).contains("2026년 3분기", "2026년 2분기", "'2026년 3분기'");   // evidence is recorded
    }

    @Test
    void sameQuarterFollowUpIsConsistent() {
        assertThat(verdict("예시전자 2026년 3분기 실적 발표…반도체 흑자 전환", "예시전자, 2026년 3분기 영업이익 8% 증가"))
                .isEqualTo(Verdict.CONSISTENT);
    }

    @Test
    void comparisonPeriodIsNotATarget() {
        var e = PeriodGuard.extract("예시전자 3분기 실적, 2분기 대비 개선…작년 3분기보다는 부진");
        assertThat(e.targets()).extracting(PeriodGuard.Period::value).containsExactly(3);
        assertThat(e.comparisons()).extracting(PeriodGuard.Period::value).containsExactlyInAnyOrder(2, 3);
        // several periods in the text, but one target: compared normally, not sent to review for that alone
        assertThat(verdict("예시전자 2026년 3분기 실적, 2분기 대비 개선", "예시전자 2026년 3분기 영업이익 증가")).isEqualTo(Verdict.CONSISTENT);
        assertThat(verdict("예시전자 2026년 3분기 실적, 2분기 대비 개선", "예시전자 2026년 2분기 영업이익 감소")).isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void sameQuarterWithoutAYearIsNotAConflictButIsNotUsedAsEvidenceEither() {
        var r = PeriodGuard.compare("예시전자 3분기 호실적에 주가 5% 상승", "예시전자, 2026년 3분기 영업이익 8% 증가");
        assertThat(r.verdict()).isEqualTo(Verdict.CONSISTENT);
        assertThat(r.blocksAutoMerge()).isFalse();
        assertThat(r.reason()).contains("연도 미확인");
    }

    @Test
    void sameQuarterOfDifferentYearsConflicts() {
        assertThat(verdict("예시전자 2026년 3분기 영업이익", "예시전자 2025년 3분기 영업이익")).isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void monthlyStatisticsOfDifferentMonthsConflict() {
        assertThat(verdict("9월 취업자 25만명 증가…고용률 역대 최고", "8월 고용동향: 취업자 18만명 증가")).isEqualTo(Verdict.CONFLICT);
        assertThat(verdict("2026년 9월 소비자물가 2.1% 상승", "9월 소비자물가 2.1%…석 달 만에 2%대")).isEqualTo(Verdict.CONSISTENT);
    }

    @Test
    void eventDatesAndBareYearsAreNotTargetPeriods() {
        // a fire reported on two different days: no period topic, nothing to compare
        assertThat(verdict("10월 14일 가상시 공장 화재…원인 조사 착수", "10월 13일 밤 가상시 공장에서 불")).isEqualTo(Verdict.NOT_APPLICABLE);
        // a court ruling that mentions the year of the crime is not a period-type event
        assertThat(verdict("2022년 지방선거 공천헌금 1심 징역형", "공천헌금 의혹 의원 1심 선고 공판")).isEqualTo(Verdict.NOT_APPLICABLE);
        var dates = PeriodGuard.extract("수출 지표 발표는 10월 14일, 9월 말 기준");
        assertThat(dates.targets()).isEmpty();
    }

    @Test
    void unclearTargetGoesToReview() {
        // earnings with a period on one side only
        assertThat(verdict("예시전자 실적 발표 후 주가 급등", "예시전자 2026년 3분기 영업이익 8% 증가")).isEqualTo(Verdict.UNCLEAR);
        // two target periods left after removing comparisons
        assertThat(verdict("예시전자 2분기·3분기 영업이익 연속 증가", "예시전자 3분기 영업이익 증가")).isEqualTo(Verdict.UNCLEAR);
        // different units
        assertThat(verdict("예시전자 상반기 매출 10조", "예시전자 2분기 매출 5조")).isEqualTo(Verdict.UNCLEAR);
        assertThat(PeriodGuard.compare("예시전자 상반기 매출 10조", "예시전자 2분기 매출 5조").blocksAutoMerge()).isTrue();
    }

    @Test
    void cumulativeRangeAndFullYearAreTheirOwnPeriods() {
        var range = PeriodGuard.extract("예시전자 1~3분기 누적 매출 30조");
        assertThat(range.targets()).extracting(PeriodGuard.Period::kind).containsExactly(Kind.QUARTER_RANGE);
        assertThat(verdict("예시전자 1~3분기 누적 매출 30조", "예시전자 3분기 매출 10조")).isEqualTo(Verdict.UNCLEAR);
        assertThat(verdict("예시전자 2025년 연간 영업이익 사상 최대", "예시전자 2024년 연간 실적 발표")).isEqualTo(Verdict.CONFLICT);
        // "3분기(7~9월)": the months inside the quarter are not separate targets
        assertThat(PeriodGuard.extract("3분기(7~9월) 영업이익").targets()).hasSize(1);
    }

    @Test
    void halvesCompare() {
        assertThat(verdict("가상은행 2026년 하반기 순이익 감소", "가상은행 2026년 상반기 순이익 증가")).isEqualTo(Verdict.CONFLICT);
    }

    @Test
    void policyOverridesTheModelAndKeepsTheEvidence() {
        var raw = new DecisionPolicy.RawDecision(DecisionPolicy.Decision.SAME_ISSUE, 0.95, 1L, "같은 회사 실적");
        var outcome = DecisionPolicy.evaluate(raw, java.util.Set.of(1L), new DecisionPolicy.Thresholds(0.85, 0.70));
        var guarded = DecisionPolicy.applyPeriodGuard(outcome, "예시전자 2026년 3분기 영업이익", "예시전자 2026년 2분기 영업이익");
        assertThat(guarded.decision()).isEqualTo(DecisionPolicy.Decision.REVIEW);
        assertThat(guarded.method()).isEqualTo(DecisionPolicy.Method.GUARD_DOWNGRADE);
        assertThat(guarded.rawDecision()).isEqualTo(DecisionPolicy.Decision.SAME_ISSUE);
        assertThat(guarded.matchedIssueId()).isNull();
        assertThat(guarded.reason()).startsWith("기간 규칙: 대상 기간이 다름").contains("LLM 사유: 같은 회사 실적");

        var fire = DecisionPolicy.applyPeriodGuard(outcome, "가상시 공장 화재 원인 조사", "가상시 공장 화재 주민 대피");
        assertThat(fire).isEqualTo(outcome);                           // rule does not apply to ordinary events
    }
}
