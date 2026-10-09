# 설계 결정 기록 (Phase 1)

지시서/Notion 문서가 아직 계획 단계라 비어 있던 부분을 구현하면서 정한 기본값과 그 이유입니다.
구조에 영향을 주는 선택(공급자, 모델, 임계값 최종값 등)은 여기가 아니라 [OPEN_ITEMS.md](OPEN_ITEMS.md)에 있습니다.
결정을 바꿀 때는 이 문서에 날짜와 이유를 추가하세요.

## 1. 범위
| 결정 | 이유 |
|---|---|
| 지시서 6~17절의 기능만 구현. 회원가입/로그인(일반 사용자), 좋아요/북마크, TV, 발행, SEO, 통계는 코드·테이블 모두 만들지 않음 | Phase 1 금지 목록. `issue`/`news_article`은 bigint id를 그대로 두어 나중에 CONTENT_SOURCE가 N:M으로 참조할 수 있게만 열어 둠 |
| 추가한 보조 테이블: `classification_run`, `admin_audit_log`, `app_user`/`user_role` | 요구된 "후보·판단 근거 추적", "병합/분리 이력", "관리자 인증" 흐름을 만들려면 최소한으로 필요 |

## 2. 구조
| 결정 | 이유 |
|---|---|
| Spring Boot 3.3.5 + Java 21 + Maven, 모듈러 모놀리스(패키지: `news`, `ai`, `issue`, `job`, `admin`, `security`, `lock`, `audit`, `common`, `config`) | 필수 스택. 패키지 간 호출은 서비스/리포지토리 경유 |
| 데이터 접근은 Spring `JdbcClient` + 명시적 SQL, JPA 없음 | pgvector 연산(`<=>`, `avg(vector)`), `FOR UPDATE SKIP LOCKED`, 부분 유니크 인덱스를 ORM 없이 그대로 쓰는 편이 정확하고 검토하기 쉬움 |
| 스키마 변경은 Flyway(`V1__phase1_schema.sql`) | 재현 가능한 스키마. V1은 새 DB 기준(아직 운영 데이터 없음) |
| Redis는 락 전용·선택. 기본 off(`app.lock.redis-enabled=false`) → JVM 내 락 | 지시서: Redis는 보조. 데이터 정합성은 DB 제약과 ASYNC_JOB 소유권 검사가 책임지고, 락은 안전망. Redis 장애 시 락 없이 계속(퇴화) |
| 비동기 큐는 `async_job` + Spring 워커 | PostgreSQL이 진실 원천. Kafka/RabbitMQ 금지 |

## 3. 비동기 작업 (ASYNC_JOB)
| 결정 | 이유 |
|---|---|
| claim: `WITH next AS (SELECT … FOR UPDATE SKIP LOCKED) UPDATE … RETURNING` | 여러 워커/스레드가 같은 작업을 잡을 수 없음. 로컬 PG에서 2세션 동시 claim 실험으로 확인 |
| 모든 상태 전이는 `status='RUNNING' AND locked_by=:worker` 조건 | 유실 판정된 워커가 새 소유자의 결과를 덮어쓰지 못함 |
| 재시도: 기본 3회, 대기 `min(600s, 5s·2^retry_count)`, 재시도 불가 오류는 즉시 FAILED | 일시 오류(소스 타임아웃/네트워크/429/5xx, 분류 락 타임아웃, 공급자가 retryable로 표시한 AI 오류)만 재시도. 검증 실패·설정 오류·4xx는 반복해도 같은 결과 |
| 하트비트 5초, 300초 무응답이면 `WORKER_LOST`로 재큐(재시도 소진 시 FAILED). 서버 시작 시에도 1회 복구 | 재시작/크래시 복구 요구 |
| `dedupe_key` 부분 유니크(PENDING/RUNNING만) | 같은 기사에 분석 작업이 중복 쌓이지 않음. 끝난 뒤에는 재요청(재분석) 가능 |
| 작업 체인: COLLECT_NEWS → ANALYZE_ARTICLE → EMBED_ARTICLE → CLASSIFY_ARTICLE, 각 단계가 다음 작업을 등록 | 단계별 재시도·관측 가능. 핸들러는 멱등 |
| 최종 실패 시 `onFinalFailure` 콜백으로 기사 상태 반영(FAILED) | 관리자 목록에서 실패 기사를 찾을 수 있게 |

