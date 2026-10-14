# 상품 조회 쿼리 성능 측정 보고서

> 측정 기록 전용 문서다. 설계·구현 기준이 아니므로 개발 작업 중에는 참고하지 않는다.
> 측정 원본(EXPLAIN 전문 포함)은 [query-performance-raw.md](query-performance-raw.md)에 쌓인다.

## 1. 요약

- 상품 목록·좋아요 조회 쿼리는 모두 인덱스를 쓰지 못하고 테이블·인덱스 전체를 읽는다. 그래서 실행 시간이 데이터 양에 비례해 늘어난다.
- 상품을 10만 → 1,000만 개로 100배 늘리면, 최신순·가격순 목록 1페이지는 **약 40ms → 3.4~4.5초**로 100배 가까이 느려진다.
- 좋아요순 정렬은 상품마다 좋아요 테이블 전체를 다시 읽는 구조라 **10만 개에서도 30초 안에 끝나지 않는다**.
- 상품 상세의 좋아요 수 1건 조회도 1,000만 건 기준 **1.3초**가 걸린다. `product_id`로 찾을 수 있는 인덱스가 없기 때문이다.

| 구분 | 10만 개 | 1,000만 개 | 원인 |
| --- | ---: | ---: | --- |
| 최신순 1페이지 | 45ms | 4,510ms | 전체 스캔 + filesort |
| 좋아요순 1페이지 | 30초 초과 | 30초 초과 | 상품마다 좋아요 전체 스캔 |
| 상품 상세 좋아요 수 | 101ms | 1,318ms | `product_id` 선두 인덱스 없음 |

## 2. 측정 환경

| 항목 | 값 |
| --- | --- |
| 장비 | 클라우드 리눅스 VM (2 vCPU, 메모리 8GB) |
| DB | MySQL 8.0.46, 기본 설정 (`innodb_buffer_pool_size` 128MB, `innodb_redo_log_capacity` 100MB) |
| 스키마 | 애플리케이션 JPA 엔티티와 같은 형태. 인덱스는 PK와 `product_likes (user_id, product_id)` 유일 인덱스뿐 |
| 측정 DB | `loopers_perf` (애플리케이션 local DB `loopers`와 분리) |

> 1절과 5.1~5.4절 수치는 seed 규칙 보강(3.1) 전에 클라우드에서 잰 참고값(reference-cloud)이며 그대로 둔다.
> 개선 전후 비교의 기준은 5.0절의 baseline이다. 맥의 Docker MySQL에 측정 도구가 접속할 수 없어, 사용자 승인 후 **위 표와 같은 클라우드 환경에서 실제 앱(commerce-api)으로** 측정했다.
> 개선 후(after) 측정도 같은 클라우드 환경·계정·인자로 다시 실행해 비교한다. 장비가 바뀌면 baseline부터 다시 잰다.

## 3. 데이터 생성

### 3.1 규모와 분포

| 테이블 | 10만 규모 (기본값) | 1,000만 규모¹ | 분포·규칙 |
| --- | ---: | ---: | --- |
| users | 50,000 | - | 1~N번. 고객 조회 API가 `X-USER-ID` 사용자의 존재를 확인하므로 만든다 |
| brands | 200 (삭제 5) | 200 (삭제 5) | 20·60·100·140·180번 삭제. 측정에 쓰는 브랜드 1·200은 삭제하지 않는다 |
| products | 100,000 (미삭제 92,859) | 10,000,000 | 약 5% 무작위 삭제 + **삭제된 브랜드의 상품은 전부 삭제**(3주차 계약). 앞 번호 브랜드에 몰림 |
| product_likes | 974,368 | 9,969,594 | 앞 번호 상품에 몰림. 같은 사용자-상품 쌍은 제외. 모든 user_id가 users에 있다 |
| 좋아요 사용자 | 50,000명 | 500,000명 | 사용자당 평균 약 20건 |

