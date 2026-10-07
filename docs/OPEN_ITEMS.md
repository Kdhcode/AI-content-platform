# 미확정 항목 (OPEN ITEMS)

구조/공급자에 영향을 주는 것만 모았습니다. "어디를 바꾸면 되는지"를 함께 적었습니다. 기본값은 [DECISIONS.md](DECISIONS.md)에 있습니다.

| # | 항목 | 현재 상태 | 정할 때 바꿀 곳 |
|---|---|---|---|
| 1 | 실제 뉴스 공급원(RSS/API/크롤링)과 이용 약관·저작권 범위 | 범용 RSS 어댑터 + 로컬 FIXTURE만 있음. 소스 0개가 기본 | `NewsSourceAdapter` 구현체 추가 → `NewsAdapterConfig`에 빈 등록 → `app.news.sources` 또는 `news_source` 행 추가. 어댑터 밖으로 사이트 전용 코드가 새지 않게 |
| 2 | LLM 공급자·모델 | `none`(실패) / `stub`(개발용) | `AiClient` 구현 → `AiConfig`의 `switch`에 case 추가 → `app.ai.provider`. 토큰/비용은 `AiCompletion`으로 `ai_log`에 이미 기록되며 단가 계산은 미구현 |
| 3 | 임베딩 모델과 차원 | `vector(1536)` 고정 | 모델 확정 후 새 Flyway 마이그레이션으로 `news_article.embedding`, `issue.embedding`, HNSW 인덱스 재생성 + `app.ai.embedding-dimension`. 모델을 바꾸면 기존 벡터는 재임베딩 필요 |
| 4 | 판단 임계값(후보 수 5, 최소 유사도 0.60, SAME 0.85, NEW 0.70, 후보 기간 30일) | 초기 추정값 | `app.classifier.*`. 실제 공급자로 `EvalLlmIT`를 돌린 기준선(`docs/EVALUATION.md`)을 보고 확정. **합격 기준(정밀도/재현율 목표)도 그때 정함** |
| 5 | 관리자 최종 인증 방식(세션/JWT/OIDC), 비밀번호 정책, 로그인 잠금 | HTTP Basic | `security/SecurityConfig`, `admin-ui/src/lib/api.ts` |
| 6 | 배포 환경(컨테이너/오케스트레이션), 시크릿 관리, DB·Redis 호스팅 | `docker-compose.yml`은 로컬 개발·테스트용 | 배포 방식 확정 후 Dockerfile/CI 추가 |
| 7 | 분류 처리량(전역 락) | 정확성 우선 직렬 처리. 락 대기 300초·TTL 300초(`app.classifier.lock-wait-seconds`, `app.lock.ttl-seconds`)로 버티지만, 대량 수집에서 LLM 지연 × 대기 건수가 이를 넘으면 `LOCK_TIMEOUT` 재시도가 소모됨 | 수집량이 늘면 후보 클러스터/카테고리 단위 락 또는 파티셔닝 검토, `LOCK_TIMEOUT`은 재시도 횟수를 소모하지 않게 별도 재큐 경로 추가 |
| 8 | 개인정보/로그 보존 정책(`ai_log.response` 원문 보관 기간) | 무기한, 20,000자 절단 | 보존 기간 확정 후 정리 배치 추가 |
| 9 | 이슈 종료/오래된 이슈 정리 정책(자동 CLOSED 시점) | 멤버 0명일 때만 CLOSED | 정책 확정 후 스케줄러 추가 |
| 10 | AI 비용 한도·호출 속도 제한 | 없음(워커 동시성 4가 사실상 한도) | `app.worker.concurrency`, 공급자별 레이트 리미터는 `AiClient` 구현 안에 |

## 이번 단계에서 의도적으로 하지 않은 것
회원가입/일반 사용자 로그인, 좋아요/북마크, TV/지역 정보, 발행, SEO, 통계, Python, MSA, Kafka/RabbitMQ — 지시서의 금지 목록.
