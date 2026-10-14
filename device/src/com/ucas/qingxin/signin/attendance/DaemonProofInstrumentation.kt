package com.ucas.qingxin.signin.attendance

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.auth.AuthRepository
import com.ucas.qingxin.signin.course.CourseRepository
import com.ucas.qingxin.signin.data.Course
import com.ucas.qingxin.signin.data.SchoolSession
import com.ucas.qingxin.signin.history.SignSource
import com.ucas.qingxin.signin.network.QingxinApiService
import com.ucas.qingxin.signin.qr.QrTimelineManager
import com.ucas.qingxin.signin.widget.WidgetRefreshScheduler
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** External proof harness; does not change production code, clocks, delay, or dispatchers. */
class DaemonProofInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var app: QingxinApp
    private val lines = mutableListOf<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            waitForIdleSync()
            app = targetContext.applicationContext as QingxinApp
            check(app.isReady) { "Production QingxinApp failed initialization" }
            val scenario = args.getString("scenario") ?: "overdue"
            event("scenario=$scenario revision=${args.getString("revision")} sdk=${android.os.Build.VERSION.SDK_INT}")
            when (scenario) {
                "overdue" -> retryScenario(false)
                "receiver" -> retryScenario(true)
                "restart" -> restartScenario()
                "idle" -> idleScenario()
                "success" -> successScenario()
                "saver" -> saverScenario()
                "earlier" -> earlierScheduleScenario()
                "deadline" -> deadlineScenario()
                "budget" -> budgetScenario()
                "baseline_observe" -> baselineObservation()
                "cooldown_restart" -> cooldownRestartScenario()
                else -> error("Unknown scenario: $scenario")
            }
            event("PASS $scenario")
            result.putString("stream", lines.joinToString("\n"))
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            event("FAIL ${t.javaClass.simpleName}: ${t.message}")
            Log.e(TAG, "Harness failure", t)
            result.putString("stream", lines.joinToString("\n") + "\n" + t.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            if (::app.isInitialized) {
                app.getSharedPreferences("user_settings", 0).edit().putBoolean("auto_sign", false).commit()
                AttendanceDaemonService.stop(app)
            }
        }
    }

    private fun prepare(targetOffsetMs: Long, failFirst: Boolean, beginOffsetMs: Long = 10 * 60_000L): Fixture {
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
        app.attendanceScheduler.stop()
        await(5_000) { !running() }
        app.getSystemService(NotificationManager::class.java).cancelAll()
        val now = System.currentTimeMillis()
        val begin = now + beginOffsetMs
        val zone = ZoneId.of("Asia/Shanghai")
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
        val course = Course("7654321", "", "Synthetic daemon regression", "Fixture", fmt.format(Instant.ofEpochMilli(begin)), fmt.format(Instant.ofEpochMilli(begin + 3_600_000)), DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(Instant.ofEpochMilli(now)), false)
        val fixture = Fixture(course, failFirst)
        val api = QingxinApiService(OkHttpClient.Builder().addInterceptor(fixture).build())
        app.credentialStore.saveSession(SchoolSession("fixture-user", "fixture-session", "fixture-student"))
        val auth = AuthRepository(api, app.credentialStore)
        val courses = CourseRepository(app, api, auth)
        val qr = QrTimelineManager(api)
        setField("api", api)
        setField("authRepository", auth)
        setField("courseRepository", courses)
        setField("qrTimeline", qr)
        setField("attendanceRepository", AttendanceRepository(api, auth, courses, qr, app.signRecords))
        runBlocking { courses.loadToday() }
        WidgetRefreshScheduler.markDataFetched(app)
        app.getSharedPreferences("user_settings", 0).edit().putBoolean("auto_sign", true).putBoolean("notify", true).putBoolean("low_power_mode", false).commit()
        @Suppress("UNCHECKED_CAST")
        val targets = AutoSignRandomizer::class.java.getDeclaredField("targets").apply { isAccessible = true }.get(AutoSignRandomizer) as ConcurrentHashMap<String, Long>
        targets[AutoSignRandomizer.key(course.id, AutoSignRandomizer.todayKey())] = now + targetOffsetMs
        event("fixture onlyCourse=${course.id} targetOffsetMs=$targetOffsetMs deadlineMs=${AutoSignRandomizer.deadlineMs(courses.parseBeginMs(course)!!)} daemonEnabled=${app.powerProfile().daemonEnabled} batteryExempt=${KeepAliveHelper.isBatteryExempt(app)}")
        check(app.powerProfile().daemonEnabled)
        check(KeepAliveHelper.isBatteryExempt(app)) { "Dedicated test app must be battery exempt to disable early-sign tolerance" }
        return fixture
    }

    private fun retryScenario(receiver: Boolean) {
        val fixture = prepare(-1_000, true)
        val shouldRun = AutoSignEngine.shouldRunDaemon(app)
        event("shouldRunDaemon=$shouldRun")
        if (receiver) {
            app.sendBroadcast(Intent(app, AutoSignAlarmReceiver::class.java).setAction(AutoSignAlarmReceiver.ACTION_AUTO_SIGN_ALARM))
            event("real explicit AutoSignAlarmReceiver broadcast sent")
        } else {
            AttendanceDaemonService.start(app)
            event("AttendanceDaemonService.start called")
        }
        await(6_000) { fixture.signTimes.isNotEmpty() }
        event("after initial wake signRequests=${fixture.signTimes.size} serviceRunning=${running()} daemonNotification=${daemonNotification()}")
        check(shouldRun) { "Last overdue course rejected by shouldRunDaemon (actual initial requests=${fixture.signTimes.size})" }
        check(fixture.signTimes.size == 1) { "Expected one failed initial request" }
        check(running()) { "Daemon stopped after transient failure" }
        await(15_000) { daemonNotification() }
        val pendingText = app.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == AttendanceNotifier.ID_DAEMON }?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        event("pending notification text=$pendingText")
        check(pendingText != app.getString(com.ucas.qingxin.signin.R.string.auto_sign_status_all_done)) { "Pending retry incorrectly shown as all done" }
        SystemClock.sleep(20_000)
        event("after receiver watchdog serviceRunning=${running()} signRequests=${fixture.signTimes.size}")
        check(running()) { "Daemon did not survive receiver's real 18 s watchdog" }
        check(fixture.signTimes.size == 1) { "Unexpected early retry" }
        check(await(85_000) { fixture.signTimes.size >= 2 }) { "No production 60–90 s retry" }
        val gap = fixture.signTimes[1] - fixture.signTimes[0]
        event("retryGapMs=$gap actualSignRequests=${fixture.signTimes.size}")
        check(gap in 59_000L..93_000L) { "Retry timing outside production interval: $gap" }
        check(await(8_000) { app.courseRepository.courses.value?.courses?.singleOrNull()?.signed == true }) { "Success did not refresh production repository" }
        check(await(8_000) { !running() && !daemonNotification() }) { "Service or foreground notification remained after success" }
        val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications
        val result = notifications.singleOrNull { it.notification.extras.getString(Notification.EXTRA_TITLE) == app.getString(com.ucas.qingxin.signin.R.string.auto_sign_result_ok_title) }
        event("final serviceRunning=${running()} daemonNotification=${daemonNotification()} notifications=${notifications.map { it.id.toString() + ":" + it.notification.extras.getString(Notification.EXTRA_TITLE) }}")
        check(result != null) { "Expected one final success notification" }
        check(fixture.signTimes.size == 2) { "Expected exactly two sign requests" }
        check(fixture.unexpected.isEmpty()) { "Unexpected routes: ${fixture.unexpected}" }
        checkSignRecords(expectedCount = 2)
    }

    private fun restartScenario() {
        val fixture = prepare(180_000, false)
        AttendanceDaemonService.start(app)
        check(await(5_000) { running() }) { "Initial service missing; foreground=${daemonNotification()}" }
        check(await(15_000) { daemonNotification() }) { "Initial foreground notification missing; serviceRunning=${running()}" }
        SystemClock.sleep(1_000)
        event("before repeated start serviceRunning=${running()} foreground=${daemonNotification()} signRequests=${fixture.signTimes.size}")
        check(fixture.signTimes.isEmpty())
        repeat(3) {
            AttendanceDaemonService.start(app)
            SystemClock.sleep(500)
        }
        SystemClock.sleep(2_000)
        event("after repeated start serviceRunning=${running()} foreground=${daemonNotification()} signRequests=${fixture.signTimes.size}")
        check(running() && daemonNotification()) { "Repeated START cancelled old loop and stopped replacement service" }
        check(fixture.signTimes.isEmpty()) { "Future course signed prematurely" }
        app.getSharedPreferences("user_settings", 0).edit().putBoolean("auto_sign", false).commit()
        AttendanceDaemonService.start(app)
        check(await(5_000) { !running() && !daemonNotification() }) { "Disabled auto sign did not stop service" }
        event("disabled auto sign stops service and notification")
    }

    /** Repeated real START commands must leave the current retry cooldown intact. */
    private fun cooldownRestartScenario() {
        val fixture = prepare(-1_000, true)
        AttendanceDaemonService.start(app)
        check(await(6_000) { fixture.firstFailureAt > 0L && running() }) { "Initial failed attempt did not leave a running daemon" }
        val failedAt = fixture.firstFailureAt
        val earliestRetry = failedAt + 60_000L
        val restartOffsets = listOf(5_000L, 15_000L, 35_000L, 55_000L)
        var nextBatch = 0
        var externalStarts = 0
        event("cooldown begins failureAtMs=$failedAt; first request failed; serviceRunning=${running()}")
        while (SystemClock.elapsedRealtime() < earliestRetry) {
            val elapsed = SystemClock.elapsedRealtime() - failedAt
            if (nextBatch < restartOffsets.size && elapsed >= restartOffsets[nextBatch]) {
                repeat(3) {
                    AttendanceDaemonService.start(app)
                    externalStarts++
                    SystemClock.sleep(50)
                }
                event("cooldown restart batch=${nextBatch + 1} elapsedSinceFailureMs=${SystemClock.elapsedRealtime() - failedAt} externalStarts=$externalStarts requests=${fixture.signTimes.size} serviceRunning=${running()}")
                nextBatch++
            }
            val retries = synchronized(fixture.signTimes) { fixture.signTimes.drop(1) }
            check(retries.none { it < earliestRetry }) { "Repeated START shortened the production 60 s minimum cooldown" }
            // A legitimate success at the boundary may already have stopped the service.
            check(running() || retries.isNotEmpty()) { "Repeated START stopped the daemon during retry cooldown" }
            SystemClock.sleep(100)
        }
        check(externalStarts == 12) { "Not all START commands were issued during the cooldown" }
        event("minimum cooldown observed: no second submit before60000ms; externalStarts=$externalStarts requests=${fixture.signTimes.size}")
        check(await((failedAt + 93_000L - SystemClock.elapsedRealtime()).coerceAtLeast(1)) { fixture.signTimes.size >= 2 }) { "No retry after repeated START commands" }
        val retryAfterFailure = fixture.signTimes[1] - failedAt
        val requestGap = fixture.signTimes[1] - fixture.signTimes[0]
        event("cooldown retryAfterFailureMs=$retryAfterFailure requestGapMs=$requestGap actualSignRequests=${fixture.signTimes.size} externalStarts=$externalStarts")
        check(retryAfterFailure in 60_000L..93_000L) { "Retry outside production interval plus 3 s scheduler allowance" }
        check(await(8_000) { app.courseRepository.courses.value?.courses?.singleOrNull()?.signed == true }) { "Retry did not settle success" }
        check(await(8_000) { !running() && !daemonNotification() }) { "Service/notification survived successful final course" }
        val successTitle = app.getString(com.ucas.qingxin.signin.R.string.auto_sign_result_ok_title)
        val successes = app.getSystemService(NotificationManager::class.java).activeNotifications.count { it.notification.extras.getString(Notification.EXTRA_TITLE) == successTitle }
        check(successes == 1 && fixture.signTimes.size == 2 && fixture.unexpected.isEmpty())
        event("cooldown final serviceRunning=${running()} daemonNotification=${daemonNotification()} successNotifications=$successes actualSignRequests=${fixture.signTimes.size}")
        checkSignRecords(expectedCount = 2)
    }

    private fun idleScenario() {
        val fixture = prepare(3_600_000, false)
        // A distant reminder must not keep the foreground service alive.
        @Suppress("UNCHECKED_CAST")
        val state = CourseRepository::class.java.getDeclaredField("_courses").apply { isAccessible = true }.get(app.courseRepository) as kotlinx.coroutines.flow.MutableStateFlow<com.ucas.qingxin.signin.data.CourseQueryResult?>
        state.value = state.value!!.copy(courses = emptyList())
        AttendanceDaemonService.start(app)
        SystemClock.sleep(2_000)
        check(!running() && !daemonNotification())
        check(fixture.signTimes.isEmpty())
        event("no courses: service stopped, no notification, no sign request")
    }

    private fun successScenario() {
        val fixture = prepare(-1_000, false)
        app.sendBroadcast(Intent(app, AutoSignAlarmReceiver::class.java).setAction(AutoSignAlarmReceiver.ACTION_AUTO_SIGN_ALARM))
        check(await(8_000) { app.courseRepository.courses.value?.courses?.singleOrNull()?.signed == true }) { "First-attempt success failed" }
        check(await(8_000) { !running() })
        val title = app.getString(com.ucas.qingxin.signin.R.string.auto_sign_result_ok_title)
        check(app.getSystemService(NotificationManager::class.java).activeNotifications.count { it.notification.extras.getString(Notification.EXTRA_TITLE) == title } == 1)
        check(fixture.signTimes.size == 1)
        event("first-attempt success control: one real sign request, one success notification, service stopped; daemonNotification=${daemonNotification()}")
        checkSignRecords(expectedCount = 1)
    }

    private fun saverScenario() {
        val fixture = prepare(-1_000, false)
        app.getSharedPreferences("user_settings", 0).edit().putBoolean("low_power_mode", true).commit()
        check(!AutoSignEngine.shouldRunDaemon(app))
        AttendanceDaemonService.start(app)
        SystemClock.sleep(2_000)
        check(!running() && !daemonNotification() && fixture.signTimes.isEmpty())
        event("power saver: daemon disabled, service stopped, no sign request")
    }

    private fun earlierScheduleScenario() {
        val fixture = prepare(180_000, false)
        val earlier = fixture.course.copy(id = "7654322", name = "Synthetic restored earlier course")
        fixture.additionalCourses = listOf(earlier)
        val key = AutoSignExclusions.courseKey(earlier)
        app.autoSignExclusionStore.setExcludedToday(key, AutoSignRandomizer.todayKey(), true)
        runBlocking { app.courseRepository.loadToday() }
        @Suppress("UNCHECKED_CAST")
        val targets = AutoSignRandomizer::class.java.getDeclaredField("targets").apply { isAccessible = true }.get(AutoSignRandomizer) as ConcurrentHashMap<String, Long>
        targets[AutoSignRandomizer.key(earlier.id, AutoSignRandomizer.todayKey())] = System.currentTimeMillis() - 1_000
        AttendanceDaemonService.start(app)
        check(await(5_000) { running() })
        SystemClock.sleep(1_000)
        check(fixture.signTimes.isEmpty())
        app.autoSignExclusionStore.setExcludedToday(key, AutoSignRandomizer.todayKey(), false)
        app.attendanceScheduler.ensureSchedule()
        event("restored excluded earlier course while service sleeps for later course")
        check(await(8_000) { app.courseRepository.courses.value?.courses?.firstOrNull { it.id == earlier.id }?.signed == true }) { "Schedule change did not wake sleeping daemon" }
        check(fixture.signIds.toList() == listOf(earlier.id))
        check(running()) { "Daemon stopped although later course remains" }
        event("earlier course signed promptly, later course unsigned, service remains for later target")
        app.attendanceScheduler.saveSettings(app.attendanceScheduler.getSettings().copy(autoSignEnabled = false))
        check(await(5_000) { !running() && !daemonNotification() })
    }

    private fun deadlineScenario() {
        val fixture = prepare(-25 * 60_000L, true, -20 * 60_000L + 20_000)
        AttendanceDaemonService.start(app)
        check(await(6_000) { fixture.signTimes.size == 1 && running() })
        event("deadline fixture first failure; real deadline expires about 20 s after preparation")
        check(await(100_000) { !running() && !daemonNotification() }) { "Daemon survived expired final course" }
        check(fixture.signTimes.size == 1) { "Submitted after deadline" }
        event("deadline cutoff: one failed request, no late submission, service and foreground notification stopped")
    }

    private fun budgetScenario() {
        val fixture = prepare(-1_000, false)
        @Suppress("UNCHECKED_CAST")
        val attempts = AutoSignEngine::class.java.getDeclaredField("attempts").apply { isAccessible = true }.get(AutoSignEngine) as ConcurrentHashMap<String, Int>
        attempts[AutoSignRandomizer.key(fixture.course.id, AutoSignRandomizer.todayKey())] = app.powerProfile().maxAttempts
        AttendanceDaemonService.start(app)
        val title = app.getString(com.ucas.qingxin.signin.R.string.auto_sign_result_fail_title)
        check(await(10_000) { app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE) == title } }) { "Exhausted budget not settled by real engine" }
        check(await(5_000) { !running() && !daemonNotification() })
        check(fixture.signTimes.isEmpty()) { "Budget exhausted but extra sign request submitted" }
        event("seeded production attempts=${app.powerProfile().maxAttempts}; actual engine emits terminal failure, zero additional sign requests, service stopped")
    }

    private fun baselineObservation() {
        val fixture = prepare(-1_000, true)
        val started = SystemClock.elapsedRealtime()
        event("baseline long observation shouldRunDaemon=${AutoSignEngine.shouldRunDaemon(app)}")
        app.sendBroadcast(Intent(app, AutoSignAlarmReceiver::class.java).setAction(AutoSignAlarmReceiver.ACTION_AUTO_SIGN_ALARM))
        check(await(6_000) { fixture.signTimes.size == 1 })
        SystemClock.sleep((started + 20_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        event("baseline after20s requests=${fixture.signTimes.size} serviceRunning=${running()} daemonNotification=${daemonNotification()}")
        SystemClock.sleep((started + 96_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        event("baseline after${SystemClock.elapsedRealtime() - started}ms requests=${fixture.signTimes.size} serviceRunning=${running()} daemonNotification=${daemonNotification()} courseSigned=${app.courseRepository.courses.value?.courses?.singleOrNull()?.signed}")
        check(fixture.signTimes.size == 2) { "Regression reproduced: expected retry after transient failure, observed only ${fixture.signTimes.size} request across 96 s" }
    }

    private fun setField(name: String, value: Any) = QingxinApp::class.java.getDeclaredField(name).apply { isAccessible = true }.set(app, value)
    private fun checkSignRecords(expectedCount: Int) {
        app.signRecords.flush()
        val records = app.signRecords.readLatest()
        check(records.size == expectedCount && records.all { it.source == SignSource.AUTO }) { "Upstream automatic-sign history did not retain each real attempt" }
        check(records.count { it.ok } == 1) { "Upstream sign history must retain exactly one successful result" }
        event("production sign history records=${records.size} successRecords=${records.count { it.ok }} source=AUTO")
    }
    @Suppress("DEPRECATION")
    private fun running(): Boolean = app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any { it.service.className == AttendanceDaemonService::class.java.name }
    private fun daemonNotification(): Boolean = app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == AttendanceNotifier.ID_DAEMON }
    private fun await(timeout: Long, predicate: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        do { if (predicate()) return true; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < until)
        return predicate()
    }
    private fun event(message: String) { val line = "${SystemClock.elapsedRealtime()} $message"; lines += line; Log.i(TAG, line) }

    private inner class Fixture(val course: Course, private val failFirst: Boolean) : Interceptor {
        val signTimes = Collections.synchronizedList(mutableListOf<Long>())
        val signIds = Collections.synchronizedList(mutableListOf<String>())
        val unexpected = Collections.synchronizedList(mutableListOf<String>())
        val signed = ConcurrentHashMap.newKeySet<String>()
        @Volatile var additionalCourses = emptyList<Course>()
        @Volatile var firstFailureAt = 0L
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            check(request.url.host.endsWith("example.invalid")) { "Refusing non-fixture endpoint" }
            val path = request.url.encodedPath
            val json = when {
                path.endsWith("common/get_timestamp.do") -> JSONObject().put("STATUS", "0").put("timestamp", System.currentTimeMillis())
                path.endsWith("course/get_stu_course_sched.action") -> JSONObject().put("STATUS", "0").put("result", JSONArray().also { array -> (listOf(course) + additionalCourses).forEach { c -> array.put(JSONObject().put("id", c.id).put("courseName", c.name).put("classBeginTime", c.beginTime).put("classEndTime", c.endTime).put("signStatus", if (signed.contains(c.id)) "1" else "0")) } })
                path.endsWith("course/stu_scan_sign.action") -> {
                    check(request.method == "GET")
                    check(request.header("sessionId") == "fixture-session")
                    val id = request.url.queryParameter("courseSchedId")!!
                    check((listOf(course) + additionalCourses).any { it.id == id })
                    check(request.url.queryParameter("id") == "fixture-user")
                    check(request.url.queryParameter("timestamp")!!.toLong() > 0)
                    signTimes += SystemClock.elapsedRealtime()
                    signIds += id
                    Log.i(TAG, "ACTUAL_HTTP attempt=${signTimes.size} ${request.method} ${request.url}")
                    if (failFirst && signTimes.size == 1) {
                        // Simulate a 500 ms failed connection so simultaneously woken paths overlap.
                        // Production coroutine delays and retry periods remain unchanged.
                        SystemClock.sleep(500)
                        firstFailureAt = SystemClock.elapsedRealtime()
                        throw IOException("Synthetic transient connection failure")
                    }
                    signed.add(id)
                    JSONObject().put("STATUS", "0").put("result", JSONObject().put("stuSignStatus", "1").put("stuSignId", "fixture-sign").put("msg", "Synthetic success"))
                }
                else -> { unexpected += path; throw IOException("Unexpected fixture route: $path") }
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture").body(json.toString().toResponseBody()).build()
        }
    }
    companion object { private const val TAG = "DaemonProof" }
}