## 4. 수집·중복 제거
| 결정 | 이유 |
|---|---|
| 중복 제거 순서: ① 정규화 URL(DB 유니크 인덱스, 권위) → ② 같은 언론사 + 정규화 제목 해시(7일 창) → `DUPLICATE`로 저장하고 원본 id 기록, 분석하지 않음 | 지시서의 순서. 유사도 기반 중복 제거는 "선택" 항목이라 Phase 1에서 제외 |
| URL 정규화: http→https, `www.`/기본 포트/fragment/끝 슬래시 제거, 추적 파라미터(utm_*, fbclid 등) 제거, 쿼리 정렬 | 같은 기사의 다른 URL 변형을 같은 키로 |
| 제목 정규화: NFKC, 소문자, 앞뒤 `[속보]`/`[종합]` 류 태그와 구두점 제거 후 SHA-256 | 재전송 시 붙는 태그만 달라진 제목을 같은 것으로 |
| 제목 중복은 *같은 언론사*일 때만 | 서로 다른 언론사가 같은 제목을 쓰는 것은 흔하고(통신사 기사) 오히려 이슈 근거가 됨 |
| 기사 1건 = 1 트랜잭션(저장 + 분석 작업 등록) | 한 기사 오류가 나머지를 막지 않고, 기사만 있고 작업이 없는 상태가 생기지 않음 |
| 발행시각 파싱 실패 → `published_at=NULL`, 원문은 `published_at_raw`에 보존 | 시각을 추측해서 채우지 않음. 이슈 집계는 `COALESCE(published_at, collected_at)` |
| 소스 실패 3회 연속 → `DEGRADED`, 실패 횟수만큼 수집 간격을 늘림(최대 6배), 성공 시 복구 | 죽은 피드를 계속 두드리지 않기 |
| 기본 프로파일은 소스 0개. RSS 어댑터(범용)·FIXTURE 어댑터(로컬 JSON) 제공. **2026-10-07 변경:** opt-in `live` 프로파일에 연합뉴스·BBC World RSS 등록(소스당 초기 3건, 자동 스케줄러 기본 off) | 지시서: 임의 뉴스 데이터 생성 금지. 실제 흐름 검증용으로 공개 RSS만 사용. 추가 공급원·상용 이용 범위는 OPEN ITEM #1 |

## 5. AI 계층
| 결정 | 이유 |
|---|---|
| `AiClient`/`EmbeddingClient` 인터페이스 뒤에 공급자를 숨김. 제공자는 `none`(기본, 호출 시 `AI_PROVIDER_NOT_CONFIGURED`), `stub`(개발/테스트 전용) | 공급자 선정은 OPEN ITEM. 설정이 없을 때 조용히 가짜 결과를 만들지 않고 큰 소리로 실패 |
| **2026-10-07 추가:** `openai` 제공자(`OpenAiClient`/`OpenAiEmbeddingClient`). 기본 모델 `gpt-4o-mini`·`text-embedding-3-small`(1536차원). JSON 모드로 받은 뒤 기존 전체 JSON Schema로 재검증(서버 strict 모드 미사용). `AI_PROVIDER=openai`인데 키가 없으면 기동 실패(stub 대체 없음). HTTP 오류 본문·키는 로그에 저장하지 않음 | 조건부 `allOf/if/then`이 있는 기존 스키마를 그대로 쓰기 위함. 실제 외부 호출·품질은 미검증, 모델·예산 확정은 OPEN ITEM #2·#3 |
| LLM 출력은 반드시 `StructuredOutputService`를 거침: JSON 추출 → 파싱 → JSON Schema(2020-12) 검증 → 실패 시 오류를 힌트로 재시도 → 그래도 실패하면 예외 | "검증 없는 LLM 출력 저장 금지". 시도마다 `ai_log`에 성공/실패·프롬프트 버전·원문 기록 |
| 분석 호출은 출력 검증 실패에 한해 최대 2회 시도(`app.ai.analysis-max-attempts`). 검증 실패로 끝나면 작업은 재시도 없이 FAILED(기사 FAILED) | 같은 프롬프트를 작업 단위로 다시 돌려도 같은 결과일 가능성이 큼. 관리자가 재분석 가능 |
| 프롬프트는 `src/main/resources/prompts/<key>/<version>/{system.md,user.md,schema.json,meta.json}` | 버전 디렉터리는 불변(변경 = 새 버전). 클래스패스에 두면 배포물과 함께 버전이 고정됨. 사용 버전은 `app.prompts.*` |
| 임베딩 입력 = 제목 + 요약 + 핵심 사실 + 개체명(최대 6000자). 차원 1536 고정, 불일치 시 비재시도 오류 | 지시서. 차원은 모델 선정 시 마이그레이션으로 변경(OPEN ITEM) |
| 프롬프트에 임베딩 유사도 값을 넣지 않음 | 모델이 숫자에 끌려 판단하는 것을 막고, "유사도만으로 판단 금지" 원칙을 프롬프트에서도 유지 |
| 모든 AI 호출은 DB 트랜잭션 밖에서 | 호출 실패/롤백과 무관하게 `ai_log`가 남도록 |

