package io.github.iohyeon.estimate

import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * 모든 테스트가 함께 쓰는 PostgreSQL 16.4 컨테이너.
 * bench 스크립트와 같은 설정(fsync=off 만 추가)으로 띄우고, 저장소의 sql/ 파일로 테이블을 만든다.
 */
object Postgres {

    private val container = PostgreSQLContainer("postgres:16.4-alpine")
        .withSharedMemorySize(1L shl 30)
        .withCommand(
            "postgres",
            "-c", "fsync=off",
            "-c", "shared_buffers=1GB",
            "-c", "work_mem=4MB",
            // 병렬 실행을 끄면 계획이 Gather 없이 조인 방식만으로 갈려 비교가 단순해진다
            "-c", "max_parallel_workers_per_gather=0",
        )
        .apply { start() }

    init {
        runFile("sql/01_selectivity_table.sql")
        runFile("sql/02_join_tables.sql")
    }

    /** sql/03_join_queries.sql 의 `-- name:` 표시로 나눈 질의 */
    val joinQueries: Map<String, String> = run {
        val text = Files.readString(Path.of("sql/03_join_queries.sql"))
        Regex("""-- name: (\w+)\n(?:--.*\n)*(SELECT[^;]+);""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    fun connect(): Connection =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun execute(vararg sql: String) = connect().use { c ->
        c.createStatement().use { s -> sql.forEach { s.execute(it) } }
    }

    /** settings 를 같은 세션에서 먼저 적용하고 EXPLAIN (ANALYZE, TIMING OFF) 결과를 돌려준다 */
    fun explain(query: String, vararg settings: String): Plan = connect().use { c ->
        c.createStatement().use { s ->
            settings.forEach { s.execute(it) }
            s.executeQuery("EXPLAIN (ANALYZE, TIMING OFF) $query").use { rs ->
                Plan(generateSequence { if (rs.next()) rs.getString(1) else null }.toList())
            }
        }
    }

    private fun runFile(path: String) {
        // VACUUM 은 트랜잭션 안에서 실행할 수 없어 문장마다 따로 실행한다
        Files.readString(Path.of(path))
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { execute(it) }
    }
}

/** EXPLAIN ANALYZE 텍스트 출력에서 노드 하나를 찾아 추정 행 수와 실제 행 수를 읽는다 */
class Plan(val lines: List<String>) {

    data class Node(val name: String, val estimatedRows: Long, val actualRows: Long)

    /** 질의의 최상위 조인 노드 */
    fun join(): Node = first(Regex("""Nested Loop|Hash Join|Merge Join"""))

    /** 테이블 t 를 읽는 스캔 노드 */
    fun scanOnT(): Node = first(Regex("""Seq Scan on t\b|Index Scan using \w+ on t\b|Bitmap Heap Scan on t\b"""))

    /** 테이블 customers 를 읽는 스캔 노드 */
    fun scanOnCustomers(): Node = first(Regex("""Seq Scan on customers\b|Index Scan using \w+ on customers\b|Bitmap Heap Scan on customers\b"""))

    private fun first(name: Regex): Node {
        val line = lines.first { name.containsMatchIn(it) }
        val est = Regex("""cost=[^)]*rows=(\d+)""").find(line)!!.groupValues[1].toLong()
        val actual = Regex("""actual rows=(\d+)""").find(line)!!.groupValues[1].toLong()
        return Node(name.find(line)!!.value.substringBefore(" using").substringBefore(" on"), est, actual)
    }

    override fun toString() = lines.joinToString("\n")
}
