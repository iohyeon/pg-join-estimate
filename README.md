# pg-join-estimate

PostgreSQL 옵티마이저가 스캔 방식과 조인 방식을 무엇을 근거로 고르는지, 그리고 그 선택이 실제로 빨랐는지 확인하려고 만든 실험입니다. 조건이 통과시키는 행의 비율(선택도), 오래된 통계와 상관된 조건이 만드는 추정 오차, 확장 통계, 인덱스가 실행 계획을 어떻게 바꾸는지 테스트로 고정하고, 두 방식의 실행 시간과 디스크 읽기를 캐시 상태별로 측정합니다.

## 확인하려는 질문

인덱스와 조인 성능을 이야기할 때 자주 듣는 설명을 직접 실행해 확인했습니다.

| 흔히 듣는 설명 | 이 저장소에서 확인한 것 |
|---|---|
| 값 종류가 적은 컬럼(상태, 성별)은 단독 인덱스 효율이 낮다 | 값이 3종인 컬럼도 찾는 값이 드물면 옵티마이저는 Index Scan을 고릅니다. 판단 기준은 값 종류 수가 아니라 그 값이 통과시키는 비율입니다. 다만 그 Index Scan은 캐시가 찬 상태에서는 6배 빨랐고 캐시가 빈 상태에서는 2배 느렸습니다 |
| 대량 조인이 Nested Loop으로 느리면 `ANALYZE`를 실행한다 | 통계가 오래된 경우는 `ANALYZE`로 고쳐지고, 조건끼리 상관된 경우는 고쳐지지 않습니다. 후자는 확장 통계로 고쳐집니다 |
| 추정이 틀려 대량 조인에 Nested Loop을 고르면 느리다 | 캐시가 빈 상태에서는 8배 느렸고, 캐시가 찬 상태에서는 차이를 판단할 수 없었습니다(중앙값은 Nested Loop이 약간 빨랐습니다). 두 방식이 읽은 페이지 수는 비슷했는데 읽기 I/O 시간은 25배 차이가 났습니다 |
| 인덱스를 넣으면 Nested Loop 쪽이 된다 | 조인 키 인덱스는 그렇지만, 필터 컬럼 인덱스를 넣자 Nested Loop이 Hash Join으로 바뀌었습니다 |

## 실험 설계

두 가지 데이터를 씁니다. 각 SQL 파일 머리 주석에 같은 설명이 있습니다.

선택도 실험 테이블 `t`(100만 행, 18,519페이지, [`sql/01_selectivity_table.sql`](sql/01_selectivity_table.sql))

- `v`: 0~999 정수가 1,000행마다 한 번씩 들어갑니다. 범위 조건 `v < k`의 통과 비율이 `k / 1000`이 되도록 했습니다.
- `status`: 값 3종(`DONE` 90%, `PENDING` 9%, `FAILED` 1%). 값 종류가 적은 컬럼에서 값마다 계획이 달라지는지 보려고 비율을 크게 벌렸습니다.
- 같은 값의 행이 테이블 전체에 일정한 간격으로 흩어져 있습니다. `FAILED`는 100행마다 1행이고 한 페이지에 약 54행이 들어가므로, 1%의 행이 테이블 절반이 넘는 페이지에 퍼집니다.

조인 실험 테이블 `customers`(10만 행), `orders`(300만 행, 40,000페이지) ([`sql/02_join_tables.sql`](sql/02_join_tables.sql))

- `customers`의 `region`, `grade`, `segment`는 각각 값 10종이고 세 컬럼이 항상 같은 값입니다. 컬럼별 통계만 보는 옵티마이저는 세 조건의 선택도를 곱해 0.1%로 추정하지만, 실제 통과 비율은 10%입니다. 추정 오차를 일부러 100배로 만들기 위한 설계입니다.
- `orders`는 고객마다 정확히 30건이고, 한 고객의 주문은 10만 행 간격으로 흩어져 있습니다. 조인 결과가 커지면 orders의 거의 모든 페이지를 건드리게 됩니다.
- `orders.customer_id`에 인덱스가 있습니다.

조인 질의는 네 개입니다([`sql/03_join_queries.sql`](sql/03_join_queries.sql)). 테스트와 측정 스크립트가 모두 이 파일을 읽습니다.