| 쏠림 정도 | 10만 규모 | 1,000만 규모¹ |
| --- | ---: | ---: |
| 상품이 가장 많은 브랜드 (1, 미삭제 기준) | 6,694개 | 약 70만 개 |
| 측정용 작은 브랜드 (200, 미삭제 기준) | 232개 | 약 2.5만 개 |
| 좋아요가 가장 많은 상품 (1) | 34,627건 | 151,236건 |

¹ 1,000만 규모는 seed 규칙 보강(users 생성, 삭제 브랜드 상품 삭제) 전에 만든 데이터 기준이다.

**스키마.** `seed.sql`의 `CREATE TABLE`은 각 `JpaEntity`를 Hibernate가 MySQL에 매핑하는 형태와 컬럼명·타입·NULL 여부를 맞췄다
(`Long @Id IDENTITY` → `bigint auto_increment`, `boolean` → `bit`, `Instant` → `datetime(6)`, `String(length=100)` → `varchar(100)`, 모든 컬럼 NOT NULL).
`product_likes` 유일 제약 이름만 다르다(Hibernate는 임의 이름 `UK...`).
실제 Hibernate DDL과의 대조는 `run.sh ddl-check`로 한다. 빈 임시 DB에 앱을 `ddl-auto=create`로 띄운 스키마와 `seed.sql`의 `CREATE TABLE`을 `SHOW CREATE TABLE`로 나란히 출력하고,
컬럼(이름·타입·NULL·auto_increment)과 인덱스(유일 여부·컬럼)를 비교한다. 컬럼 순서와 유일 제약 이름은 비교에서 뺀다. 임시 DB는 끝나면 지운다.

**검증.** seed 마지막에 테이블별 건수와 규칙 위반 건수(삭제 브랜드의 미삭제 상품, users에 없는 좋아요 사용자)를 출력한다. 위반은 0이어야 한다.

**분포를 쏠리게 만든 이유.** 값이 고르게 퍼져 있으면 어떤 브랜드로 필터해도 결과 수가 비슷해서, 인덱스 효과가 실제와 다르게 보일 수 있다. 실제 쇼핑몰처럼 큰 브랜드와 인기 상품에 데이터가 몰린 상태를 만들고, 큰 브랜드(1)와 작은 브랜드(200)를 둘 다 측정한다.

- `created_at`: 2025년 1년 안에서 무작위. ID 순서와 최신순이 다르게 해서, PK 순서 덕분에 우연히 정렬되는 일을 막는다.
- 가격: 1,000~500,000원 (100원 단위)

### 3.2 생성 방법

애플리케이션 API를 반복 호출하거나 한 줄씩 INSERT하지 않는다. DB 안에서 `INSERT ... SELECT` 한 문장으로 10만 행씩 만든다.

**① 숫자 시퀀스 테이블**: 0~9를 담은 테이블을 6번 크로스 조인해 0~99,999 번호를 만든다. 이 번호가 "행 생성기" 역할을 한다.

```sql
INSERT INTO perf_seq (n)
SELECT t.n FROM (
    SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 + f.d*100000 AS n
    FROM perf_digits a, perf_digits b, perf_digits c, perf_digits d, perf_digits e, perf_digits f
) t WHERE t.n < 100000;
```

**② 시퀀스에서 행 만들기**: 컬럼 값은 `RAND()`로 만들고, 거듭제곱(`POW(RAND(), 2)`)을 씌워 값이 앞 번호로 몰리게 한다. `{base}`·`{limit}`·`{seed}`는 청크마다 바뀌는 값이다.

```sql
INSERT INTO products (id, brand_id, created_at, deleted, name, price, stock)
SELECT {base} + n + 1,
       1 + FLOOR(200 * POW(RAND({seed1}), 2)),                       -- 앞 번호 브랜드에 몰림
       TIMESTAMP('2025-01-01') + INTERVAL FLOOR(RAND({seed2}) * 365*24*3600) SECOND,
       RAND({seed3}) < 0.05,                                         -- 약 5% 삭제
       CONCAT('상품-', {base} + n + 1),
       (10 + FLOOR(RAND({seed4}) * 4991)) * 100,
       ({base} + n) % 1000
FROM perf_seq WHERE n < {limit};
```

