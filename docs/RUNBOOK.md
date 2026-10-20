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
| verify-content | 13093·18093 | `aicontent_verify_content`(시나리오 DB 복사본) | stub, 작업자 끔 | 로그인 생략 가능 + 실제 계정 로그인(역할 확인용, 계정은 검증 DB에만) |

로그인 생략은 검증 환경에만 남긴다. daily·dev는 `local-verify` 프로필을 쓰지 않으므로 기존 인증이 그대로다(2026-10-15 확인: daily에서 로그인 없이·틀린 비밀번호로 관리자 API 401, `viewer` 계정 200).

## 3. 처음 한 번 준비

0. **새 PC면 먼저 점검:** 프로젝트 폴더에서 `.\scripts\run\check-env.ps1`(원격 브랜치까지 보려면 `-Fetch`). 읽기 전용이며 아무것도 설치·시작하지 않는다. Git 브랜치·미커밋 변경·현재 브랜치에 없는 원격 커밋(현재 브랜치가 추적하는 원격과 비교. 추적 원격이 없으면 `origin`, 그것도 없으면 하나뿐인 원격 — Fork 등으로 받아 원격 이름이 `origin`이 아닌 경우 포함), Java(pom.xml 기준 21+)·Maven·Node(next 15.5 기준 18.18+·19.8+·20+)·`admin-ui/node_modules`(설치된 next가 `package.json`과 다르면 `[MISSING]`), `127.0.0.1:$DbPort`의 PostgreSQL과 환경별 DB·pgvector·스키마 버전, Docker, 터미널 환경 변수(값은 출력하지 않고 설정 여부만), 실행 중인 서버와 health를 보여 준다. 필수 항목이 빠지면 `[MISSING]`과 종료 코드 1.
   - `.local/`(프로젝트 DB 바이너리·데이터, Maven, `machine.ps1`, 계정 비밀번호 파일)은 Git에 없다. 새 PC에는 직접 준비해야 하고, 이전 PC의 DB 데이터(`aicontent_main` 등)는 옮기지 않으면 없다.
   - 이전 PC의 `.local/`을 통째로 옮기는 방법(둘 다 Windows x64·PostgreSQL 16이면 가능): ① 이전 PC에서 미커밋·미push 작업이 없는지 확인 ② 이전 PC DB를 먼저 멈춤(`pg_ctl ... -m fast -w stop`, 켠 채 `pgdata`를 복사하면 손상될 수 있음) ③ USB 등으로 복사(비밀번호 파일이 있으니 Git·공유 드라이브 금지) ④ 새 PC에서 `.local/run/machine.ps1`의 경로를 새 PC에 맞게 수정 ⑤ 이전 경로를 가리키는 `.local/run/ui-*`를 지우고(`start-ui.ps1`이 다시 만듦) `build.ps1`로 jar를 다시 만든 뒤 `check-env.ps1`. 다른 방법은 `pg_dump`/`pg_restore`.
1. `admin-ui`에서 `npm ci`(잠금 파일 그대로 설치. 이미 했으면 생략). 의존성이 바뀐 커밋을 받은 뒤(예: 2026-10-19 next 14 → 15)에는 다시 실행하고, 화면은 `start-ui.ps1 -Target <환경> -Rebuild`로 한 번 다시 빌드한다(`.local/run/ui-*`에 예전 빌드가 남아 있음).
2. `.local/run/machine.ps1`을 만든다(Git 제외). 기본값(`.local` 안의 PostgreSQL·Maven, DB 포트 55432) 다음에 읽히므로 아래 값을 덮어쓸 수 있다(2026-10-19부터. 그 전에는 `$PgBin`·`$MavenCmd`를 덮어써도 기본값으로 되돌아갔다). 예:
   ```powershell
   $env:JAVA_HOME = 'C:\Users\User\.jdks\temurin-25.0.4.1'
   $NodeDir = 'C:\Users\User\AppData\Local\JetBrains\IntelliJIdea2026.2\acp-agents\.runtimes\node\24.19.0'
   $MavenCmd = 'C:\tools\apache-maven-3.9.9\bin\mvn.cmd'   # 없으면 PATH의 mvn
   $PgBin = 'C:\tools\pgsql\bin'; $PgData = 'C:\tools\pgdata'
   $DbPort = 5432                                         # docker compose(postgres 5432)를 쓸 때. 이때는 DB_PASSWORD 필요
   ```
   DB 주소는 항상 `127.0.0.1`이고 포트만 바꿀 수 있다. docker compose를 쓰면 `aicontent_test`는 `docker/init-test-db.sql`이 만들고, 다른 환경 DB(`aicontent_main` 등)는 직접 만든다.
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