| 이름 | 조건 | 결과 주문 수 |
|---|---|---|
| `small` | 고객 `id <= 100` | 3,000건(0.1%) |
| `mid` | 고객 `id <= 1000` | 30,000건(1%) |
| `big` | `region = 3 AND grade = 3 AND segment = 3` | 300,000건(10%) |
| `filtered` | 고객 `id <= 500`이고 주문 `amount = 7` | 30건 |

실행 계획은 테스트로 단언합니다. 추정 행 수는 `ANALYZE`가 표본으로 계산해 실행마다 조금씩 다르므로, 테스트는 정확한 값이 아니라 범위를 단언하고, 계획이 바뀌는 경계에서 떨어진 지점만 단언합니다. 실행 시간은 장비와 부하에 따라 달라지므로 단언하지 않고 측정 스크립트로 출력합니다.

## 실행 방법

필요한 것: JDK 21, Docker. 테스트는 Testcontainers가 `postgres:16.4-alpine` 컨테이너를 띄웁니다.

실행 계획 테스트(11건, 2분 안팎)

```bash
./gradlew test
```

테스트는 확인한 추정 행 수와 실제 행 수를 `RESULT |` 줄로 출력합니다. 아래 결과 표의 추정 행 수는 이 출력에서 옮겼습니다.

실행 시간 측정

```bash
./bench/up.sh     # 측정용 컨테이너 pgje 를 띄우고 테이블을 만든다
./bench/warm.sh   # 캐시가 찬 상태: 두 방식을 번갈아 16회씩, 첫 회를 뺀 15회의 중앙값과 개별 값
./bench/cold.sh   # 캐시가 빈 상태: 매 회 재시작과 페이지 캐시 비우기 뒤 1회 실행, 5회의 중앙값과 회차별 값(20분 안팎)
docker rm -f pgje
```

- 두 방식은 `enable_hashjoin`, `enable_nestloop`, `enable_mergejoin`, `enable_seqscan`, `enable_indexscan`, `enable_bitmapscan` 설정으로 강제합니다.
- `cold.sh`는 `EXPLAIN (ANALYZE, BUFFERS)`와 `track_io_timing`으로 회차마다 디스크에서 읽은 페이지 수와 읽기 I/O 시간도 출력합니다.
- `cold.sh`는 Docker VM의 페이지 캐시를 비우려고 `--privileged` 컨테이너를 실행합니다. 리눅스 호스트에서 실행하면 호스트의 페이지 캐시를 비우게 되므로 주의합니다.
- 두 스크립트 모두 시작과 끝의 load average를 출력합니다.

## 결과

원시 출력: [`results/test-2026-11-19.txt`](results/test-2026-11-19.txt)(테스트), [`results/bench-2026-11-19.txt`](results/bench-2026-11-19.txt)(측정)

### 실행 계획(테스트로 단언)

| 확인한 것 | 결과 | 테스트 |
|---|---|---|
| `status` 값별 스캔 | `FAILED`(1%) Index Scan, `PENDING`(9%) Bitmap Heap Scan, `DONE`(90%) Seq Scan | `SelectivityTest` |
| 등호 조건의 추정 행 수 | `pg_stats`의 MCV 빈도 × 100만과 같음(MCV 목록이 세 값을 모두 담는지도 단언) | `SelectivityTest` |
| 범위 조건 `v < k` | 0.1%, 10%는 Bitmap Heap Scan, 90%는 Seq Scan. 한 번 Seq Scan으로 넘어가면 돌아오지 않음 | `SelectivityTest` |
| 오래된 통계(자동 분석 끄고 값 변경) | 조인 추정 27행(실제 300,000)으로 Nested Loop, `ANALYZE` 뒤 298,623행으로 Hash Join | `EstimateTest` |
| 상관된 세 조건(실제 10,000행) | 기본 통계 98행, `ANALYZE` 다시 실행 뒤 101행, 확장 통계 뒤 10,137행 | `EstimateTest` |
| 기본 통계에서 고른 조인 | `small` Nested Loop, `mid` Hash Join, `big` Nested Loop(추정 3,115, 실제 300,000) | `JoinPlanTest` |
| 확장 통계 뒤 | `big`만 Hash Join으로 바뀜, `mid` Hash Join, `small` Nested Loop 유지 | `JoinPlanTest` |
| 조인 키 인덱스 삭제 뒤 | `small`, `mid`, `big` 모두 Hash Join(`big`의 추정은 여전히 틀림) | `JoinPlanTest` |
| 필터 컬럼 `orders.amount`에 인덱스 추가 | `filtered`가 Nested Loop에서 Hash Join으로 바뀌고, orders 쪽을 새 인덱스로 줄임 | `JoinPlanTest` |

