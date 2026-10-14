package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.appGraph
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** The WorkManager calls [WorkManagerPushWorkScheduler] makes, so its rules can be tested on the JVM. */
interface UniqueWorkQueue {
    fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)

    fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest)

    fun cancel(name: String)
}

class WorkManagerQueue(context: Context) : UniqueWorkQueue {
    private val appContext = context.applicationContext

    override fun enqueue(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        WorkManager.getInstance(appContext).enqueueUniqueWork(name, policy, request)
    }

    override fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(name, policy, request)
    }

    override fun cancel(name: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork(name)
    }
}

/**
 * Retries a failed registration (needs a network, exponential backoff), runs a daily check
 * (needs a network) that re-registers when the 7-day renewal is due, and finishes a token
 * removal that sign-out could not complete (needs a network, retried until it succeeds).
 */
class WorkManagerPushWorkScheduler(private val queue: UniqueWorkQueue) : PushWorkScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(networkRequired())
            .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        // KEEP: a retry that is already pending or running keeps its backoff.
        queue.enqueue(RETRY_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        queue.cancel(RETRY_WORK_NAME)
    }

    override fun ensureDailyCheck() {
        val request = PeriodicWorkRequestBuilder<PushDailyCheckWorker>(DAILY_CHECK_HOURS, TimeUnit.HOURS)
            .setConstraints(networkRequired())
            .build()
        // KEEP: called on every app start; an enqueued check keeps its schedule.
        queue.enqueuePeriodic(DAILY_CHECK_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelDailyCheck() {
        queue.cancel(DAILY_CHECK_WORK_NAME)
    }

    override fun scheduleTokenDelete() {
        // No initial delay: the network constraint is what it waits for.
        val request = OneTimeWorkRequestBuilder<PushTokenDeleteWorker>()
            .setConstraints(networkRequired())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        queue.enqueue(TOKEN_DELETE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun networkRequired() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val RETRY_WORK_NAME = "push-registration"
        const val DAILY_CHECK_WORK_NAME = "push-registration-daily"
        const val TOKEN_DELETE_WORK_NAME = "push-token-delete"
        const val DAILY_CHECK_HOURS = 24L
        private const val INITIAL_DELAY_MINUTES = 1L
    }
}

class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // 1, 2, 4 ... minutes, capped at WorkManager's 5 h: gives up after roughly 13 hours and
        // shows the registration as failed. The next trigger tries again.
        val lastAttempt = runAttemptCount + 1 >= MAX_ATTEMPTS
        val done = applicationContext.appGraph.push.runScheduledRetry(lastAttempt)
        return when {
            done -> Result.success()
            lastAttempt -> Result.failure()
            else -> Result.retry()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}

class PushDailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Failures are handled by the coordinator (retry worker); the next day checks again anyway.
        applicationContext.appGraph.push.runDailyCheck()
        return Result.success()
    }
}

/** Finishes removing a token after an offline sign-out; no attempt cap, the token must go. */
class PushTokenDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.appGraph.push.retryPendingDelete(fromWorker = true)) Result.success() else Result.retry()
}
