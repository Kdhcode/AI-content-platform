# AI 품질 보강과 실제 OpenAI 검증 (2026-10-14)

기준: `60b89f6`의 다매체 실제 검증(기사 35건, DB `aicontent_openai_20261014_2`)에서 나온 문제를 고치고 다시 검증했다.
작업 브랜치 `claude/ai-quality`(`main` 기준). 실제 API 호출은 이번 작업 전체에서 **66회**(상한 100회).

## 1. 카테고리 분류

**원인.** `article-analysis/v1` 프롬프트는 "허용된 값 중 하나만 고른다"는 규칙만 있고 값별 정의가 없었다. 허용 값 목록은
스키마로 전달되지만 기준이 없어, 모델이 해외 기사·문화 기사를 `OTHER`로, 사건 기사를 `LIFE`로 보내는 경우가 많았다.
`60b89f6`에서 허용 목록 밖 값을 내 스키마 검증에 실패한 1회도 같은 원인으로 본다.

**수정.** `prompts/article-analysis/v2`를 추가하고 기본값을 v2로 바꿨다(`app.prompts.article-analysis-version`).
v1 폴더는 그대로 둔다(버전 폴더 불변 규칙). v2는 v1과 **스키마·사용자 템플릿이 같고** 시스템 지시만 다르다.
- 9개 카테고리마다 정의를 적고, 언론사나 소스 제공 카테고리가 아니라 사건 내용으로 판단하게 했다.
- 해외 사건·외국 정치는 `WORLD`, 한국의 사건·사고·범죄·재판은 `SOCIETY`, `LIFE`는 사건이 아닌 생활 정보로 한정, `OTHER`는 마지막 선택.
- 특정 기사·언론사 이름은 넣지 않았다. `PromptFilesTest`가 스키마의 모든 값에 정의가 있는지 검사한다.

**검증.** `60b89f6`의 실제 기사 중 v1이 틀린 14건과 맞은 6건을 골라, 기대 카테고리를 **실행 전에** 적어 두고 v2로 다시 분석했다.
v1 결과는 어제 저장된 값이다.

| | v1 (어제) | v2 1차 | v2 2차 (최종) |
|---|---|---|---|
| v1 오분류 14건 | 0/14 | 11/14 | **13/14** |
| v1 정상 6건 | 6/6 | 6/6 | **6/6** |
| 합계 | 6/20 | 17/20 | **19/20** |

- 1차에서 틀린 3건: 뉴욕 센트럴파크 마차 폐지(→ POLITICS), 영국 MI5 경고(→ POLITICS), 은평구 금성당제(→ OTHER).
  외국 정치를 `POLITICS`로 보낸 2건 때문에 `POLITICS` 정의에 "외국의 정치·의회·지방정부·정보기관 소식은 WORLD"를 덧붙이고,
  `CULTURE`에 "제례"를 넣어 2차를 돌렸다.
- 2차에도 남은 오분류: **은평구 금성당제 → OTHER**(기대 CULTURE 또는 SOCIETY). 고치지 않았다.
- **한계:** 같은 20건으로 프롬프트를 고치고 다시 쟀으므로 과적합 가능성이 있다. 새 기사로 확인하지 않았다.
  v2 실행에는 입력 정리(2절)도 함께 적용돼, 20건 중 SBS 1건·뉴시스 3건은 두 변경이 겹친다. 카테고리 변화의 원인을 완전히 분리하지는 못했다.

## 2. RSS 분석 입력 정리

**문제.** SBS RSS 본문 앞뒤에 이용 조건·앱 안내 문구가 붙어 AI 입력에 들어갔다. 뉴시스는 끝에 "◎슬로건 + 기자 이메일"이 붙는다.