관찰만 하고 단언하지 않은 것: `v < k`에서 Seq Scan으로 바뀌는 지점은 이번 실행에서 통과 비율 50%와 60% 사이였습니다.

### 실행 시간(측정, 단위 ms)

조인. 캐시 찬 상태는 15회 중앙값, 캐시 빈 상태는 5회 중앙값입니다.

| 질의 | 옵티마이저 기본 선택 | 찬 상태 Nested Loop | 찬 상태 Hash Join | 빈 상태 Nested Loop | 빈 상태 Hash Join |
|---|---|---|---|---|---|
| `small` | Nested Loop | 20 | 468 | 603 | 825 |
| `mid` | Hash Join | 64 | 566 | 6,163 | 1,245 |
| `big` | Nested Loop | 593 | 704 | 7,455 | 906 |

스캔. 인덱스 쪽은 `enable_seqscan = off`로 강제한 계획입니다(`FAILED`는 Index Scan, 나머지는 Bitmap Heap Scan).

| 조건 | 옵티마이저 선택 | 찬 상태 인덱스 쪽 | 찬 상태 Seq Scan | 빈 상태 인덱스 쪽 | 빈 상태 Seq Scan |
|---|---|---|---|---|---|
| `FAILED`(1%) | Index Scan | 20 | 118 | 646 | 332 |
| `PENDING`(9%) | Bitmap Heap Scan | 88 | 162 | 327 | 385 |
| `DONE`(90%) | Seq Scan | 439 | 528 | 750 | 863 |

캐시 빈 상태의 디스크 읽기(중앙값 회차)

| 계획 | 읽은 페이지 | 읽기 I/O |
|---|---|---|
| `big` Nested Loop | 43,330 | 5,366 |
| `big` Hash Join | 40,541 | 219 |
| `FAILED` Index Scan | 10,012 | 606 |
| `FAILED` Seq Scan | 18,519 | 196 |

### 해석

측정한 것은 실행 시간, 읽은 페이지 수, 읽기 I/O 시간입니다. 아래의 원인 설명은 데이터 설계에서 끌어낸 추론이고, 읽은 순서나 운영체제의 미리 읽기는 측정하지 않았습니다.

- `big`에서 두 방식이 읽은 페이지 수는 비슷했습니다. 결과 30만 건이 orders의 거의 모든 페이지에 들어 있고, `shared_buffers`가 orders보다 커서 한 번 읽은 페이지는 다시 읽지 않기 때문입니다. 그런데 읽기 I/O는 25배 차이가 났습니다. Hash Join은 orders를 순서대로 읽고, Nested Loop은 한 고객의 주문이 10만 행 간격으로 놓인 데이터를 고객 순서로 따라가므로 무작위에 가까운 순서로 읽었을 것으로 봅니다.
- `FAILED`는 1%의 행이 테이블 절반이 넘는 페이지에 흩어져 있어, 캐시가 빈 상태에서는 Index Scan이 Seq Scan보다 페이지를 적게 읽고도 느렸습니다. `FAILED` 행은 100행마다 놓여 있어 Index Scan은 힙을 앞에서부터 건너뛰며 읽었을 것으로 봅니다.
- 캐시가 찬 상태에서는 옵티마이저가 고른 `mid`의 Hash Join이 Nested Loop보다 9배 느렸습니다. 기본 `random_page_cost=4`는 무작위 읽기의 대부분이 캐시에 있다고 가정한 값이고, PostgreSQL 문서는 데이터가 모두 캐시에 있을 가능성이 높으면 이 값을 낮추는 것이 적절할 수 있다고 설명합니다. 이 결과의 원인이 비용 상수라는 것은 비용 상수를 바꿔 확인하지 않은 가설입니다.
- 회차별 값이 겹쳐 차이를 판단할 수 없는 항목: `small`의 캐시 빈 상태, `big`의 캐시 찬 상태, `PENDING`의 캐시 빈 상태, `DONE`의 두 상태. 회차별 값은 원시 출력에 있습니다.

