#!/bin/sh
# 캐시가 빈 상태의 실행 시간.
# 매 회 PostgreSQL 을 재시작해 shared_buffers 를 비우고 Docker VM 의 페이지 캐시도 비운 뒤 한 번 실행한다.
# 5회의 중앙값과, 회마다 "실행시간ms/디스크에서 읽은 페이지/읽기 I/O 시간ms" 를 출력한다.
# VM 의 페이지 캐시를 비우려고 --privileged 컨테이너를 실행한다. macOS 호스트의 파일 캐시는 비우지 못한다.
# 전체 실행에 20분 안팎 걸린다.
. "$(dirname "$0")/queries.sh"
once() {
  docker restart pgje >/dev/null
  docker run --rm --privileged alpine sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches'
  until docker exec pgje pg_isready -U postgres -q; do sleep 1; done
  measure "$1" "$2"
}
# five "<이름>" "<설정>" "<질의>"
five() {
  name=$1; setting=$2; sql=$3; r=""; d=""
  for i in 1 2 3 4 5; do
    out=$(once "$setting" "$sql")
    r="$r $(echo "$out" | cut -d' ' -f1)"; d="$d $(echo "$out" | tr ' ' '/')"
  done
  echo "$name | $(median $r) |$d"
}
echo "부하(시작): $(load)"
echo "[조인] 실행시간ms/읽은페이지/I/O ms"
for q in small mid big; do
  case $q in small) Q=$SMALL;; mid) Q=$MID;; big) Q=$BIG;; esac
  five "$q Nested Loop" "$NL" "$Q"
  five "$q Hash Join" "$HJ" "$Q"
done
echo "[스캔]"
for s in FAILED PENDING DONE; do
  five "status=$s 인덱스 쪽" "$IDX" "SELECT sum(length(pad)) FROM t WHERE status = '$s'"
  five "status=$s Seq Scan" "$SEQ" "SELECT sum(length(pad)) FROM t WHERE status = '$s'"
done
echo "부하(끝): $(load)"