**수정.** `news/AnalysisInputCleaner`를 추가하고 `ArticleAnalysisService.promptVariables`에서 **AI에 보내는 사본만** 정리한다.
`news_article.analysis_text`에 저장된 원문(이용 조건 문구 포함)은 바꾸지 않는다.
- 지우는 것: 저작권 표시(ⓒ/©)가 붙은 재배포 금지 문구, 피드 이용 조건 문장, "▶ … 다운로드/확인하기/영상 시청/바로가기/구독" 같은 짧은 링크 문구, 이메일이 든 끝 서명, 그 밖의 이메일 주소.
- 언론사 이름 규칙은 없다. 재배포 금지 문구는 ⓒ/© 표시가 있거나 피드 자체를 말할 때만 지워, 불법 복제를 보도하는 기사 문장은 남는다(테스트로 확인).
- 정리 후 본문이 비면 기존과 같이 "(본문 없음 - 제목만 사용)"을 보낸다. 길이 제한(8,000자)은 정리 후 적용한다.
- **입력 정리는 콘텐츠 이용 허가와 별개다.** 정리했다고 해서 SBS 등 각 사의 RSS 이용 조건이 해소되지 않는다(OPEN_ITEMS #1).

**검증.** 실제 기사 35건에 정리기를 적용(`CleanerRealDataReport`, AI 호출 없음). 15건이 바뀌었다(SBS 10, 뉴시스 5).
지워진 조각을 모두 사람이 확인했고 전부 이용 조건·앱 안내·링크 문구·서명이었다. 기사 문장이 지워진 경우는 없었다.
나머지 20건(연합뉴스·동아·경향·BBC)은 바뀌지 않았다. 정리 후 남은 단어가 모두 원문에 있는지도 테스트가 확인한다.

## 3. AI 비용 기록

- 단가 확인: 2026-10-14, [OpenAI API Pricing](https://developers.openai.com/api/docs/pricing) (Standard, 100만 토큰당 USD).
  `gpt-4o-mini` 입력 $0.15 · 캐시 입력 $0.075 · 출력 $0.60, `text-embedding-3-small` 입력 $0.02. 페이지에 갱신 날짜 표시는 없었다.
- `application-live.yml`의 `app.ai.pricing`에 입력했다. 기본 프로파일에는 넣지 않는다(실제 공급자를 쓰는 `live`에만).
- 응답 모델명 `gpt-4o-mini-2024-07-18`은 `AiCostCalculator`의 가장 긴 접두사 일치로 `gpt-4o-mini` 단가를 쓴다.
  이번 실제 호출 66건 모두 `estimated_cost_usd`가 기록됐다(NULL 0건). 임베딩 2건도 기록됐다.
- **추정 비용 ≠ 청구액.** 캐시 입력 할인, Batch, 세금·크레딧은 반영하지 않는다. 시스템 프롬프트와 스키마가 길어 OpenAI가 입력을
  자동 캐시하면 실제 청구액은 추정보다 낮을 수 있다. 정확한 금액은 OpenAI 사용량 화면에서 확인한다.
- 과거(`60b89f6` 이전) NULL 비용은 그대로 둔다.

## 4. 이슈 분류 평가 (대표 세트 27건, 실제 OpenAI)

`EvalLlmIT#printsMetricsForTheFixedSet`, 판단 프롬프트 `issue-classifier/v1`, 운영 임계값 그대로(SAME 0.85, NEW 0.70). 1회 실행.
평가 세트는 가상 기사이고, 정답은 "같은 사건 그룹"(`eventGroup`)과 묶음·모호 표시다. SAME/NEW 정답은 발행 순서와 그룹에서 계산된다.

| 지표 | 결과 | 합격 기준 초안 (`docs/EVALUATION.md`, 미확정) |
|---|---|---|
| 오병합 `FALSE_MERGE` | **1건 (E09)** | 0건 — **미달** |
| 스키마 실패 | 0건 | 1건 이하 — 충족 |
| SAME 재현율 (모호 제외) | 11/13 | 10/13 이상 — 충족 |
| REVIEW | 3건 (E08, 묶음 E23·E24) | 8건 이하 — 충족 |
| 반복성 | 1회만 실행 | 3회 — **미실행** |

전체 지표: precision 0.923, recall 0.923, 자동 오병합률 0.037, REVIEW 비율 0.111.

판정별로 나온 것: 후보가 있는 상황에서 NEW_ISSUE(LLM) 10건, SAME_ISSUE 13건, 후보 없음 NEW 1건, REVIEW 1건(LLM)과 묶음 REVIEW 2건이 모두 나왔다.

오분류:
- **E09 → 오병합.** 같은 회사의 **다른 분기(3분기) 실적**을 2분기 실적 이슈(G4)에 자동으로 붙였다. 세트에 일부러 넣은 반례이고, 초안의 필수 기준 위반이다.
- **E10 → 놓친 병합.** 같은 3분기 실적 후속 기사(G3)인데 NEW_ISSUE로 판단했다. E09가 G4에 붙어 G3 이슈가 없었던 영향으로 보인다.
- **E08 → REVIEW.** 같은 2분기 실적 후속 기사(G4)인데 신뢰도가 기준에 못 미쳐 REVIEW가 됐다(관리자 확인으로 복구 가능).
- E16(모호, 칼럼)은 NEW_ISSUE, E19(모호)는 SAME — 둘 다 허용 범위.

다음 조치 제안(이번에 하지 않음): 분류 프롬프트에 "같은 회사라도 다른 기간의 실적·발표는 다른 사건" 규칙을 넣은 `issue-classifier/v2`를
만들고 3회 측정. 초안 규칙대로 오병합이 있으면 임계값은 올리는 쪽으로만 조정한다.

## 5. 실행 범위·호출 수·비용

| 단계 | 호출 | 입력 토큰 | 출력 토큰 | 추정 비용 (USD) |
|---|---|---|---|---|
| 카테고리 v2 1차 (20건) + 임베딩 비용 확인 2건 | 22 | 28,152 | 6,655 | 0.008192 |
| 카테고리 v2 2차 (20건) | 20 | 28,603 | 6,873 | 0.008416 |
| 분류 평가 세트 (판단 24회) | 24 | 32,839 | 2,352 | 0.006339 |
| **합계** | **66** (실패 0, 재시도 0) | **89,594** | **15,880** | **0.022947** |

- DB: 이번 작업 전용 새 DB `aicontent_aicheck_20261014`(벡터 없음, `ai_log`만 기록). 기존 `aicontent`, `aicontent_openai_20261014_1`, `_2`는 읽기만 했다.
- 상한: 모든 실행에 `app.ai.limits.daily-max-calls=100`을 걸어, 이 DB의 오늘 호출 합계가 100에 닿으면 멈추게 했다.
- 자동 수집은 실행하지 않았다(기존 기사 재사용, 평가 세트는 가상 데이터).
- 키는 사용자의 Git 제외 파일에서 실행 프로세스의 환경 변수로만 넘겼고, 로그·문서·Git에 남기지 않았다.

## 6. 실행 명령

`backend/`에서(Windows, 로컬 Maven). 키는 환경 변수 `OPENAI_API_KEY`, DB는 `TEST_DB_URL`로 지정했다.

```powershell
# 입력 정리 리포트 (AI 호출 없음). articles.json은 검증 DB에서 내보낸 로컬 파일(Git 제외)
mvn test -Dtest=CleanerRealDataReport -Darticles.json=<파일> -Darticles.report=<파일>

# 카테고리 비교 (유료)
mvn test -Dtest=AnalysisCompareIT -Dcompare.run=true -Darticles.json=<파일> -Dcompare.cases=<기대값 파일> `
  -Dcompare.out=<결과 파일> -Dcompare.embed=2 -Dapp.ai.provider=openai `
  -Dspring.config.additional-location=file:src/main/resources/application-live.yml -Dapp.ai.limits.daily-max-calls=100

# 분류 평가 (유료)
mvn test "-Dtest=EvalLlmIT#printsMetricsForTheFixedSet" -Deval.run=true -Dapp.ai.provider=openai `
  -Dspring.config.additional-location=file:src/main/resources/application-live.yml -Dapp.ai.limits.daily-max-calls=100
```

`articles.json`은 검증 DB에서 `json_agg(json_build_object('id', a.id, 'source', s.name, 'publisher', a.publisher_name, 'title', a.title,
'publishedAt', a.published_at, 'sourceCategory', a.category, 'text', a.analysis_text, 'v1Category', a.analysis_json::jsonb->>'category'))`로 내보낸다.
기사 원문이 들어 있으므로 저장소에 넣지 않는다.

## 7. 미검증·남은 문제

- **묶음 기사 판별(`eval.analysis`)**: 분석 27회 + 재판단 약 24회가 필요해 호출 상한(100) 안에 들어가지 않아 실행하지 않았다.
- **분류 평가 반복 측정**: 1회만 실행했다. 합격 기준 초안의 3회 반복은 미실행.
- **오병합 E09**: 미수정. 합격 기준 초안 기준으로 현재 분류 품질은 **불합격**이다.
- **카테고리**: 새 기사로 재확인하지 않았다. 금성당제 1건은 여전히 OTHER.
- **실제 파이프라인 재실행**: 새 기사를 수집해 v2 프롬프트로 전체 흐름(수집→분석→임베딩→분류)을 다시 돌리지는 않았다. 이번 검증은 저장된 기사 재분석과 평가 하네스로 했다.
- **청구액 대조**: OpenAI 사용량 화면의 실제 청구액과 추정 비용을 비교하지 않았다.
