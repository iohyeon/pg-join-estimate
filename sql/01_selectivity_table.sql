-- 선택도 실험 테이블 t, 100만 행
--   v      : 0부터 999까지의 정수가 1,000행마다 한 번씩 고르게 들어간다(값 1,000종). 같은 값의 행이 테이블 전체에 흩어진다.
--   status : 값 3종. DONE 90%, PENDING 9%, FAILED 1%. 역시 테이블 전체에 흩어진다.
--   pad    : 행 하나를 약 100바이트로 키워, 힙을 읽어야 하는 질의가 페이지를 실제로 읽게 한다.
DROP TABLE IF EXISTS t;
CREATE TABLE t (id bigint PRIMARY KEY, v int NOT NULL, status text NOT NULL, pad text NOT NULL);
INSERT INTO t
SELECT g,
       ((g::bigint * 7919) % 1000)::int,
       CASE WHEN g % 100 = 0 THEN 'FAILED' WHEN g % 10 = 0 THEN 'PENDING' ELSE 'DONE' END,
       repeat('x', 100)
FROM generate_series(1, 1000000) g;
CREATE INDEX t_v ON t (v);
CREATE INDEX t_status ON t (status);
VACUUM ANALYZE t;
