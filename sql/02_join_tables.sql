-- 조인 실험 테이블
-- customers 10만 행. region, grade, segment 는 각각 값 10종이고 세 컬럼이 항상 같은 값이다(완전 상관).
--   컬럼별 통계만 있는 옵티마이저는 세 조건을 독립으로 보고 0.1 x 0.1 x 0.1 = 0.001 로 추정하지만 실제 통과 비율은 0.1 이다.
-- orders 300만 행. 고객마다 정확히 30건이고, 한 고객의 주문은 10만 행 간격으로 테이블 곳곳에 흩어진다.
--   그래서 고객 한 명의 주문을 읽으려면 서로 다른 힙 페이지 30개를 읽어야 한다.
DROP TABLE IF EXISTS orders, customers;
CREATE TABLE customers (id int PRIMARY KEY, region int NOT NULL, grade int NOT NULL, segment int NOT NULL);
INSERT INTO customers SELECT g, g % 10, g % 10, g % 10 FROM generate_series(1, 100000) g;
CREATE TABLE orders (id bigint PRIMARY KEY, customer_id int NOT NULL, amount int NOT NULL, pad text NOT NULL);
INSERT INTO orders
SELECT g, 1 + ((g::bigint * 7919) % 100000)::int, (g % 1000), repeat('y', 60)
FROM generate_series(1, 3000000) g;
CREATE INDEX orders_customer ON orders (customer_id);
VACUUM ANALYZE customers;
VACUUM ANALYZE orders;
