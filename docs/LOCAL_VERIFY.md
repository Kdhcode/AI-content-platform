# 로컬 검증 모드 (로그인 생략)

관리자 화면과 뉴스룸을 내 PC에서 직접 눌러 보는 용도다. 운영·공유 서버용이 아니다.

## 1. 무엇이 바뀌나

- 프로필 `local-verify`(`backend/src/main/resources/application-local-verify.yml`)에서만 켜진다. 기본값은 꺼짐(`app.local-verify.login-bypass=false`)이다.
- 켜지면 인증 정보(Authorization 헤더) 없이 온 관리자 API 요청을 고정 사용자 `local-verify`(OPERATOR + SYSTEM_ADMIN)로 처리한다. 감사 로그의 처리자도 `local-verify`로 남는다.
- 인증 정보를 보낸 요청은 지금처럼 검사한다(틀린 비밀번호는 401).
- 관리자 화면은 로그인 화면을 건너뛰고, 모든 관리자 화면과 뉴스룸 위에 노란 띠를 보여 준다: "로컬 검증 모드", 데이터 구분(`LOCAL_VERIFY_DATA_LABEL`), AI 상태.
- 화면은 `GET /api/local-verify/mode`로 상태를 확인한다(로그인 불필요). 모드가 꺼져 있으면 `loginBypass:false`만 돌려준다.

## 2. 켜지지 않는 경우 (서버가 시작을 거부함)

`LocalVerifyGuard`가 설정을 읽은 직후(DB 연결·AI 클라이언트 생성·포트 열기 전)와 보안 설정을 만들 때 두 번 검사한다. 하나라도 걸리면 `app.local-verify.login-bypass=true refused: …`로 시작하지 않는다.

| 조건 | 거부 예 |
|---|---|
| `local-verify` 프로필이 활성이어야 함 | 다른 프로필에서 `APP_LOCALVERIFY_LOGINBYPASS=true`만 켠 경우 |
| `live` 프로필이 활성이면 안 됨 | `--spring.profiles.active=local-verify,live` |
| `server.address`가 루프백(127.0.0.1, ::1, localhost)이어야 함. 미설정(모든 주소) 거부 | `--server.address=0.0.0.0` |
| AI 공급자가 `none` 또는 `stub`이어야 함. 설정 경로와 무관(`AI_PROVIDER`, `SPRING_APPLICATION_JSON`, 명령줄 등) | `AI_PROVIDER=openai` |
| OpenAI API 키가 없어야 함 | `OPENAI_API_KEY`가 설정된 경우 |

테스트: `LocalVerifyGuardTest`(조건별), `LocalVerifyIntegrationTest`(기본 설정은 여전히 401, 모드에서는 로그인 없이 동작·처리자 기록·틀린 인증은 401, 실제 시작 거부 4가지).

## 3. 실행 방법

백엔드 jar를 만든 뒤 환경 변수를 주고 `local-verify` 프로필로 실행한다. 관리자 UI는 빌드할 때 백엔드 주소(`BACKEND_URL`)가 들어가므로 환경마다 따로 빌드한다. UI도 `-H 127.0.0.1`로 띄워 이 PC에서만 열리게 한다.

```bash
java -jar backend/target/platform-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local-verify
```

| 환경 변수 | 시나리오 | 실제 기사 복사본 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:55432/aicontent_verify_scenario` | `jdbc:postgresql://127.0.0.1:55432/aicontent_verify_real` |
| `PORT` | 18091 | 18092 |
| `AI_PROVIDER` | `stub` | `none` |
| 추가 인자 | 없음 | `--app.worker.enabled=false` (작업 실행 안 함) |
| `LOCAL_VERIFY_DATA_LABEL` | 시나리오 데이터 (가상 기사, …) | 실제 기사 복사본 (…) |
| 뉴스 소스 | `SPRING_APPLICATION_JSON`으로 `docs/verify/scenario/round1~3.json`을 FIXTURE 소스로 등록 | 없음 |
| UI | `BACKEND_URL=http://127.0.0.1:18091 npm run build` → `next start -H 127.0.0.1 -p 13091` | `BACKEND_URL=http://127.0.0.1:18092` → 13092 |

`OPENAI_API_KEY`는 비워 둔다(있으면 시작 거부).

## 4. 데이터

**시나리오 DB `aicontent_verify_scenario`.** 빈 DB에서 `docs/verify/scenario`의 가상 기사 8건을 3회에 나눠 수집했다(stub 분석·임베딩·분류, 운영과 같은 임계값). 제목만 있고 본문은 비어 있다. 본문을 넣으면 stub 임베딩 유사도가 후보 기준(0.60) 아래로 떨어져 검수 사례가 생기지 않아서, 기준을 낮추지 않고 제목만 쓰는 방식을 택했다.

| 이슈 | 상태 | 내용 | 확인할 동작 |
|---|---|---|---|
| #1 | ACTIVE | 예시전자 2026년 2분기 실적 | 비교 대상 |
| #2 | ACTIVE | 8월 고용동향 | 비교 대상 |
| #3 | ACTIVE | 가상시 A동 상가 화재 첫 보도 | 병합 대상 |
| #4 | REVIEW | 3분기 실적(기간 규칙: 3분기 ≠ 2분기) + 보류 연결 2건(주가 상승, 행사장 정전 사고) | 분리 → 확정 |
| #5 | ACTIVE | 같은 화재의 원인 조사 기사(자동으로 묶이지 않음) | #3으로 병합 |
| #6 | REVIEW | 9월 고용동향(기간 규칙: 9월 ≠ 8월) | 별도 사건으로 확정 |

**실제 기사 복사본 `aicontent_verify_real`.** `aicontent_view_20261014`(2026-10-14 실제 OpenAI로 처리한 기사 35건·이슈 29개)를 `pg_dump`/`pg_restore`로 복사했다. 원본은 읽기만 했다. 복사본에서는 AI 공급자가 꺼져 있고 작업 실행기도 꺼져 있어 새 분석·임베딩·분류를 하지 않는다. **이 데이터에는 검수 대기 이슈가 없다**(실제 처리 결과가 모두 ACTIVE). 병합·분리와 뉴스룸 반영만 볼 수 있고, 검수 사유 화면은 시나리오로 본다.

다시 처음 상태로 돌리려면: 시나리오는 서버를 끄고 DB를 지운 뒤 다시 만들고 3회 수집, 실제 복사본은 DB를 지운 뒤 원본에서 다시 복사.