> 이 절과 6절은 2026-10-15 PR #1(`claude/integration-review`) 기준이다. **`claude/content-a`(V4~V7) 병합은 5-1절**을 따른다.

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

## 5-1. `claude/content-a` 병합 후 daily DB V3 → V7 (콘텐츠 A 묶음)

**바뀌는 것:** 병합한 코드로 백엔드를 처음 띄우는 순간 Flyway가 `aicontent_main`(현재 V3)에 V4~V7을 한 번에 적용한다. 따로 실행하는 마이그레이션 명령은 없다.
- V4: 새 테이블 `content`·`content_source`·`content_link`. V5·V6: 이 테이블들의 제약 교체
- V7: `issue`에 `change_seq`·`change_seq_at` 열(기본값 0), 함수 2개(`issue_raise_change_seq`, `issue_article_raise_change_seq`), 트리거 3개(`trg_issue_change_seq` — `issue` BEFORE UPDATE, `trg_issue_article_change_seq`·`trg_issue_article_move_change_seq` — `issue_article` 추가·삭제·이동 시 해당 `issue` 행 UPDATE), `content.origin_ack_seq`
- 기존 테이블의 행은 바뀌지 않는다(`aicontent_main` 복사본으로 기존 11개 테이블 행 md5 동일 확인, TASK.md 15~18절). 이후 이슈 갱신·기사 연결마다 트리거가 돈다
- **되돌리는 마이그레이션은 없다.** 예전 코드로 돌아가도 DB는 V7로 남는다(아래 "복원")

**순서 (본인 터미널, daily를 쓰지 않는 시간에)**
1. daily 백엔드가 떠 있으면 Ctrl+C로 멈춘다(작업 중 데이터가 백업 뒤에 바뀌지 않게).
2. **백업:**
   ```powershell
   $d = Get-Date -Format 'yyyyMMdd-HHmm'
   & .local\pg16\pgsql\bin\pg_dump.exe -h 127.0.0.1 -p 55432 -U aicontent -Fc -f ".local\backup-aicontent_main-$d-V3.dump" aicontent_main
   ```
   백업 확인: `pg_restore.exe --list <파일> | Select-String 'TABLE DATA'`에 `issue`·`news_article`·`issue_article`·`app_user` 등이 보이고, 파일이 0바이트가 아닌지. `.local/`은 Git 제외(비밀번호 해시가 들어 있음, 공유 드라이브에 올리지 않음).
3. (권장) **리허설:** 백업을 새 DB로 복원하고 병합한 코드를 그 DB로 한 번 띄워 V4~V7이 적용되는지 본다. 스크립트 환경(`start-backend.ps1`)은 DB가 고정이라, 15~18절처럼 `java -jar ... --spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/aicontent_main_v7check`로 다른 포트에 띄운다(daily와 같은 `--app.ai.provider=none`). 확인 뒤 그 DB는 지워도 된다.
   ```powershell
   & .local\pg16\pgsql\bin\createdb.exe -h 127.0.0.1 -p 55432 -U aicontent aicontent_main_v7check
   & .local\pg16\pgsql\bin\pg_restore.exe -h 127.0.0.1 -p 55432 -U aicontent -d aicontent_main_v7check --no-owner <백업 파일>
   ```
4. 코드 받기·빌드: `git checkout main`, `git pull`, `admin-ui`에서 `npm ci`, `.\scripts\run\build.ps1`, `.\scripts\run\check-env.ps1`.
5. 실행: `start-backend.ps1 -Target daily`(이때 V4~V7 적용, 로그에 `Migrating schema "public" to version "4 - content"` … `"7 - issue change seq"`), `start-ui.ps1 -Target daily -Rebuild`.

**적용 후 확인**
```powershell
$q = { param($sql) & .local\pg16\pgsql\bin\psql.exe -h 127.0.0.1 -p 55432 -U aicontent -d aicontent_main -Atc $sql }
& $q "select version, success from flyway_schema_history order by installed_rank"     # 1~7 모두 t
& $q "select tgname from pg_trigger where tgname like 'trg_issue%change_seq' order by 1" # 트리거 3개
& $q "select count(*) from issue; select count(*) from news_article; select count(*) from issue_article"  # 백업 전과 같은 수
```
화면: 로그인 후 대시보드·이슈 목록이 전과 같고, `/contents`가 빈 목록으로 열리며, 로그인 없는 창에서 `/api/admin/contents`가 401, `/news`가 열린다. 화면 위에 "로컬 검증 모드" 띠가 없어야 한다.

