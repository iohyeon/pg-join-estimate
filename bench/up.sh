#!/bin/sh
# 실행 시간 측정용 PostgreSQL 16.4 컨테이너(pgje)를 띄우고 실험 테이블을 만든다.
# 설정은 테스트(src/test/.../Postgres.kt)와 같다.
set -e
cd "$(dirname "$0")/.."
docker rm -f pgje >/dev/null 2>&1 || true
docker run -d --name pgje --shm-size=1g -e POSTGRES_PASSWORD=pw postgres:16.4-alpine \
  -c shared_buffers=1GB -c work_mem=4MB -c max_parallel_workers_per_gather=0 >/dev/null
until docker exec pgje pg_isready -U postgres -q; do sleep 1; done
sleep 2
for f in sql/01_selectivity_table.sql sql/02_join_tables.sql; do
  docker exec -i pgje psql -U postgres -q -v ON_ERROR_STOP=1 < "$f"
done
echo "pgje ready"