**③ 고정 시드**: `RAND(42)`처럼 시드를 SQL 리터럴로 고정한다. 몇 번을 다시 만들어도 같은 데이터가 나오므로, 개선 전후를 같은 데이터로 비교할 수 있다.

### 3.3 대량 삽입 부하 줄이기

1,000만 건을 처음 넣었을 때 24분 넘게 걸렸고, MySQL 로그에 redo 로그 공간 부족 경고가 이어졌다. 두 가지를 바꿨다.

1. **10만 건씩 나눠 넣고 바로 커밋한다.** 한 트랜잭션으로 1,000만 건을 넣으면 undo 로그(롤백용)와 버퍼 풀 사용량이 한꺼번에 불어난다. 청크 사이에 쉬는 시간(`CHUNK_SLEEP`)도 줄 수 있다.
2. **좋아요를 유일 인덱스 `(user_id, product_id)` 순서로 정렬해 넣는다.** 이 인덱스는 버퍼 풀(128MB)보다 훨씬 크다. 무작위 순서로 넣으면 B-Tree 곳곳의 페이지를 디스크에서 읽어 와 중간에 끼워 넣어야 한다. 정렬해 넣으면 인덱스 끝에 이어 붙기만 하므로 필요한 페이지가 메모리에 머문다. 이를 위해 사용자를 1번부터 차례로 배정했다.

| 1,000만 건 생성 방식 | 소요 시간 |
| --- | ---: |
| 청크 커밋, 좋아요 무작위 순서 | 24분 34초 |
| 청크 커밋 + 좋아요 정렬 삽입 | 1분 56초 |
| 위 방식 + 청크 사이 0.3초 휴식 | 2분 57초 |

> 기본 설정에서는 정렬 삽입을 해도 redo 로그 경고가 일부 남는다. 에러가 아니라 MySQL이 쓰기 속도를 잠시 조절한다는 뜻이다. 생성된 데이터 크기는 약 1.5GB다 (products 724MB, product_likes 720MB).

## 4. 측정 방법

### 4.1 SQL 단위 (`run.sh bench`)

| 이름 | 쿼리 | 대응 코드 |
| --- | --- | --- |
| Q1 / Q2 | 최신순 1페이지 (전체 / 브랜드 1) | `ProductPersistenceAdapter.search` (`latest`) |
| Q3 / Q4 | 가격 낮은순 1페이지 (전체 / 브랜드 1) | 같음 (`price_asc`) |
| Q5 | 최신순 깊은 페이지 (offset 50,000) | 같음 |
| Q6 / Q7 / Q8 | 좋아요순 1페이지 (전체 / 브랜드 1 / 브랜드 200) | 같음 (`likes_desc`) |
| Q9 | 목록 상품 20개의 좋아요 수 일괄 집계 | `LikePersistenceAdapter.countByProductIds` |
| Q10 | 상품 상세의 좋아요 수 (인기 상품 1) | 같음 (ID 1개) |

