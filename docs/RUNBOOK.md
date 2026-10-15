# 실행 환경 운영 기준 (RUNBOOK)

2026-10-15 기준. 모든 서버와 DB는 사용자 PC 안(127.0.0.1)에서만 열린다.

## 1. 현재 서버·포트·DB 정리

### 서버
| 포트 | 용도 | 상태 (10-15 오후) |
|---|---|---|
| 55432 | PostgreSQL 16 + pgvector (`.local/pg16`, 데이터 `.local/pgdata`) | 실행 중. 10-14 18:45에 실행 세션이 끝나며 함께 종료됐다가 10-15 재시작(정상 복구, 데이터 이상 없음) |
| 8090 / 3090 | 10-14 실제 AI 검증 결과 보기용 서버(`aicontent_view_20261014`) | **종료 상태.** 10-14 18:45 DB와 같은 시각에 종료. 임시 폴더(작업 세션)의 작업 트리에서 실행하던 것이라 다시 띄우지 않음 |
| 18091 / 13091, 18092 / 13092 | 로컬 검증 모드(로그인 생략) 시나리오·실제 복사본 | 필요할 때만 실행(`docs/LOCAL_VERIFY.md`) |
| 8080 / 3000 | **평소 사용(daily) 권장 구성** | 스크립트 검증 후 종료. 사용자 터미널에서 실행 |

### DB (임베딩 종류별로 분리)
| 구분 | DB | 내용 |
|---|---|---|
| **실제 OpenAI 임베딩** | `aicontent_main` | **평소 사용 DB(신규).** `aicontent_view_20261014`를 복사(기사 35·임베딩 35·이슈 29, 관리자 `viewer`) |
| | `aicontent_view_20261014` | 10-14 보기용 원본. 수정하지 않는 보관용 |
| | `aicontent_openai_20261014_2`, `_1` | 10-14 실제 OpenAI 첫 검증 원본(보관) |
| | `aicontent_verify_real` | 로컬 검증 모드용 복사본 |
| | `aicontent_aiq2_pipe`, `aicontent_aiq3_pipe` | 품질 작업의 소규모 실제 처리 기록(보관) |
| **stub 임베딩** | `aicontent` | 개발 DB(실제 RSS 기사 494건 + stub 분석·벡터). 실제 AI를 붙이면 벡터가 섞이므로 금지 |
| | `aicontent_verify_scenario` | 로컬 검증 모드 시나리오 |
| | `aicontent_aiq4_ui`, `aicontent_aiq4_int`, `aicontent_sql` | 지난 화면·통합 확인용(보관) |
| | `aicontent_test` | Maven 통합 테스트 전용(테스트가 매번 비움) |
| 기사 없음(평가 로그만) | `aicontent_aiq2_eval`, `aicontent_aiq3_eval`, `aicontent_aiq4_eval`, `aicontent_aicheck_20261014`, `aicontent_aiq4_pipe` | 평가 호출·비용 기록(보관) |

규칙: **실제 AI 임베딩 DB에는 `stub`을, stub DB에는 `openai`를 연결하지 않는다.** 두 겹으로 막는다.
- 실행 스크립트가 환경마다 DB와 AI 공급자를 고정한다.
- 앱 시작 시 `AiProviderGuard`가 DB의 `ai_log`에 기록된 성공한 임베딩 호출의 공급자를 보고, 다른 종류면 시작을 거부한다(스크립트 없이 `java -jar`로 띄워도 적용). 임베딩 기록이 없는 새 DB는 허용. 2026-10-15 확인: `aicontent`에 openai, `aicontent_main`에 stub 연결 모두 거부.

## 2. 권장 구성

| 환경 | 주소 | DB | AI | 로그인 |
|---|---|---|---|---|
| **daily (평소)** | 관리자 http://127.0.0.1:3000 · 뉴스룸 http://127.0.0.1:3000/news · API 8080 | `aicontent_main` | 기본 `none`(유료 호출 없음). 새 기사 처리가 필요할 때만 `-Ai openai` + 호출 한도 | **관리자 인증 필수** |
| dev (개발) | 3001 / 8081 | `aicontent` | `stub` | 관리자 인증 |
| verify-scenario / verify-real | 13091·18091 / 13092·18092 | 검증 DB | stub / none | 로그인 생략(검증 전용, `local-verify`) |

로그인 생략은 검증 환경에만 남긴다. daily·dev는 `local-verify` 프로필을 쓰지 않으므로 기존 인증이 그대로다(2026-10-15 확인: daily에서 로그인 없이·틀린 비밀번호로 관리자 API 401, `viewer` 계정 200).

## 3. 처음 한 번 준비

