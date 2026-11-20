# bench 스크립트가 함께 쓰는 설정. 조인 질의는 테스트와 같은 sql/03_join_queries.sql 에서 읽는다.
cd "$(dirname "$0")/.." || exit 1
query() { awk -v n="$1" '$0 == "-- name: " n {f=1; next} f && /^SELECT/ {sub(/;$/, ""); print; exit}' sql/03_join_queries.sql; }
SMALL=$(query small)
MID=$(query mid)
BIG=$(query big)
NL="SET enable_hashjoin = off; SET enable_mergejoin = off"
HJ="SET enable_nestloop = off; SET enable_mergejoin = off"
SEQ="SET enable_indexscan = off; SET enable_bitmapscan = off"
IDX="SET enable_seqscan = off"
p="docker exec pgje psql -U postgres -At"

# 설정을 적용하고 질의를 한 번 실행해 "실행시간ms 읽은페이지 I/O시간ms" 를 출력한다.
# 값은 최상위 노드의 shared 버퍼 기준이다(temp 읽기는 세지 않는다). 실행에 실패하면 스크립트를 멈춘다.
measure() {
  out=$($p -c "SET track_io_timing = on" -c "$1" -c "EXPLAIN (ANALYZE, BUFFERS, TIMING OFF) $2" | awk '
    /Buffers:/ && !b { r = 0; if (match($0, /shared( hit=[0-9]+)? read=[0-9]+/)) { x = substr($0, RSTART, RLENGTH); sub(/.*read=/, "", x); r = x }; b = 1 }
    /I\/O Timings:/ && !io { t = 0; if (match($0, /shared(\/local)? read=[0-9.]+/)) { y = substr($0, RSTART, RLENGTH); sub(/.*read=/, "", y); t = y }; io = 1 }
    /Execution Time/ { e = $3 }
    END { if (e != "") printf "%s %s %s\n", e, (r == "" ? 0 : r), (t == "" ? 0 : t) }')
  if [ -z "$out" ]; then echo "측정 실패: $2" >&2; exit 1; fi
  echo "$out"
}
median() { echo "$@" | tr ' ' '\n' | sort -n | awk '{a[NR] = $1} END {print a[int((NR + 1) / 2)]}'; }
load() { uptime | sed 's/.*load averages*: //'; }
