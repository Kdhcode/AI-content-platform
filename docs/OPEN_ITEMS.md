# 미확정 항목 (OPEN ITEMS)

구조/공급자에 영향을 주는 것만 모았습니다. "어디를 바꾸면 되는지"를 함께 적었습니다. 기본값은 [DECISIONS.md](DECISIONS.md)에 있습니다.

| # | 항목 | 현재 상태 | 정할 때 바꿀 곳 |
|---|---|---|---|
| 1 | 실제 뉴스 공급원(RSS/API/크롤링)과 이용 약관·저작권 범위 | `live` 프로파일에 연합뉴스·BBC RSS 등록, 실제 수집 검증 완료. 기본 프로파일은 소스 0개 | 추가 공급원과 상용 이용 범위는 확정 필요. `app.news.sources` 또는 `NewsSourceAdapter` 구현체로 확장 |
| 2 | LLM 공급자·모델 | `none` / `stub` / `openai` 구현. 기본 OpenAI 모델 `gpt-4o-mini`, HTTP 모의 서버 통합 검증 완료. 실제 API 키 없어 외부 호출 미검증 | `OPENAI_API_KEY`, `OPENAI_CHAT_MODEL`. 계정의 모델 접근 권한·품질·예산 확정 필요. **2026-10-12:** 비용 계산 구현(`AiCostCalculator` → `ai_log.estimated_cost_usd`). 단가는 코드·기본 설정에 넣지 않았으므로 `app.ai.pricing`에 직접 입력해야 기록됨(미설정이면 NULL) |
| 3 | 임베딩 모델과 차원 | `text-embedding-3-small` 어댑터, `vector(1536)` 고정. 실제 pgvector 통합 테스트 통과, 외부 API 호출 미검증 | `OPENAI_EMBEDDING_MODEL`. 차원 변경은 새 Flyway 마이그레이션 필요. 모델을 바꾸면 기존 벡터 전체 재임베딩, stub DB와 실제 모델 DB 분리 |
| 4 | 판단 임계값(후보 수 5, 최소 유사도 0.60, SAME 0.85, NEW 0.70, 후보 기간 30일) | 초기 추정값 | `app.classifier.*`. 실제 공급자로 `EvalLlmIT`를 돌린 기준선(`docs/EVALUATION.md`)을 보고 확정. **합격 기준(정밀도/재현율 목표)도 그때 정함** |
| 5 | 관리자 최종 인증 방식(세션/JWT/OIDC), 비밀번호 정책, 로그인 잠금 | HTTP Basic | `security/SecurityConfig`, `admin-ui/src/lib/api.ts` |
| 6 | 배포 환경(컨테이너/오케스트레이션), 시크릿 관리, DB·Redis 호스팅 | `docker-compose.yml`은 로컬 개발·테스트용 | 배포 방식 확정 후 Dockerfile/CI 추가 |
| 7 | 분류 처리량(전역 락) | 정확성 우선 직렬 처리. 락 대기 300초·TTL 300초(`app.classifier.lock-wait-seconds`, `app.lock.ttl-seconds`). **2026-10-12:** 재큐 경로 구현 — `LOCK_TIMEOUT`은 `retry_count`를 쓰지 않고 `defer_count`만 올려 30초 뒤 다시 실행, 20회 초과 시 FAILED(`app.worker.defer-delay-seconds`, `app.worker.max-defers`). 락 단위 세분화는 미결 | 수집량이 늘면 후보 클러스터/카테고리 단위 락 또는 파티셔닝 검토 |
| 8 | 개인정보/로그 보존 정책(`ai_log.response` 원문 보관 기간) | 무기한, 20,000자 절단. **2026-10-13:** 정리 배치 구현(`AiLogRetentionService`). 기본 꺼짐(0일 = 무기한), 켜면 기간이 지난 행의 원문만 비우고 토큰·비용 기록은 남김. 행 자체 삭제, `error_message`·기사 본문(`analysis_text`) 보존 기간은 미구현 | 보존 기간을 정해 `app.retention.ai-log-response-days`(환경 변수 `AI_LOG_RESPONSE_RETENTION_DAYS`)에 입력 |
| 9 | 이슈 종료/오래된 이슈 정리 정책(자동 CLOSED 시점) | 멤버 0명일 때 CLOSED. **2026-10-13:** 자동 종료 스케줄러 구현(`IssueAutoCloseService`). 기본 꺼짐(0일), 켜면 활동 없는 ACTIVE 이슈를 CLOSED로 바꾸고 감사 로그 `ISSUE_AUTO_CLOSE`를 남김. REVIEW 이슈는 대상 아님 | 종료 기준 일수를 정해 `app.issue-lifecycle.auto-close-after-days`(환경 변수 `ISSUE_AUTO_CLOSE_AFTER_DAYS`)에 입력. 후보 기간(`app.classifier.candidate-window-days`, 30일)보다 짧게 잡으면 후속 기사가 그만큼 빨리 새 이슈가 됨 |
| 10 | AI 비용 한도·호출 속도 제한 | 관찰용 화면(`GET /api/admin/ai-usage`, 관리자 UI `/ai-usage`). **2026-10-13:** 한도 구현(`AiUsageLimiter`, `app.ai.limits`): 하루 예산·하루 호출 수·분당 호출 수. 기본 모두 꺼짐. 한도에 닿으면 작업은 실패하지 않고 대기(재시도·재큐 횟수 미사용). 사용량 화면에 한도와 오늘 사용량·차단 여부 표시. 공급자 쪽 429(OpenAI 한도)는 기존대로 재시도 오류 | 예산이 정해지면 `app.ai.limits.daily-budget-usd`(단가 `app.ai.pricing` 필요), 또는 `AI_DAILY_MAX_CALLS`·`AI_MAX_CALLS_PER_MINUTE` 환경 변수. 소프트 한도라 동시성(4)만큼 넘칠 수 있음 |

## 이번 단계에서 의도적으로 하지 않은 것
회원가입/일반 사용자 로그인, 좋아요/북마크, TV/지역 정보, 발행, SEO, 통계, Python, MSA, Kafka/RabbitMQ — 지시서의 금지 목록.
