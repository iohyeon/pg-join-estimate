package io.github.iohyeon.estimate

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Percentage.withPercentage
import org.junit.jupiter.api.Test

/**
 * 옵티마이저가 스캔 방식을 고르는 근거가 컬럼의 값 종류 수가 아니라
 * 조건이 통과시키는 행의 비율(선택도)이라는 것을 확인한다.
 */
class SelectivityTest {

    private fun scan(where: String) =
        Postgres.explain("SELECT sum(length(pad)) FROM t WHERE $where").scanOnT().also { println("RESULT | $where | $it") }

    @Test
    fun `값이 3종인 컬럼도 찾는 값의 비율에 따라 세 가지 스캔을 모두 고른다`() {
        assertThat(scan("status = 'FAILED'").name).isEqualTo("Index Scan")        // 1%
        assertThat(scan("status = 'PENDING'").name).isEqualTo("Bitmap Heap Scan") // 9%
        assertThat(scan("status = 'DONE'").name).isEqualTo("Seq Scan")           // 90%
    }

    @Test
    fun `등호 조건의 추정 행 수는 그 값의 MCV 빈도에 테이블 행 수를 곱한 값이다`() {
        val freqs = Postgres.connect().use { c ->
            c.createStatement().executeQuery(
                """SELECT unnest(most_common_vals::text::text[]) AS v, unnest(most_common_freqs) AS f
                   FROM pg_stats WHERE tablename = 't' AND attname = 'status'"""
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) to rs.getDouble(2) else null }.toMap() }
        }
        assertThat(freqs).containsOnlyKeys("DONE", "PENDING", "FAILED")
        for ((value, freq) in freqs) {
            assertThat(scan("status = '$value'").estimatedRows.toDouble())
                .isCloseTo(freq * 1_000_000, withPercentage(0.1))
        }
    }

    @Test
    fun `통과 비율이 낮은 범위 조건은 인덱스를, 높은 범위 조건은 Seq Scan을 고른다`() {
        val plans = listOf(1, 10, 50, 100, 300, 500, 600, 700, 800, 900).associateWith { scan("v < $it").name }
        assertThat(plans[1]).isEqualTo("Bitmap Heap Scan")
        assertThat(plans[100]).isEqualTo("Bitmap Heap Scan")
        assertThat(plans[900]).isEqualTo("Seq Scan")
        // 한 번 Seq Scan 으로 넘어가면 비율이 더 높아져도 인덱스로 돌아오지 않는다
        val firstSeq = plans.entries.first { it.value == "Seq Scan" }.key
        assertThat(plans.filterKeys { it >= firstSeq }.values).containsOnly("Seq Scan")
    }
}