- 쿼리마다 워밍업 1회 후 `RUNS`회(기본 10) 실행한다. 첫 실행에는 디스크에서 데이터를 읽어 오는 비용이 섞이기 때문이다.
- 워밍업이 `SLOW_MS`(기본 10초)를 넘는 느린 쿼리는 `SLOW_RUNS`회(기본 3)만 실행하고, 표에 실제 실행 횟수를 적는다. `QUERY_RUNS`로 쿼리별 횟수를 직접 정할 수도 있다.
- 시간은 `SHOW PROFILES`의 서버 실행 시간이다. 클라이언트·네트워크 시간이 빠지므로 쿼리 자체의 비용만 비교된다.
- 1회가 제한 시간(`TIMEOUT_MS`, 기본 30초)을 넘으면 `TIMEOUT`으로 기록하고 반복을 건너뛴다. `QUERY_TIMEOUTS`로 쿼리별 제한을 정한다.
- **Q6·Q7은 제한 60초(기본 `QUERY_TIMEOUTS`)로 두고 `TIMEOUT(>60000)`을 하한값으로, `EXPLAIN`만 기록한다.** 클라우드 10만 규모에서 Q7 1회가 722초였고 Q6은 그보다 훨씬 길어 실측하지 않는다. 결과가 없으므로 스냅샷 비교도 할 수 없다.
- **좋아요순(`likes_desc`) 결과 비교는 Q8(SQL)과 R3(API) 스냅샷으로 한다.** 둘 다 같은 정렬을 작은 브랜드(200)로 끝까지 실행하므로 결과 순서와 좋아요 수를 비교할 수 있다.
- 쿼리마다 `EXPLAIN`(예상 계획)과 `EXPLAIN ANALYZE`(실제 실행 결과)를 함께 남긴다.

### 4.2 요청 단위 (`run.sh api-bench`)

앱(commerce-api)을 `loopers_perf`에 연결해 띄우고 실제 API 응답 시간과 요청당 SQL 수를 잰다. 설정 파일은 바꾸지 않고 local 프로필 위에 명령행 인자로만 덮어쓴다.

| 인자 | 이유 |
| --- | --- |
| `--spring.jpa.hibernate.ddl-auto=none` | local 기본값 `create`면 기동 시 측정 DB 테이블이 지워진다 |
| `--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf` | 측정 DB 연결 |
| `--datasource.mysql-jpa.main.username/password` (기본 root) | compose의 `application` 계정은 `loopers` DB 권한만 있다 |
| `--spring.jpa.show-sql=false` | SQL 콘솔 출력 비용을 응답 시간에서 뺀다 |

**측정 조건: 개선 후(after) 측정도 같은 계정(root)·같은 인자로 실행한다.** 계정이나 옵션이 다르면 연결·로깅 비용이 달라져 비교할 수 없다. 섹션에 실제 인자를 함께 기록한다.

| 요청 | 경로 (`X-USER-ID: 1`) |
| --- | --- |
| R1 (과제 측정 대상) | `GET /api/v1/products?brandId=1&sort=price_asc&page=0&size=20` |
| R2 | `GET /api/v1/products?brandId=200&sort=price_asc&page=0&size=20` |
| R3 | `GET /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` |
| R4 | `GET /api/v1/products/1` |

- 지연: curl 순차 1개로 워밍업 20회 + 측정 200회, `time_total`의 p50·p95·p99·최대(ms). 첫 요청이 5초(`API_SLOW_MS`)를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '워밍업'·'표본' 열이 요청별 실제 횟수다.
- 데이터 보존: 앱 기동 전후로 `loopers_perf.products` 건수를 출력하고 기록한다. 시작 전 0건이면 측정하지 않고, 전후 건수가 다르면 측정 실패로 본다(인자가 빠져 `ddl-auto=create`로 뜨는 사고를 막기 위해서다).
- 요청당 SQL 수: 측정 직전 `performance_schema.events_statements_summary_by_digest`를 비우고, 측정 후 앱 커넥션(기본 DB가 `loopers_perf`)이 실행한 문장을 digest별 실행 수·총 시간으로 집계해 요청 수로 나눈다. 측정용 mysql 클라이언트는 기본 DB 없이 접속하므로 집계에서 빠진다.
- 워밍업을 포함한 모든 응답이 200이어야 한다. 401·500 등이 섞이거나 데이터 건수가 바뀌면 섹션 제목에 실패를 표시하고 종료 코드 1로 끝나며, 그 측정은 비교에 쓰지 않는다.
- 측정이 끝나거나 중간에 실패해도 띄운 앱 프로세스는 종료한다.

### 4.3 결과 스냅샷과 비교 (`run.sh compare`)

