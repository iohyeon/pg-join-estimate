-- 조인 질의 네 개. 테스트는 네 개 모두, bench 스크립트는 small, mid, big 을 읽는다.
-- name: small
-- 고객 100명, 주문 3,000건(전체의 0.1%)
SELECT sum(o.amount) FROM customers c JOIN orders o ON o.customer_id = c.id WHERE c.id <= 100;
-- name: mid
-- 고객 1,000명, 주문 30,000건(1%)
SELECT sum(o.amount) FROM customers c JOIN orders o ON o.customer_id = c.id WHERE c.id <= 1000;
-- name: big
-- 고객 10,000명, 주문 300,000건(10%). 상관된 세 조건 때문에 기본 통계로는 고객 100명 안팎으로 추정된다.
SELECT sum(o.amount) FROM customers c JOIN orders o ON o.customer_id = c.id WHERE c.region = 3 AND c.grade = 3 AND c.segment = 3;
-- name: filtered
-- 고객 500명의 주문 중 금액이 7인 주문. amount 와 customer_id 가 모두 g 에서 정해져 함께 움직이므로
-- 실제로는 고객 434 한 명의 주문 30건이고, 옵티마이저는 두 조건을 독립으로 보고 15건으로 추정한다.
-- orders.amount 에 인덱스를 추가하면 조인 방식이 바뀌는지 본다
SELECT sum(o.amount) FROM customers c JOIN orders o ON o.customer_id = c.id WHERE c.id <= 500 AND o.amount = 7;