**문제가 생겼을 때 복원** — 코드만 되돌리면 DB는 원래대로 돌아오지 않는다(2026-10-19 확인: V7 DB 복사본에 `main` `8e0c6d7`을 띄우면 Flyway가 "version (7) newer than the latest available migration (3)" 경고만 내고 시작하며, `content*` 테이블·`issue.change_seq` 열·트리거가 그대로 남아 이슈를 고치면 `change_seq`가 계속 올라감). DB까지 V3로 돌리려면 백업으로 바꾼다:
1. daily 백엔드를 멈춘다(연결이 남아 있으면 이름 변경이 안 됨).
2. 백업을 새 DB로 복원: `createdb ... aicontent_main_restore` → `pg_restore ... -d aicontent_main_restore --no-owner <백업>`. `aicontent_main_restore`에서 `flyway_schema_history` 최고 버전이 3이고 위 행 수가 백업 전과 같은지 확인.
3. 바꿔 끼우기(지우지 않고 이름만):
   ```powershell
   & .local\pg16\pgsql\bin\psql.exe -h 127.0.0.1 -p 55432 -U aicontent -d postgres -c "ALTER DATABASE aicontent_main RENAME TO aicontent_main_v7_failed_<날짜>" -c "ALTER DATABASE aicontent_main_restore RENAME TO aicontent_main"
   ```
4. 예전 코드로 빌드·실행: `git checkout 8e0c6d7`(또는 병합 되돌리기 커밋), `build.ps1`, `start-ui.ps1 -Target daily -Rebuild`, `start-backend.ps1 -Target daily`.
5. 백업 뒤에 생긴 데이터(새 콘텐츠, 그 사이 수집·처리한 기사, 관리자 조작)는 복원한 DB에 없다. 필요하면 `aicontent_main_v7_failed_<날짜>`에서 확인한다. 원인을 확인하기 전에는 그 DB를 지우지 않는다.

**리허설 기록(2026-10-19, daily·`aicontent_main`은 건드리지 않음):** `aicontent_verify_real`(V3, 실제 데이터 복사본, 이슈 29·기사 35)을 `aicontent_rehearsal_main`으로 복사해 위 순서를 그대로 실행 — 백업(TABLE DATA 12개) → `claude/content-a` jar(AI `none`)로 기동 → 로그 `Migrating schema "public" to version "4 - content"` … `"7 - issue change seq"`, `Successfully applied 4 migrations … now at version v7`(0.2초) → 확인 SQL 통과(1~7 `t`, 트리거 3개, 이슈·기사·연결 수 29/35/35 그대로) → 복원·이름 바꾸기 → V3, `content` 테이블·트리거 없음, 수 그대로. `aicontent_rehearsal_main_v7_failed`(V7)는 남겨 둠.

## 6. 되돌리는 방법

- **코드:** GitHub에서 병합 커밋을 되돌리는 PR을 만든다(`git revert -m 1 <병합 커밋>` 후 push). 급하면 작업 폴더에서 `git checkout 885b2f5` 후 `build.ps1`·`start-ui.ps1 -Rebuild`로 이전 버전을 띄운다.
- **DB:** (PR #1 기준) 스키마가 바뀌지 않았으므로 되돌릴 것이 없다. V4~V7 이후는 5-1절 "복원" — 코드만 되돌려서는 DB가 V3로 돌아가지 않는다. 관리자 조작으로 데이터를 되돌려야 하면 병합 전 백업을 새 DB로 복원해 비교한다: `createdb ... aicontent_main_restore` → `pg_restore -d aicontent_main_restore <백업>`. 기존 DB를 지우거나 덮어쓰기 전에는 먼저 확인한다.
- **로그인 생략:** `local-verify` 프로필을 켜지 않는 한 동작하지 않는다. 문제가 생기면 그 프로필로 띄운 검증 서버만 끄면 된다.

## 7. 임시 작업 폴더 의존 정리

| 지금 위치 | 문제 | 옮길 곳 |
|---|---|---|
| 작업 세션 임시 폴더의 `int`, `q4`, `view` 작업 트리 | 세션 정리 때 사라질 수 있음. 8090/3090이 여기서 실행됐음 | 코드는 모두 GitHub에 push됨. 병합 후 사용자 작업 폴더(`main`)에서 실행. 작업 트리는 병합 뒤 `git worktree prune`으로 정리 |
| 임시 폴더의 시작 스크립트(`view-env`, `lv/*.ps1`) | 경로가 임시 폴더에 묶임 | `scripts/run/*.ps1`(Git 관리, 프로젝트 기준 상대 경로) + `.local/run/machine.ps1`(PC별 경로, Git 제외) |
| 임시 폴더의 UI 빌드 사본 | 위와 같음 | `start-ui.ps1`이 `.local/run/ui-<환경>`에 만든다 |
| 백엔드 jar | 위와 같음 | `build.ps1`이 `.local/run/platform-backend.jar`로 복사 |