성능 비교는 **같은 결과를 내는 쿼리끼리**만 의미가 있다. 인덱스나 쿼리를 바꾼 뒤 결과 순서·값이 달라졌다면 더 빨라진 것이 아니라 다른 일을 한 것이다.

- `bench`는 쿼리별 결과를 `docs/week4/perf-results/<label>/sql-<쿼리>.tsv`로 저장한다. 목록 쿼리는 순서대로 `id·price·created_at`, 좋아요순은 좋아요 수까지, 집계 쿼리는 `product_id·total`(product_id 순 정렬)이다. TIMEOUT이면 결과 없음으로 표시한다.
- `api-bench`는 요청별 마지막 응답의 `id·price·likeCount`를 `api-<요청>.tsv`로 저장한다.
- `run.sh compare <기준> <비교>`는 두 스냅샷을 비교해 다른 쿼리·요청과 실제로 다른 행을 출력한다. **결과가 다른 쿼리·요청은 성능 비교 대상에서 제외한다.** 한쪽이 TIMEOUT이면 비교 불가로 표시한다.

### 4.4 실행

```bash
# 저장소 루트에서, docker/infra-compose.yml의 mysql·redis가 떠 있는 상태
scripts/perf/run.sh ddl-check                          # (처음 한 번) seed 스키마가 앱 스키마와 같은지 확인
scripts/perf/run.sh seed                               # 10만 규모 (users 5만 포함)
scripts/perf/run.sh bench baseline                     # SQL 단위. Q6·Q7은 60초 TIMEOUT 하한값과 EXPLAIN만
scripts/perf/run.sh api-bench baseline                 # 요청 단위 (bootJar 빌드 후 앱을 띄우고 끝나면 종료)
scripts/perf/run.sh compare baseline after-index       # 개선 후 결과가 같은지 확인
```

## 5. 개선 전 결과와 원인

### 5.0 baseline (2026-10-14, 클라우드, 실제 앱)

- 환경: Linux x86_64, Intel Xeon 2.10GHz 2코어, 메모리 8GB, MySQL 8.0.46(기본 설정), Java 21.0.12, Redis 7(마스터 6379·복제 6380)
- 앱: `commerce-api` 소스(`83d186a`) bootJar를 local 프로필 + 4.2절 인자(root, `ddl-auto=none`, `show-sql=false`)로 기동
- 데이터: 10만 규모 seed (users 50,000 / 미삭제 상품 92,859 / 좋아요 974,368), 측정 전후 products 100,000건 유지
- `ddl-check`: 실제 Hibernate가 만든 스키마와 `seed.sql` 스키마의 컬럼·인덱스 차이 없음 (컬럼 순서·유일 제약 이름만 다름)
- 원본: [query-performance-raw.md](query-performance-raw.md)의 `측정: baseline`·`API 측정: baseline`, 스냅샷 `perf-results/baseline/`

**요청 단위** (curl 순차 1개, `X-USER-ID: 1`, 모든 응답 200)

| 요청 | 워밍업 / 표본 | p50 | p95 | p99 | 최대 | 요청당 SQL (SELECT) | 요청당 SQL 시간 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **R1** 브랜드 1 가격순 (과제 대상) | 20 / 200 | **153.6ms** | **170.9ms** | 183.6ms | 1,053.4ms | **14 (4)** | 135.2ms |
| R2 브랜드 200 가격순 | 20 / 200 | 147.5ms | 167.2ms | 175.7ms | 194.5ms | 14 (4) | 134.5ms |
| R3 브랜드 200 좋아요순 | 1 / 3 | 19,459ms | 19,737ms | 19,737ms | 19,737ms | 14 (4) | 19,411ms |
| R4 상품 1 상세 | 20 / 200 | 96.5ms | 109.1ms | 121.5ms | 132.2ms | 14 (4) | 85.9ms |