## 6. ISSUE 판단
| 결정 | 이유 |
|---|---|
| 후보: 기사 임베딩 기준 코사인 상위 5개, 유사도 ≥ 0.60, 최근 30일, 상태 ACTIVE | 검색은 범위를 좁힐 뿐 판단하지 않음. 수치는 초기값(OPEN ITEM) |
| 후보 없음 → LLM 호출 없이 NEW_ISSUE(method `NO_CANDIDATE`) | 비교 대상이 없는데 모델에게 묻는 것은 비용과 환각 위험만 늘림 |
| `multiEvent=true` 기사 → LLM 호출 없이 REVIEW | 묶음 기사는 한 이슈에 속할 수 없음 |
| 가드(`DecisionPolicy`): SAME_ISSUE는 신뢰도 ≥ 0.85 **그리고** `matchedIssueId ∈ 후보`일 때만 자동 적용, NEW_ISSUE는 신뢰도 ≥ 0.70, 아니면 REVIEW로 강등(`GUARD_DOWNGRADE`) | 오병합(SAME 오판)이 가장 비싼 오류. 근거 없는 자동 연결 금지 |
| 스키마 위반/LLM 실패 → REVIEW(`INVALID_OUTPUT`) 또는 AI 오류는 작업 재시도 | 모르면 사람에게 넘김 |
| REVIEW는 **임시 이슈(status=REVIEW)** 를 만들어 기사를 연결 | 미연결 기사가 생기지 않고 관리자가 병합/확정으로 처리. REVIEW 이슈는 후보에서 제외되어 오염 전파를 막음 |
| 분류 단계 전체를 락(`issue-classification`) 안에서 실행 | 같은 새 사건의 두 기사가 동시에 "후보 없음"을 보고 이슈를 둘 만드는 경쟁 방지. 처리량보다 정확성 우선. 대기 300초/TTL 300초 기본(OPEN ITEM: 규모가 커지면 사건 클러스터 단위 락) |
| 이미 이슈에 연결된 기사의 재분류는 `classification_run`만 기록(적용 안 함) | 관리자가 고친 결과를 자동화가 되돌리지 않음 |
| 적용 직전 대상 이슈를 행 잠금으로 재확인(ACTIVE 아니면 REVIEW로) | 검색~적용 사이에 관리자가 병합/종료했을 수 있음 |
| 이슈 집계(기사 수, 언론사 수, 기간, 중심 임베딩 `avg(embedding)`, 핵심 사실/개체)는 멤버십 변경마다 한 곳(`IssueAggregateService.refresh`)에서 SQL로 재계산 | 병합/분리/이동 후 값이 어긋나지 않도록. 증분 갱신 대신 재계산 |
| 요약: `summary_source` AUTO/MANUAL. 관리자가 쓴 요약은 자동 갱신하지 않음 | 수동 수정 보호 |
| 멤버가 0명이 된 ACTIVE/REVIEW 이슈는 CLOSED | 빈 이슈 정리 |

## 7. 관리자 기능·API
| 결정 | 이유 |
|---|---|
| 병합: 경로 id = 남는 이슈(target), 본문 `sourceIssueIds`. 원본은 삭제하지 않고 `MERGED` + `merged_into_issue_id` | 이력 보존, 되돌리기 판단 가능 |
| 분리: 원본에 기사 1건 이상 남아야 함. 새 이슈는 ACTIVE | 빈 이슈 방지 |
| 병합/분리/이동/수정은 각각 1 트랜잭션, 이슈 행 잠금은 id 오름차순, 감사 로그를 같은 트랜잭션에 기록 | 동시 관리 작업 교착 방지, 부분 적용 없음 |
| 응답 형식 `{success,data,error}`, 페이지는 0부터, `size` 1~100(기본 20), 정렬은 화이트리스트 키만 | 지시서의 공통 형식. 정렬 키가 SQL로 직접 들어가지 않게 |
| 인증: HTTP Basic + `app_user`/`user_role`(BCrypt), 무상태. 조회·이슈/기사 수정 = OPERATOR 이상, 작업 재시도/취소·수동 수집 = SYSTEM_ADMIN | 최소 인증. 최종 로그인 방식은 OPEN ITEM (`SecurityConfig` 한 곳만 교체) |
| 초기 관리자는 `ADMIN_USERNAME`/`ADMIN_PASSWORD` 환경 변수가 있을 때만 생성. 기본 계정 없음 | 하드코딩 자격증명 금지 |
| 소스 목록 API는 `config`(자격증명이 들어갈 수 있음)를 반환하지 않음 | 비밀 노출 방지 |
| 브라우저는 Next.js 서버만 호출하고 `/api/*`를 백엔드로 프록시 | CORS 설정 불필요 |
| 관리자 UI의 자격증명은 `sessionStorage`(탭을 닫으면 사라짐) | Phase 1 최소. 최종 인증 방식 확정 시 교체 |

## 8. 알려진 한계 (의도적)
- 분류 락이 전역이라 LLM 호출 시간만큼 분류가 직렬화됩니다(대량 수집 시 병목 → OPEN ITEM).
- 이슈 제목은 첫 기사 제목으로 정해지고 자동으로 바뀌지 않습니다(관리자 수정). 분리/이동으로 첫 기사가 빠져도 제목은 그대로입니다.
- 임베딩 검색은 HNSW 근사 검색 뒤에 상태/기간 필터를 적용하므로, 후보가 많은 환경에서는 필터에 걸려 후보가 줄 수 있습니다(`ef_search` 조정 또는 후보 수 확대로 대응).
- 평가 세트의 후보 검색은 "최근 갱신 N개 이슈"로 단순화되어 있어 임베딩 검색 재현율은 측정하지 않습니다.
