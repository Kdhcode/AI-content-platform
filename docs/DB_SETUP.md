# 현재 DB 서버 구성

2026-10-07에 실행 중인 PostgreSQL 프로세스, 서버 설정, SQL 조회로 확인했다.

## 어디서 실행되는가

현재 DB는 **사용자의 Windows PC에서 직접 실행되는 로컬 PostgreSQL**이다.
Docker 컨테이너, 클라우드 DB, Windows 서비스로 등록한 DB가 아니다.
Docker 실행 파일을 찾지 못해 설치 프로그램 대신 프로젝트 내부에 바이너리를 내려받아 실행했다.

| 항목 | 확인 결과 |
|---|---|
| PostgreSQL | 16.4, Windows x64 |
| 벡터 확장 | pgvector 0.8.0 |
| 접속 주소 | `127.0.0.1:55432` |
| DB 사용자 | `aicontent` |
| 실행 파일 | `.local/pg16/pgsql/bin/postgres.exe` |
| 데이터 파일 | `.local/pgdata/` |
| DB 설정 | `.local/pgdata/postgresql.conf` |
| 인증 설정 | `.local/pgdata/pg_hba.conf` |
| 서버 로그 | `.local/postgres-server.log` |
| 초기화 기록 | `.local/initdb.log` |
| 인증 방식 | loopback 접속에 `trust`: 이 개발 DB에는 비밀번호 없이 연결 가능 |
| 외부 접속 | `listen_addresses=127.0.0.1`이므로 다른 PC에서 직접 접속 불가 |

`.local/`은 Git에서 제외했다. DB 데이터는 서버를 꺼도 해당 폴더에 남는다.
PC를 재부팅하면 자동 시작되는 서비스가 아니므로 아래 시작 명령을 다시 실행해야 한다.

## 어떻게 만들었는가

1. PostgreSQL Windows 바이너리 ZIP을 EDB 배포처에서 받아 `.local/pg16/pgsql`에 압축 해제했다.
   GUI 도구인 pgAdmin은 실행에 필요하지 않아 제외했다.
2. PostgreSQL 16용 pgvector 패키지에서 `vector.dll`을 `lib/`에,
   `vector.control`과 확장 SQL을 `share/extension/`에 복사했다.
   임시 검증용 확장 바이너리 출처는 `portalcorp/pgvector_compiled`이며 공식 pgvector Windows 배포본은 아니다.
3. 빈 폴더에 `initdb`로 새 클러스터를 생성했다.
4. `pg_ctl`로 `127.0.0.1:55432`에서 시작했다. 기존 기본 포트 5432를 사용하지 않는다.
5. `createdb`로 앱 DB, 통합 테스트 DB, SQL 검증 DB를 별도로 만들었다.
6. 백엔드에 `DB_URL=jdbc:postgresql://127.0.0.1:55432/aicontent`를 지정했다.
   Spring Boot 시작 시 Flyway가 `V1__phase1_schema.sql`을 실제 PostgreSQL에 적용했다.

실행한 핵심 명령은 프로젝트 루트 기준으로 다음과 같다. **`initdb`와 `createdb`는 최초 생성 시에만 실행한다.**

```powershell
& .local/pg16/pgsql/bin/initdb.exe -D .local/pgdata -U aicontent -A trust -E UTF8 --locale=C
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata -l .local/postgres-server.log -o '-p 55432 -h 127.0.0.1' -w start
& .local/pg16/pgsql/bin/createdb.exe -h 127.0.0.1 -p 55432 -U aicontent aicontent
& .local/pg16/pgsql/bin/createdb.exe -h 127.0.0.1 -p 55432 -U aicontent aicontent_test
& .local/pg16/pgsql/bin/createdb.exe -h 127.0.0.1 -p 55432 -U aicontent aicontent_sql
```

## DB별 용도와 검증

- `aicontent`: 현재 관리자 화면이 조회하는 앱 DB. 실제 RSS 기사와 스텁 AI 결과가 저장된다.
- `aicontent_test`: Maven 통합 테스트용. 테스트가 테이블을 비우므로 앱 DB와 분리했다.
- `aicontent_sql`: 스키마 제약·쿼리 검증 SQL용. 앱 데이터에 영향을 주지 않는다.

앱 DB에서 다음을 확인했다.

- `flyway_schema_history`: 버전 `1`, 설명 `phase1 schema`, 적용 성공 `true`.
- 업무 테이블 11개와 Flyway 이력 테이블 1개, 총 12개.
- `news_article.embedding`과 `issue.embedding`: 실제 `vector(1536)` 컬럼.
- `ix_issue_embedding_hnsw`: 실제 HNSW 벡터 검색 인덱스.
- 관리자 대시보드의 DB 이름·포트·버전·스키마 표시는 실행 중인 DB에 SQL로 조회한 결과다.

## 접속·시작·중지

프로젝트 루트에서 실행한다.

```powershell
# 서버 상태
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata status

# 종료된 서버 다시 시작
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata -l .local/postgres-server.log -o '-p 55432 -h 127.0.0.1' -w start

# SQL 콘솔
& .local/pg16/pgsql/bin/psql.exe -h 127.0.0.1 -p 55432 -U aicontent -d aicontent

# 데이터 유지하면서 종료
& .local/pg16/pgsql/bin/pg_ctl.exe -D .local/pgdata -m fast -w stop
```

IntelliJ Database 창이나 다른 PostgreSQL 클라이언트에서는 Host `127.0.0.1`, Port `55432`,
Database `aicontent`, User `aicontent`, 비밀번호 없음으로 연결한다.

이 구성은 로컬 개발·검증용이다. 외부에 서비스할 때는 비밀번호 인증과 별도 운영 계정으로 구성한다.
저장소의 `docker-compose.yml`은 별도의 표준 개발 환경으로, Docker 사용 시
`pgvector/pgvector:pg16`과 포트 **5432**를 사용한다. 현재 실행 중인 네이티브 DB의 **55432**와 구분한다.