- R1 최대 1,053ms는 200회 중 1회의 튐이다(p99 183.6ms). 기동 직후 JIT·커넥션 생성 영향으로 보이며, 비교는 p50·p95로 한다.
- 요청당 SQL 14개 = SELECT 4개(사용자 존재 확인, 상품 조회, 브랜드 일괄 조회, 좋아요 수 집계) + 트랜잭션 제어문 10개.
  트랜잭션 제어문은 요청마다 트랜잭션이 2개(사용자 확인, 조회)라 `SET autocommit` 4·`SET SESSION TRANSACTION READ ONLY/READ WRITE` 각 2·`COMMIT` 2가 나간 것이다.
- R1의 SQL 시간 135ms 중 좋아요 수 집계(Q9 형태)가 약 110ms, 상품 조회(Q4 형태)가 약 23ms다. **R1을 느리게 만드는 주원인은 상품 조회가 아니라 좋아요 수 집계**다.

**SQL 단위** (서버 실행 시간 평균)

| 쿼리 | 실행 횟수 | 평균 | 비고 |
| --- | ---: | ---: | --- |
| Q1 최신순 (전체) | 10 | 28.2ms | ALL + filesort |
| Q2 최신순 (브랜드 1) | 10 | 23.1ms | ALL + filesort |
| Q3 가격순 (전체) | 10 | 22.9ms | ALL + filesort |
| **Q4** 가격순 (브랜드 1) | 10 | **23.7ms** | ALL + filesort, R1의 상품 조회 |
| Q5 깊은 페이지 | 10 | 58.9ms | ALL + filesort |
| Q6 좋아요순 (전체) | - | TIMEOUT(>60s) | 하한값, EXPLAIN만 |
| Q7 좋아요순 (브랜드 1) | - | TIMEOUT(>60s) | 하한값, EXPLAIN만 |
| Q8 좋아요순 (브랜드 200) | 3 | 19,213ms | R3의 상품 조회 |
| **Q9** 좋아요 수 20개 | 10 | **102.8ms** | 인덱스 전체 스캔, R1·R2의 좋아요 수 집계 |
| Q10 좋아요 수 1개 | 10 | 86.0ms | 인덱스 전체 스캔, R4의 좋아요 수 집계 |

- 결과 일치 확인: R1 응답(id·price)은 Q4 스냅샷과, R3 응답(id·좋아요 수)은 Q8 스냅샷과 같다. 같은 seed를 두 번 만들어 bench한 결과도 compare에서 차이가 없었다(Q6·Q7은 비교 불가).

### 5.1 결과 (평균, ms)

| 쿼리 | 10만 개 | 1,000만 개 | 배율 | type | Extra |
| --- | ---: | ---: | ---: | --- | --- |
| Q1 최신순 (전체) | 45 | 4,510 | 100배 | ALL | Using where; Using filesort |
| Q2 최신순 (브랜드 1) | 35 | 3,467 | 99배 | ALL | Using where; Using filesort |
| Q3 가격순 (전체) | 43 | 4,046 | 93배 | ALL | Using where; Using filesort |
| Q4 가격순 (브랜드 1) | 33 | 3,425 | 105배 | ALL | Using where; Using filesort |
| Q5 깊은 페이지 | 73 | 7,600 | 104배 | ALL | Using where; Using filesort |
| Q6 좋아요순 (전체) | TIMEOUT | TIMEOUT | - | ALL + DEPENDENT SUBQUERY | Using filesort |
| Q7 좋아요순 (브랜드 1) | TIMEOUT | TIMEOUT | - | 같음 | 같음 |
| Q8 좋아요순 (브랜드 200) | 25,177 | TIMEOUT | - | 같음 | 같음 |
| Q9 좋아요 수 20개 | 148 | 1,526 | 10배 | index | Using index; Using temporary |
| Q10 좋아요 수 1개 | 101 | 1,318 | 13배 | index | Using index |

> Q9·Q10이 10배 남짓만 늘어난 이유: 좋아요 수는 두 규모 사이에 약 100만 → 1,000만으로 10배만 늘었다.

