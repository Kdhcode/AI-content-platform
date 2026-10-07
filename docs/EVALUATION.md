# AI 평가 세트와 지표

## 목적
프롬프트·임계값·모델을 바꿀 때 "더 나아졌는가"를 같은 자로 재기 위한 고정 평가 세트입니다. 품질 합격선은 **정하지 않았습니다**(실제 공급자 기준선을 먼저 측정해야 함 → OPEN_ITEMS #4).

## 데이터: `backend/src/test/resources/eval/issue-eval-v1.json`
- 27건, 사건 그룹 10개 + 묶음 기사 2건. **모두 가상(fictional) 데이터**(가상시, 허구군, 예시전자, 샘플은행, 테스트대학교 등). 실제 뉴스·실존 인물/기관과 무관합니다.
- 필드: `id, publisher, publishedAt, title, summary, keyFacts[], entities[], eventGroup(정답 라벨), multiEvent, ambiguous, note`.
- 의도적으로 넣은 반례:
  - **G1 vs G2**: 같은 "화재"지만 다른 날짜·다른 지역(자동 SAME이면 오류)
  - **G4 vs G3**: 같은 기업의 2분기/3분기 실적(자동 SAME이면 오류)
  - **후속 보도**: 같은 사건의 원인 조사/피해 집계/지원 검토(SAME이어야 함)
  - **묶음 기사 2건**: 어떤 이슈에도 자동 연결 금지(REVIEW여야 함)
  - **모호 2건(E16 칼럼, E19 후속 정책)**: 사람도 갈리는 경우 → REVIEW가 허용되고 오류로 세지 않음
- 이 세트는 측정 도구입니다. 수정하면 이전 결과와 비교할 수 없으니 바꾸지 말고 `issue-eval-v2.json`을 새로 만드세요(`EvalDatasetTest`가 우발적 변경을 막음).

## 실행
`EvalRunner`가 발행시각 순으로 한 건씩 운영과 같은 규칙으로 처리합니다: 후보 없음→NEW, 묶음→REVIEW, 그 외 LLM 판단→`DecisionPolicy`(강등 규칙 포함). 결과를 메모리 이슈에 적용하며(NEW=ACTIVE 이슈 생성, SAME=기존 이슈에 추가, REVIEW=후보에서 제외되는 임시 이슈) 후보는 "ACTIVE 이슈 중 최근 갱신 N개"입니다(임베딩 검색 재현율은 별도 평가 필요: OPEN ITEM).

```bash
cd backend
mvn -Dtest=EvalLlmIT -Deval.run=true -Dspring.profiles.active=test -Dapp.ai.provider=<공급자> test
```
`stub` 제공자로 돌리면 하네스가 동작하는지만 확인됩니다(스텁은 모델이 아님). 출력 예: `N=27 precision=… recall=… wrongAutoSameRate=… reviewRate=… adminEditRate=… errors={…}` 와 사례별 결과.

## 지표 정의 (`EvalMetrics`, 단위 테스트로 손계산 검증됨)
| 지표 | 정의 |
|---|---|
| precision | 자동 SAME_ISSUE 중 정답 이슈에 연결된 비율 |
| recall | 정답이 "기존 이슈와 같은 사건"인 사례(모호 제외) 중 자동으로 올바르게 연결된 비율 |
| wrongAutoSameRate | 잘못된 자동 SAME_ISSUE 건수 / 전체 건수 — 가장 비싼 오류(이슈 오염) |
| reviewRate | REVIEW 건수 / 전체 — 사람 검수 부담 |
| adminEditRate | 잘못된 자동 결정(REVIEW 아님) / 자동 결정 — 관리자가 고쳐야 할 비율(모호 사례는 오류로 세지 않음) |
| errorTypes | `FALSE_MERGE`(자동 SAME이 다른 이슈/첫 보도에 붙음), `MISSED_MERGE`(같은 사건인데 자동 NEW), `BUNDLE_NOT_REVIEWED`, `REVIEW_COULD_BE_SAME/NEW`(명확한 답이 있었는데 REVIEW: 오류는 아니지만 자동화 손실), `INVALID_LLM_OUTPUT`, `GUARD_DOWNGRADE`(정책이 LLM 판단을 뒤집음) |

분모가 0이면 `n/a`(null)입니다.

## 데이터셋이 변별력이 있는지 확인한 방법
같은 세트를 단순 단어 겹침 판단기로 돌려 보면(작성 중 임시 스크립트) 같은 기업의 다른 분기 실적(E09/E10→G4)이 잘못 병합됩니다. 즉 반례가 실제로 오류를 유발합니다. 이 스크립트는 저장소에 포함하지 않았고, 수치는 기준선으로 쓰지 않습니다.

## 운영 데이터로 같은 지표 보기
운영에서는 `classification_run`(후보·원판단·적용 여부)과 `issue_article.manually_corrected`/`admin_audit_log`가 쌓입니다. 관리자가 병합/이동/분리한 건수를 자동 적용 건수로 나누면 실제 `adminEditRate`를 얻을 수 있습니다(전용 리포트 화면은 Phase 1 범위 밖).
