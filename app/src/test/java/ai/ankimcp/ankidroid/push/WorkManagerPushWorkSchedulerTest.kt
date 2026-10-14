package ai.ankimcp.ankidroid.push

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorkManagerPushWorkSchedulerTest {
    /** Behaves like WorkManager's unique work: KEEP leaves an enqueued request in place. */
    private class FakeQueue : UniqueWorkQueue {
        val enqueued = linkedMapOf<String, Any>()
        val calls = mutableListOf<String>()

        override fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
            calls += "enqueue $name $policy"
            if (policy != ExistingWorkPolicy.KEEP || name !in enqueued) enqueued[name] = request
        }

        override fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
            calls += "enqueuePeriodic $name $policy"
            if (policy != ExistingPeriodicWorkPolicy.KEEP || name !in enqueued) enqueued[name] = request
        }

        override fun cancel(name: String) {
            calls += "cancel $name"
            enqueued.remove(name)
        }
    }

    private val queue = FakeQueue()
    private val scheduler = WorkManagerPushWorkScheduler(queue)

    @Test
    fun `the daily check is one unique periodic job, kept when already enqueued`() {
        scheduler.ensureDailyCheck()
        val first = queue.enqueued.getValue(WorkManagerPushWorkScheduler.DAILY_CHECK_WORK_NAME)

        scheduler.ensureDailyCheck()

        assertEquals(
            List(2) { "enqueuePeriodic ${WorkManagerPushWorkScheduler.DAILY_CHECK_WORK_NAME} KEEP" },
            queue.calls,
        )
        assertSame("the second app start does not reset the schedule", first, queue.enqueued.values.single())
        val spec = (first as PeriodicWorkRequest).workSpec
        assertEquals(PushDailyCheckWorker::class.java.name, spec.workerClassName)
        assertEquals(TimeUnit.HOURS.toMillis(24), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun `the retry and the daily check are separate jobs`() {
        scheduler.ensureDailyCheck()
        scheduler.schedule()

        scheduler.cancel()

        assertEquals(listOf(WorkManagerPushWorkScheduler.DAILY_CHECK_WORK_NAME), queue.enqueued.keys.toList())
        assertEquals(
            listOf(
                "enqueuePeriodic ${WorkManagerPushWorkScheduler.DAILY_CHECK_WORK_NAME} KEEP",
                "enqueue ${WorkManagerPushWorkScheduler.RETRY_WORK_NAME} KEEP",
                "cancel ${WorkManagerPushWorkScheduler.RETRY_WORK_NAME}",
            ),
            queue.calls,
        )

        scheduler.cancelDailyCheck()
        assertEquals(emptyList<String>(), queue.enqueued.keys.toList())
    }

    @Test
    fun `the token delete is its own network-bound job that logout's cancels leave alone`() {
        scheduler.scheduleTokenDelete()
        val first = queue.enqueued.getValue(WorkManagerPushWorkScheduler.TOKEN_DELETE_WORK_NAME)

        scheduler.scheduleTokenDelete()
        scheduler.cancel()
        scheduler.cancelDailyCheck()

        assertSame("KEEP: an enqueued delete keeps its backoff", first, queue.enqueued.values.single())
        val spec = (first as OneTimeWorkRequest).workSpec
        assertEquals(PushTokenDeleteWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(0L, spec.initialDelay)
    }
}
