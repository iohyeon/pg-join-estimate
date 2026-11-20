package io.github.iohyeon.estimate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 조인 방식이 추정 행 수와 인덱스에 따라 어떻게 바뀌는지 확인한다.
 * 질의는 sql/03_join_queries.sql 의 small(주문 0.1%), mid(1%), big(10%), filtered 이다.
 */
class JoinPlanTest {

    private fun join(name: String, label: String) =
        Postgres.explain(Postgres.joinQueries.getValue(name)).join().also { println("RESULT | $label, $name | $it") }

    @Test
    fun `기본 통계에서는 추정이 틀린 big 질의에 Nested Loop을 고른다`() {
        assertThat(join("small", "기본 통계").name).isEqualTo("Nested Loop")
        assertThat(join("mid", "기본 통계").name).isEqualTo("Hash Join")

        val big = join("big", "기본 통계")
        assertThat(big.name).isEqualTo("Nested Loop")
        assertThat(big.actualRows).isEqualTo(300_000)
        assertThat(big.estimatedRows).isLessThan(big.actualRows / 50)
    }

    @Test
    fun `확장 통계로 추정을 고치면 big 질의만 Hash Join으로 바뀐다`() {
        try {
            Postgres.execute(
                "CREATE STATISTICS customers_rgs (dependencies, mcv) ON region, grade, segment FROM customers",
                "ANALYZE customers",
            )
            val big = join("big", "확장 통계")
            assertThat(big.name).isEqualTo("Hash Join")
            assertThat(big.estimatedRows).isBetween(270_000, 330_000)
            assertThat(join("mid", "확장 통계").name).isEqualTo("Hash Join")
            assertThat(join("small", "확장 통계").name).isEqualTo("Nested Loop")
        } finally {
            Postgres.execute("DROP STATISTICS IF EXISTS customers_rgs", "ANALYZE customers")
        }
    }

    @Test
    fun `조인 키 인덱스를 지우면 big 질의뿐 아니라 small 질의까지 Hash Join으로 바뀐다`() {
        try {
            Postgres.execute("DROP INDEX orders_customer")
            val big = join("big", "조인 키 인덱스 삭제")
            assertThat(big.name).isEqualTo("Hash Join")
            assertThat(big.estimatedRows).isLessThan(big.actualRows / 50) // 추정은 여전히 틀린 채로 바뀌었다
            assertThat(join("mid", "조인 키 인덱스 삭제").name).isEqualTo("Hash Join")
            assertThat(join("small", "조인 키 인덱스 삭제").name).isEqualTo("Hash Join")
        } finally {
            Postgres.execute("CREATE INDEX IF NOT EXISTS orders_customer ON orders (customer_id)", "ANALYZE orders")
        }
    }

    @Test
    fun `조인 키가 아닌 필터 컬럼에 인덱스를 추가하면 Nested Loop이 Hash Join으로 바뀐다`() {
        assertThat(join("filtered", "필터 인덱스 없음").name).isEqualTo("Nested Loop")
        try {
            Postgres.execute("CREATE INDEX orders_amount ON orders (amount)", "ANALYZE orders")
            val plan = Postgres.explain(Postgres.joinQueries.getValue("filtered"))
            println("RESULT | 필터 인덱스 추가, filtered | ${plan.join()}")
            assertThat(plan.join().name).isEqualTo("Hash Join")
            // orders 쪽 입력을 새 인덱스로 줄였다
            assertThat(plan.lines).anyMatch { it.contains("Bitmap Index Scan on orders_amount") }
        } finally {
            Postgres.execute("DROP INDEX IF EXISTS orders_amount", "ANALYZE orders")
        }
    }
}
