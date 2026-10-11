package ai.mobileagent.benchmark

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class BenchmarkRunStoreReconcileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun pendingFile(): File = File(folder.root, "benchmarks/pending-run.json").apply {
        parentFile?.mkdirs()
    }

    private fun runsFile(): File = File(folder.root, "benchmarks/runs.jsonl").apply {
        parentFile?.mkdirs()
    }

    private fun writePending(runId: String, startedAt: String = Instant.now().minusSeconds(30).toString()) {
        pendingFile().writeText(
            JSONObject()
                .put("run_id", runId)
                .put("started_at", startedAt)
                .put("prompt", "[BENCH:t1][COHORT:test] task")
                .toString(),
        )
    }

    @Test
    fun `recovers unfinished run as RUN_INTERRUPTED and clears pending without replay`() {
        writePending("run-killed-1")

        val recovered = BenchmarkRunStore.reconcileInterruptedFiles(
            pending = pendingFile(),
            runs = runsFile(),
            permissionLost = true,
        )

        assertTrue(recovered)
        assertFalse("pending must be cleared", pendingFile().exists())
        val line = runsFile().readText().trim()
        val record = JSONObject(line)
        assertEquals("failed", record.getString("status"))
        assertEquals("RUN_INTERRUPTED", record.getString("failure_code"))
        assertEquals("process", record.getString("failure_stage"))
        assertTrue(record.getBoolean("crash"))
        assertTrue(record.getBoolean("permission_lost"))
        assertFalse("no auto-replay marker", record.getBoolean("replay"))
        assertTrue(record.getLong("duration_ms") >= 0)
    }

    @Test
    fun `reconcile is idempotent and does not duplicate finalized runs`() {
        writePending("run-killed-2")
        val first = BenchmarkRunStore.reconcileInterruptedFiles(pendingFile(), runsFile(), permissionLost = false)
        val second = BenchmarkRunStore.reconcileInterruptedFiles(pendingFile(), runsFile(), permissionLost = false)
        assertTrue(first)
        assertFalse(second)
        val lines = runsFile().readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
    }

    @Test
    fun `already finalized run is not written twice`() {
        writePending("run-killed-3")
        // 模拟崩溃前已把终结记录写入 runs.jsonl 的场景（例如 finish 后 pending 清理前被杀）。
        runsFile().writeText(
            JSONObject()
                .put("run_id", "run-killed-3")
                .put("status", "failed")
                .put("failure_code", "PI_RUN_TIMEOUT")
                .toString() + "\n",
        )

        val recovered = BenchmarkRunStore.reconcileInterruptedFiles(pendingFile(), runsFile(), permissionLost = false)

        assertFalse(recovered)
        assertFalse(pendingFile().exists())
        assertEquals(1, runsFile().readLines().filter { it.isNotBlank() }.size)
        assertTrue(runsFile().readText().contains("PI_RUN_TIMEOUT"))
    }

    @Test
    fun `corrupt pending is removed without writing a record`() {
        pendingFile().writeText("{not json")

        val recovered = BenchmarkRunStore.reconcileInterruptedFiles(pendingFile(), runsFile(), permissionLost = false)

        assertFalse(recovered)
        assertFalse(pendingFile().exists())
        assertFalse(runsFile().exists())
    }

    @Test
    fun `missing pending is a no-op`() {
        val recovered = BenchmarkRunStore.reconcileInterruptedFiles(pendingFile(), runsFile(), permissionLost = false)
        assertFalse(recovered)
        assertFalse(runsFile().exists())
    }

    @Test
    fun `unparseable startedAt still recovers with zero duration`() {
        writePending("run-killed-4", startedAt = "not-a-timestamp")

        val recovered = BenchmarkRunStore.reconcileInterruptedFiles(pendingFile(), runsFile(), permissionLost = false)

        assertTrue(recovered)
        val record = JSONObject(runsFile().readText().trim())
        assertEquals("RUN_INTERRUPTED", record.getString("failure_code"))
        assertEquals(0L, record.getLong("duration_ms"))
    }
}