### 5.2 원인 ① 최신순·가격순: 전체 스캔 + filesort (Q1~Q5)

`products`에 `brand_id`·`created_at`·`price` 인덱스가 없다. 그래서 1페이지 20건을 위해 매번 다음을 한다.

1. 1,000만 행을 전부 읽는다 (`type: ALL`).
2. `deleted = false`(와 `brand_id`) 조건으로 거른다.
3. 남은 행을 정렬한다 (`Using filesort`).
4. 앞의 20건만 돌려준다.

```text
-> Limit: 20 row(s)  (actual time=5633..5633 rows=20)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk
        -> Filter: (p.deleted = false)
            -> Table scan on p  (actual time=0.129..3363 rows=10e+6)   ← 20건을 위해 1,000만 행 읽음
```

- 브랜드 필터(Q2·Q4)가 있어도 빨라지지 않는다. 브랜드 1의 70만 행을 찾으려고 결국 전체를 읽기 때문이다.
- 깊은 페이지(Q5)가 가장 느리다. 정렬하면서 20건이 아니라 50,020건을 유지해야 하고(`limit input to 50020 row(s)`), 그중 앞의 50,000건은 버린다.

### 5.3 원인 ② 좋아요순: 상품마다 반복되는 종속 서브쿼리 (Q6~Q8)

```sql
ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id = p.id) DESC
```

```text
id  select_type         table  type   key                              rows
1   PRIMARY             p      ALL    NULL                             9,960,468   Using filesort
2   DEPENDENT SUBQUERY  l      index  uk_product_likes_user_product    9,752,871   Using where; Using index
```

- `DEPENDENT SUBQUERY`는 바깥 행(상품) 하나마다 서브쿼리를 다시 실행한다는 뜻이다.
- 서브쿼리는 `product_id`로 찾아야 하는데, 유일 인덱스가 `(user_id, product_id)` 순서라 `product_id`만으로는 찾을 수 없다. 그래서 매번 인덱스 전체(`type: index`)를 훑는다.
- 비용은 대략 "상품 수 × 좋아요 수"다. 10만 규모에서도 약 9.5만 × 97만 ≈ 920억 건을 비교하므로 30초 안에 끝나지 않는다. 상품이 250개뿐인 작은 브랜드(Q8)도 25초가 걸렸다.

### 5.4 원인 ③ 좋아요 수 집계: 선두 컬럼이 아닌 인덱스 (Q9·Q10)

```sql
SELECT product_id, count(*) FROM product_likes WHERE product_id IN (...) GROUP BY product_id
```

```text
-> Covering index scan on product_likes using uk_product_likes_user_product
   (actual time=0.607..1639 rows=9.97e+6)   ← 상품 1개의 좋아요 수를 위해 997만 건 전체 읽음
```

- 복합 인덱스는 맨 앞 컬럼(`user_id`) 기준으로 먼저 정렬되어 있다. 그래서 두 번째 컬럼(`product_id`)만으로는 범위를 좁힐 수 없다.
- 그래도 테이블보다 작은 인덱스만 읽으면 되므로(`Using index`, 커버링 인덱스), 옵티마이저는 테이블 대신 인덱스 전체 스캔을 고른다.
- 목록 조회 때마다 이 집계가 실행되므로, 목록 API 한 번에 Q1~Q4 중 하나와 Q9의 비용이 함께 든다. 상세 조회도 마찬가지로 Q10 비용이 든다.

## 6. 개선 후 결과

> 개선을 적용한 뒤 같은 데이터로 `run.sh bench`를 실행해 채운다.

| 쿼리 | 개선 전 (1,000만) | 개선 후 (1,000만) | 적용한 것 |
| --- | ---: | ---: | --- |
| | | | |

트레이드오프(쓰기 비용, 인덱스 용량, 정합성)는 개선 방식을 정한 뒤 정리한다.
