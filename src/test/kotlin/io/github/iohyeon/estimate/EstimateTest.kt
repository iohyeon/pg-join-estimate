package io.github.iohyeon.estimate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 추정 행 수가 틀리는 두 가지 원인을 나눠 확인한다.
 * 통계가 오래된 경우는 ANALYZE 로 고쳐지고, 조건 사이의 상관 때문인 경우는 ANALYZE 로 고쳐지지 않고 확장 통계로 고쳐진다.
 */
class EstimateTest {

    private val correlated = "SELECT * FROM customers WHERE region = 3 AND grade = 3 AND segment = 3"

    private fun estimate(query: String, label: String) =
        Postgres.explain(query).scanOnCustomers().also { println("RESULT | $label | $it") }

    @Test
    fun `통계가 오래되어 틀린 추정은 ANALYZE로 고쳐진다`() {
        // 자동 분석을 끈 테이블에서 통계를 모은 뒤 값을 바꾼다. 통계에는 tier = 0 만 있다.
        Postgres.execute(
            "DROP TABLE IF EXISTS customers_tier",
            "CREATE TABLE customers_tier (id int PRIMARY KEY, tier int NOT NULL) WITH (autovacuum_enabled = false)",
            "INSERT INTO customers_tier SELECT id, 0 FROM customers",
            "ANALYZE customers_tier",
            "UPDATE customers_tier SET tier = 1 WHERE id % 10 = 3",
        )
        try {
            val query = "SELECT sum(o.amount) FROM customers_tier c JOIN orders o ON o.customer_id = c.id WHERE c.tier = 1"
            val stale = Postgres.explain(query).also { println("RESULT | 오래된 통계 | ${it.join()}") }
            assertThat(stale.join().name).isEqualTo("Nested Loop")
            assertThat(stale.join().actualRows).isEqualTo(300_000)
            assertThat(stale.join().estimatedRows).isLessThan(3_000)

            Postgres.execute("ANALYZE customers_tier")
            val fresh = Postgres.explain(query).also { println("RESULT | ANALYZE 뒤 | ${it.join()}") }
            assertThat(fresh.join().name).isEqualTo("Hash Join")
            assertThat(fresh.join().estimatedRows).isBetween(270_000, 330_000)
        } finally {
            Postgres.execute("DROP TABLE IF EXISTS customers_tier")
        }
    }

    @Test
    fun `컬럼별 통계만 있으면 상관된 세 조건을 독립으로 보고 100분의 1 수준으로 추정한다`() {
        val node = estimate(correlated, "상관 조건, 기본 통계")
        assertThat(node.actualRows).isEqualTo(10_000)
        assertThat(node.estimatedRows).isBetween(50, 200)
    }

    @Test
    fun `상관 때문에 틀린 추정은 ANALYZE를 다시 실행해도 100행 안팎에 머문다`() {
        Postgres.execute("ANALYZE customers")
        assertThat(estimate(correlated, "상관 조건, ANALYZE 뒤").estimatedRows).isBetween(50, 200)
    }

    @Test
    fun `세 컬럼에 확장 통계를 만들면 추정이 실제와 맞아진다`() {
        try {
            Postgres.execute(
                "CREATE STATISTICS customers_rgs (dependencies, mcv) ON region, grade, segment FROM customers",
                "ANALYZE customers",
            )
            assertThat(estimate(correlated, "상관 조건, 확장 통계 뒤").estimatedRows).isBetween(9_000, 11_000)
        } finally {
            Postgres.execute("DROP STATISTICS IF EXISTS customers_rgs", "ANALYZE customers")
        }
    }
}
