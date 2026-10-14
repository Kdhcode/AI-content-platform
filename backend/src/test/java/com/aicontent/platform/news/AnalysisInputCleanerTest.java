package com.aicontent.platform.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Sample texts are made up for the test; they copy only the shape of real feed notices, not real articles. */
class AnalysisInputCleanerTest {

    private static final String HEADER = "ⓒ 가상방송 & 가상방송 i / RSS 피드는 개인 리더 이용 목적으로 허용 되어 있습니다. "
            + "피드를 이용한 게시 등의 무단 복제는 금지 되어 있습니다. ▶ 가상방송 뉴스 앱 다운로드 "
            + "▶ 뉴스에 지식을 담다 - 가상프리미엄 앱 다운로드 ⓒ 가상방송 & 가상방송 i : 무단복제 및 재배포 금지 ";
    private static final String FOOTER = " ▶ 이 기사의 전체 내용 확인하기 ▶ 가상방송 뉴스 앱 다운로드 ⓒ 가상방송 & 가상방송 i : 무단복제 및 재배포 금지";
    private static final String BODY = "가상시 A동 상가에서 14일 오전 화재가 발생해 주민 30명이 대피했다. "
            + "소방당국은 \"인명 피해는 없다\"고 밝혔다. [김가상/가상시 소방서장 : 진화는 오후 1시에 끝났습니다.] (영상편집 : 이가상)";

    @Test
    void removesFeedNoticesAndLinkLabelsButKeepsEveryBodySentence() {
        var cleaned = AnalysisInputCleaner.clean(HEADER + BODY + FOOTER);

        assertThat(cleaned.text()).isEqualTo(BODY);
        assertThat(cleaned.text()).doesNotContain("ⓒ", "RSS", "다운로드", "무단", "▶");
        assertThat(cleaned.removed()).isNotEmpty();
    }

    @Test
    void videoLabelInTheMiddleIsRemovedAndTheTextAroundItStays() {
        var cleaned = AnalysisInputCleaner.clean("첫 문장입니다. ▶ 영상 시청 둘째 문장입니다.");
        assertThat(cleaned.text()).isEqualTo("첫 문장입니다. 둘째 문장입니다.");
    }

    @Test
    void trailingBylineWithEmailIsRemoved() {
        var cleaned = AnalysisInputCleaner.clean("[가상=통신] 홍길동 기자 = 가상시가 새 정책을 발표했다. ◎공감 가상통신 reporter01@example.com");
        assertThat(cleaned.text()).isEqualTo("[가상=통신] 홍길동 기자 = 가상시가 새 정책을 발표했다.");
    }

    @Test
    void emailInsideASentenceLosesOnlyTheAddress() {
        var cleaned = AnalysisInputCleaner.clean("제보는 tips@example.com 으로 받는다고 가상시는 밝혔다. 다음 문장도 남는다.");
        assertThat(cleaned.text()).isEqualTo("제보는 으로 받는다고 가상시는 밝혔다. 다음 문장도 남는다.");
    }

    @Test
    void anArticleAboutPiracyWithoutACopyrightMarkIsUntouched() {
        String article = "문화부는 영상 무단 복제 금지 규정을 어긴 업체 3곳을 적발했다. 업체들은 무단 전재 금지 안내를 무시했다고 밝혔다.";
        var cleaned = AnalysisInputCleaner.clean(article);
        assertThat(cleaned.text()).isEqualTo(article);
        assertThat(cleaned.removed()).isEmpty();
    }

    @Test
    void ordinaryArrowsAndBulletsInTheBodyStay() {
        String article = "선정 대상자는 ▷가상대 김교수 ▷가상일보 이부장이다. ▶ 주요 내용: 지원금 1,000만 원 지급 결정.";
        assertThat(AnalysisInputCleaner.clean(article).text()).isEqualTo(article);
    }

    @Test
    void englishCopyrightLineIsRemoved() {
        var cleaned = AnalysisInputCleaner.clean("The ministry announced the plan on Monday. © 2026 Example News. All rights reserved.");
        assertThat(cleaned.text()).isEqualTo("The ministry announced the plan on Monday.");
    }

    @Test
    void textWithoutBoilerplateIsReturnedAsIs() {
        for (String text : new String[] {"", "   ", "Pope Leo had successful surgery, the Vatican said.", "한 문장."}) {
            var cleaned = AnalysisInputCleaner.clean(text);
            assertThat(cleaned.text()).isEqualTo(text.trim());
            assertThat(cleaned.removed()).isEmpty();
        }
        assertThat(AnalysisInputCleaner.clean(null).text()).isEmpty();
    }

    @Test
    void promptVariablesSendTheCleanedTextAndFallBackWhenNothingIsLeft() {
        Map<String, Object> vars = ArticleAnalysisService.promptVariables("제목", "가상방송", null, null, HEADER + BODY + FOOTER, 8000);
        assertThat(vars.get("text")).isEqualTo(BODY);

        Map<String, Object> onlyNotice = ArticleAnalysisService.promptVariables("제목", "가상방송", null, null, HEADER + FOOTER, 8000);
        assertThat(onlyNotice.get("text")).isEqualTo("(본문 없음 - 제목만 사용)");
    }

    @Test
    void truncationAppliesToTheCleanedText() {
        Map<String, Object> vars = ArticleAnalysisService.promptVariables("제목", "가상방송", null, null, HEADER + BODY, 10);
        assertThat(vars.get("text")).isEqualTo(BODY.substring(0, 10));
    }
}