1. `npm install`을 `admin-ui`에서 한 번 실행(이미 했으면 생략).
2. `.local/run/machine.ps1`을 만든다(Git 제외). 예:
   ```powershell
   $env:JAVA_HOME = 'C:\Users\User\.jdks\temurin-25.0.4.1'
   $NodeDir = 'C:\Users\User\AppData\Local\JetBrains\IntelliJIdea2026.2\acp-agents\.runtimes\node\24.19.0'
   ```
3. daily 관리자 계정: `aicontent_main`에는 `viewer` 계정이 복사돼 있다(비밀번호는 `.local/view/viewer-password.txt`, Git 제외). 자기 계정을 따로 만들려면 처음 실행할 때 `ADMIN_USERNAME`/`ADMIN_PASSWORD`를 그 터미널에만 설정한다(없는 아이디일 때만 생성, 기존 비밀번호는 바꾸지 않음).

## 4. 평소 실행 순서 (본인 터미널에서)

도구 세션에서 띄운 서버는 세션이 끝날 때 함께 종료될 수 있다(10-14 사례). 평소 서버는 본인 PowerShell 창에서 실행한다. 창 3개를 쓴다.

```powershell
.\scripts\run\start-db.ps1                      # DB (이미 실행 중이면 그대로)
.\scripts\run\build.ps1                         # 백엔드 jar (코드가 바뀌었을 때만)
.\scripts\run\start-backend.ps1 -Target daily   # 창 1: API 127.0.0.1:8080
.\scripts\run\start-ui.ps1 -Target daily        # 창 2: 화면 127.0.0.1:3000 (화면 코드가 바뀌면 -Rebuild)
```

멈출 때는 각 창에서 Ctrl+C. DB를 멈추려면 `& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata -m fast -w stop`.

PC를 켤 때마다 DB가 자동으로 뜨게 하려면 Windows 서비스로 등록할 수 있다(`pg_ctl register`, 관리자 권한 필요 — 사용자가 직접 결정·실행).

**스크립트가 막는 것(2026-10-15 보강):** 환경을 정하는 값(프로필, 로그인 생략, AI 공급자, DB, 주소, 포트)은 모두 명령줄 인자로 넘긴다. Spring Boot는 명령줄 인자를 환경 변수·설정 파일보다 우선하므로, 터미널에 남은 `AI_PROVIDER`, `DB_URL`, `OPENAI_API_KEY` 같은 값은 무시된다. 인자로 완전히 덮을 수 없는 값(`SPRING_PROFILES_*`, `SPRING_CONFIG_*`, `SPRING_APPLICATION_JSON`, `SPRING_DATASOURCE_*`, `SERVER_ADDRESS`, `APP_LOCALVERIFY*`/`APP_LOCAL_VERIFY*`, `APP_AI_PROVIDER`, `APP_AI_OPENAI_*`, `APP_AI_EMBEDDINGGUARD*`(혼합 검사 끄기), `-D`가 든 `JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS`/`_JAVA_OPTIONS`)이 남아 있으면 스크립트가 이름을 알려 주고 시작하지 않는다(`Remove-Item Env:이름`으로 지운 뒤 다시 실행). 스크립트는 터미널의 환경 변수를 바꾸지 않는다. daily·dev는 `--app.local-verify.login-bypass=false`를 항상 넘기므로 로그인 생략이 켜질 수 없다.

확인 기록(10-15, 유료 호출 없음): 보강 전에는 터미널에 `SPRING_PROFILES_ACTIVE=local-verify`, `APP_LOCALVERIFY_LOGINBYPASS=true`가 남아 있으면 daily에서 로그인 생략이 켜졌다(관리자 API 200). 보강 후 스크립트가 시작을 거부하고, 스크립트를 거치지 않고 같은 인자로 실행해도 로그인 생략 꺼짐·401.

**유료 AI(새 기사 처리)를 켤 때만:**
```powershell
$env:OPENAI_API_KEY = '...'          # 이 창에만. 파일·Git·채팅에 남기지 않는다
$env:AI_DAILY_MAX_CALLS = '50'       # 없으면 스크립트가 시작을 거부
.\scripts\run\start-backend.ps1 -Target daily -Ai openai
```
`-Ai openai`이면 스크립트가 `live` 프로필을 켠다. 모델 단가(`application-live.yml`의 `app.ai.pricing`)와 실제 RSS 소스가 함께 들어온다. 호출 한도는 `AI_DAILY_MAX_CALLS`(필수), `AI_MAX_CALLS_PER_MINUTE`, 하루 예산 `APP_AI_LIMITS_DAILY_BUDGET_USD`(선택)로 정한다. 앱은 설정된 채팅·임베딩 모델에 단가가 없으면 시작을 거부한다(비용이 NULL로 기록되는 것을 막음). 10-15 확인: 단가 없이 openai → 거부, `live` + 한도 1 → 시작·한도 1 적용·AI 호출 0(가짜 키, 작업 실행기 끔).
뉴스 자동 수집 스케줄러는 스크립트가 항상 끈다(`--app.news.scheduler-enabled=false`). 수집은 관리자 화면의 수동 수집으로만 한다.

