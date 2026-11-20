#!/bin/sh
# 캐시가 찬 상태의 실행 시간.
# orders, orders_customer, customers, t, t_status 를 pg_prewarm 으로 shared_buffers 에 올린 뒤(customers_pkey 는 올리지 않는다), 비교할 두 설정을 번갈아 16회씩 실행하고
# 첫 회를 버린 15회의 중앙값과 개별 값(ms)을 출력한다. 매 회 A 를 먼저, B 를 나중에 실행한다.
#   조인: Nested Loop 강제와 Hash Join 강제, 질의 small, mid, big
#   스캔: 인덱스 쪽(Seq Scan 금지)과 Seq Scan 강제, 조건 status = FAILED, PENDING, DONE
. "$(dirname "$0")/queries.sh"
$p -c "CREATE EXTENSION IF NOT EXISTS pg_prewarm" \
   -c "SELECT pg_prewarm('orders'), pg_prewarm('orders_customer'), pg_prewarm('customers'), pg_prewarm('t'), pg_prewarm('t_status')" >/dev/null
cmp() {
  a=""; b=""
  for i in $(seq 1 16); do
    x=$(measure "$2" "$4" | cut -d' ' -f1); y=$(measure "$3" "$4" | cut -d' ' -f1)
    [ "$i" -gt 1 ] && a="$a $x" && b="$b $y"
  done
  echo "$1 | A $(median $a) | B $(median $b)"
  echo "    A:$a"
  echo "    B:$b"
}
echo "부하(시작): $(load)"
echo "[조인] A = Nested Loop, B = Hash Join"
cmp small "$NL" "$HJ" "$SMALL"
cmp mid "$NL" "$HJ" "$MID"
cmp big "$NL" "$HJ" "$BIG"
echo "[스캔] A = 인덱스 쪽, B = Seq Scan"
for s in FAILED PENDING DONE; do cmp "status=$s" "$IDX" "$SEQ" "SELECT sum(length(pad)) FROM t WHERE status = '$s'"; done
echo "부하(끝): $(load)"