## 측정 환경

Intel Core i7-8850H, macOS 15.7, Docker Desktop(엔진 28.3.2, VM 메모리 7.6GB), PostgreSQL 16.4(`postgres:16.4-alpine`), `shared_buffers=1GB`, `work_mem=4MB`, `max_parallel_workers_per_gather=0`, 그 밖의 비용 상수는 기본값. 테스트 컨테이너는 같은 설정에 `fsync=off`를 더해 적재를 빠르게 합니다(계획에는 영향이 없습니다). 측정 중 load average는 5에서 15 사이였습니다.

테스트는 Kotlin 2.0.21, JUnit 5.10.5, AssertJ 3.26.3, Testcontainers 1.20.4, PostgreSQL JDBC 42.7.4, Gradle 8.10.2 wrapper로 실행합니다.

병렬 실행을 끈 것은 계획이 Gather 노드 없이 조인 방식만으로 갈리게 해 비교를 단순하게 하려는 것입니다.

## 한계

- 측정은 macOS의 Docker Desktop 한 대에서 했습니다. 캐시가 빈 상태는 PostgreSQL 재시작과 Docker VM의 페이지 캐시 비우기로 만들었지만 macOS 호스트의 파일 캐시는 비우지 못합니다. 일부 읽기는 호스트 메모리에서 왔을 수 있고, 운영 장비의 디스크에서는 차이의 크기가 다를 수 있습니다.
- 세 컬럼이 항상 같은 값인 극단적인 상관으로 추정 오차를 만들었습니다. 실제 데이터의 상관은 이보다 약합니다.
- `random_page_cost` 조정, `work_mem` 부족으로 Hash Join이 디스크로 넘치는 경우, Merge Join, 병렬 실행은 다루지 않았습니다.
- `filtered` 질의는 계획이 바뀌는 것만 확인했고 실행 시간은 측정하지 않았습니다.
- 캐시 찬 상태 측정은 매 회 A(Nested Loop 또는 인덱스 쪽)를 먼저, B를 나중에 실행해 순서를 바꾸지 않았고, `customers_pkey` 인덱스는 미리 올리지 않았습니다(약 2MB이고 첫 회를 버립니다).

## 구조

- `sql/`: 실험 테이블 두 개와 조인 질의 네 개. 테스트와 측정 스크립트가 같은 파일을 읽습니다.
- `src/test/kotlin/io/github/iohyeon/estimate/Postgres.kt`: 테스트가 함께 쓰는 컨테이너, `EXPLAIN ANALYZE` 출력에서 노드 이름과 추정, 실제 행 수를 읽는 도우미
- `src/test/kotlin/io/github/iohyeon/estimate/SelectivityTest.kt`: 선택도와 스캔 방식
- `src/test/kotlin/io/github/iohyeon/estimate/EstimateTest.kt`: 오래된 통계와 상관된 조건의 추정 오차, `ANALYZE`와 확장 통계
- `src/test/kotlin/io/github/iohyeon/estimate/JoinPlanTest.kt`: 통계와 인덱스 상태별 조인 방식
- `bench/`: 실행 시간 측정 스크립트(`up.sh`, `warm.sh`, `cold.sh`)와 공통 설정(`queries.sh`)
- `results/`: README 숫자의 원시 출력

## 참고 문서

- [PostgreSQL 16, Row Estimation Examples](https://www.postgresql.org/docs/16/row-estimation-examples.html)
- [PostgreSQL 16, Statistics Used by the Planner](https://www.postgresql.org/docs/16/planner-stats.html)
- [PostgreSQL 16, Multivariate Statistics Examples](https://www.postgresql.org/docs/16/multivariate-statistics-examples.html)
- [PostgreSQL 16, Query Planning 설정](https://www.postgresql.org/docs/16/runtime-config-query.html)
- [PostgreSQL 16, Using EXPLAIN](https://www.postgresql.org/docs/16/using-explain.html)

## 관련 글

- [옵티마이저는 무엇을 보고 스캔과 조인을 고를까: PostgreSQL에서 통계와 인덱스를 바꿔 가며 확인하기](https://develop-tracking.tistory.com/entry/옵티마이저는-무엇을-보고-스캔과-조인을-고를까-PostgreSQL에서-통계와-인덱스를-바꿔-가며-확인하기)