## 5. main 병합 후 재시작·확인 순서

이번 통합(`claude/integration-review`)에는 **DB 마이그레이션 변경이 없다**(V1~V3 그대로). 병합·되돌리기 모두 코드만 바뀐다.

**병합 전**
1. 평소 DB 백업: `& .local/pg16/pgsql/bin/pg_dump.exe -h 127.0.0.1 -p 55432 -U aicontent -Fc -f .local/backup-aicontent_main-<날짜>.dump aicontent_main`
2. 병합 전 `main` 커밋 기록: `885b2f5`.
3. IntelliJ 개인 설정 확인: 백업 `.local/idea-db-settings-backup-20261014/`가 현재 `.idea/dataSources.xml`, `.idea/db-forest-config.xml`과 같은지 확인(10-15 확인: 동일).

**병합 후 (사용자 작업 폴더 `ai-content-platform`)**
1. 현재 `feature/public-news`에 `.idea/db-forest-config.xml` 수정이 남아 있다. `main`에서는 이 파일을 추적하지 않으므로 그대로 `git checkout main`하면 Git이 멈춘다. 백업을 확인한 뒤:
   ```powershell
   git restore .idea/db-forest-config.xml
   git checkout main
   git pull main main
   Copy-Item .local\idea-db-settings-backup-20261014\dataSources.xml, .local\idea-db-settings-backup-20261014\db-forest-config.xml .idea\
   ```
   (`git pull`이 두 파일을 지우면 마지막 줄이 되살린다. 이후에는 Git이 무시한다.)
2. 백엔드·화면 다시 빌드: `.\scripts\run\build.ps1`, 그리고 화면은 `start-ui.ps1 -Target daily -Rebuild`.
3. 실행: 4절 순서.
4. 동작 확인(로그인 후):
   - 대시보드가 열리고 이슈 29개가 보인다.
   - 이슈 상세에서 검수 사유·비교 후보 열이 보인다(daily 데이터에는 검수 대기 이슈가 없어 비어 있는 것이 정상).
   - 로그인하지 않은 새 창에서 `/api/admin/issues`가 401, http://127.0.0.1:3000/news 가 열린다.
   - 화면 위에 노란 "로컬 검증 모드" 띠가 **없어야** 한다(daily는 검증 모드가 아님).
5. 필요하면 전체 테스트: `backend`에서 `mvn test`(테스트 DB `aicontent_test`, 유료 호출 없음).

## 6. 되돌리는 방법

- **코드:** GitHub에서 병합 커밋을 되돌리는 PR을 만든다(`git revert -m 1 <병합 커밋>` 후 push). 급하면 작업 폴더에서 `git checkout 885b2f5` 후 `build.ps1`·`start-ui.ps1 -Rebuild`로 이전 버전을 띄운다.
- **DB:** 스키마가 바뀌지 않았으므로 되돌릴 것이 없다. 관리자 조작으로 데이터를 되돌려야 하면 병합 전 백업을 새 DB로 복원해 비교한다: `createdb ... aicontent_main_restore` → `pg_restore -d aicontent_main_restore <백업>`. 기존 DB를 지우거나 덮어쓰기 전에는 먼저 확인한다.
- **로그인 생략:** `local-verify` 프로필을 켜지 않는 한 동작하지 않는다. 문제가 생기면 그 프로필로 띄운 검증 서버만 끄면 된다.

## 7. 임시 작업 폴더 의존 정리

| 지금 위치 | 문제 | 옮길 곳 |
|---|---|---|
| 작업 세션 임시 폴더의 `int`, `q4`, `view` 작업 트리 | 세션 정리 때 사라질 수 있음. 8090/3090이 여기서 실행됐음 | 코드는 모두 GitHub에 push됨. 병합 후 사용자 작업 폴더(`main`)에서 실행. 작업 트리는 병합 뒤 `git worktree prune`으로 정리 |
| 임시 폴더의 시작 스크립트(`view-env`, `lv/*.ps1`) | 경로가 임시 폴더에 묶임 | `scripts/run/*.ps1`(Git 관리, 프로젝트 기준 상대 경로) + `.local/run/machine.ps1`(PC별 경로, Git 제외) |
| 임시 폴더의 UI 빌드 사본 | 위와 같음 | `start-ui.ps1`이 `.local/run/ui-<환경>`에 만든다 |
| 백엔드 jar | 위와 같음 | `build.ps1`이 `.local/run/platform-backend.jar`로 복사 |
