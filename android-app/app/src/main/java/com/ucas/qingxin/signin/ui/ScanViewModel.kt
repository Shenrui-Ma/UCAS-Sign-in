package com.ucas.qingxin.signin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucas.qingxin.signin.QingxinApp
import com.ucas.qingxin.signin.attendance.ScanPayload
import com.ucas.qingxin.signin.data.SignOutcome
import com.ucas.qingxin.signin.network.ApiException
import com.ucas.qingxin.signin.network.QingxinApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「扫码打卡」页面的界面状态。
 *
 * 与 [ManualSignUiState] 一样是不可变快照：界面只读，变更只走 [ScanViewModel]。
 */
data class ScanUiState(
    /** 是否正在提交签到请求（此时相机取景仍在，但已经不再接受新的帧）。 */
    val submitting: Boolean = false,
    /** 识别结果回显（浅色提示，如「已识别：7 位节次编号」）。 */
    val recognized: String = "",
    /**
     * 二维码域名与本项目通道不一致时的说明。
     *
     * 这条提示存在的意义是**可解释性**：用户拿到的二维码指向 A，而我们实际请求的是 B，
     * 一旦失败，没有这句话就无法判断「到底是学校不认，还是应用把地址搞错了」。
     */
    val hostNote: String = "",
    /** 成功信息。 */
    val success: String = "",
    /** 失败原因。 */
    val error: String = "",
) {
    /**
     * 是否已有终态（成功或失败）。
     *
     * 界面据此**停掉取景**并换成结果卡片：继续对着同一张二维码取景没有意义，
     * 而且相机会一直吃电 —— 现场场景下用户可能盯着结果看很久。
     */
    val finished: Boolean get() = success.isNotEmpty() || error.isNotEmpty()
}

/**
 * 「扫码打卡」页面的 ViewModel。
 *
 * ## 职责边界
 * 它只做三件事：把二维码原文**分类**（[ScanPayload]）、决定**发不发请求**、
 * 把结果翻译成一句人能读懂的话。真正的提交在
 * [com.ucas.qingxin.signin.attendance.AttendanceRepository.signScanTarget]，
 * 与手动 / 自动签到共用同一把锁 —— 因此这一页**不需要**、也不该碰课程状态。
 *
 * ## 为什么不做「可信域名白名单」
 * 因为压根不发往别的域名：载荷解析出来的域名只用于提示，请求地址一律由
 * [QingxinApiService.buildScanUrl] 用本项目自己的 [QingxinApiService.BASE_URL] 拼出来。
 * 详见 [ScanPayload.DirectSign.qrHost] 的说明。
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val app: QingxinApp get() = getApplication()

    private val _ui = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = _ui.asStateFlow()

    /**
     * 取景闸门。
     *
     * 相机分析器从「解出一帧」到「回调处理完」之间没有任何同步，因此同一张码完全可能
     * 在一次签到请求还没回来时又触发一次。用原子闸门保证：一旦进入提交或已有终态，
     * 后续帧一律丢弃。**不能**只靠相机库的「扫到一次就停」——
     * 那个开关管的是后续帧的分析，管不住已经在飞行中的回调。
     */
    private val gate = AtomicBoolean(false)

    /**
     * 重新开始扫码。
     *
     * 同时清掉闸门：闸门是「本次取景」的状态，不是「本页面」的状态，
     * 否则用户点一次「重新扫码」之后相机就再也不响应了。
     */
    fun reset() {
        gate.set(false)
        _ui.value = ScanUiState()
    }

    /** 相机解出一段文本（可能来自任意二维码，因此必须先分类再决定动作）。 */
    fun onDecoded(raw: String) {
        if (!gate.compareAndSet(false, true)) return
        if (!app.isReady) {
            fail("应用尚未初始化完成，请稍后重试")
            return
        }
        // 分类顺序见 ScanPayload 的说明，与小程序逐条对应。
        when (val payload = ScanPayload.parse(raw)) {
            is ScanPayload.NotSign ->
                fail("扫到的是${payload.kind.label}，不是签到码。${payload.kind.hint}")

            is ScanPayload.Unknown ->
                fail("这张二维码不是轻新课堂的签到码，请对准班牌上的签到码再扫一次")

            is ScanPayload.DirectSign -> {
                _ui.update {
                    it.copy(
                        recognized = "已识别：${payload.label}",
                        hostNote = hostNoteFor(payload.qrHost),
                    )
                }
                viewModelScope.launch { submitDirect(payload) }
            }

            is ScanPayload.PlatformSign -> {
                _ui.update {
                    it.copy(
                        recognized = "已识别：7.0 平台签到码",
                        hostNote = hostNoteFor(payload.qrHost),
                    )
                }
                viewModelScope.launch { runSign(payload.path, payload.query) }
            }
        }
    }

    /**
     * 旧通道：必要时先补一个学校校时，再提交。
     *
     * 只有二维码**没带** `timestamp` 时才去校时（见 [ScanPayload.DirectSign.withTimestamp]）：
     * 校时是一次额外的网络往返，二维码自己带了时间戳时它纯属白等。
     */
    private suspend fun submitDirect(payload: ScanPayload.DirectSign) {
        _ui.update { it.copy(submitting = true) }
        val query = if (payload.needsTimestamp) {
            val schoolNowMs = try {
                app.qrTimeline.syncClock().schoolNowMs
            } catch (e: ApiException) {
                fail(e.message ?: "学校校时失败，请重试")
                return
            }
            payload.withTimestamp(schoolNowMs)
        } else {
            payload.submitQuery
        }
        runSign(payload.path, query)
    }

    private suspend fun runSign(path: String, query: String) {
        _ui.update { it.copy(submitting = true) }
        try {
            val result = app.attendanceRepository.signScanTarget(path, query)
            if (result.outcome == SignOutcome.SIGNED) {
                _ui.update {
                    it.copy(
                        submitting = false,
                        success = result.message.ifBlank { "签到成功" },
                    )
                }
            } else {
                fail(result.message.ifBlank { outcomeMessage(result.outcome) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            val loginExpired = e.code == "LOGIN_EXPIRED" || e.code == "LOGIN_REJECTED"
            fail(if (loginExpired) "登录已过期，请返回首页重新登录" else e.message ?: e.code)
        } catch (e: Exception) {
            fail(e.message ?: "签到失败")
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(submitting = false, success = "", error = message) }
    }

    /** 二维码域名与本项目通道不一致时给一句人话；一致或没写域名时返回空串。 */
    private fun hostNoteFor(qrHost: String?): String {
        if (qrHost.isNullOrBlank()) return ""
        val ours = QingxinApiService.BASE_URL.toHttpUrlOrNull()?.host ?: return ""
        if (qrHost.equals(ours, ignoreCase = true)) return ""
        return "二维码指向 $qrHost，已按轻新课堂的行为改用本校通道（$ours）"
    }

    private fun outcomeMessage(outcome: SignOutcome): String = when (outcome) {
        SignOutcome.SIGNED -> "签到成功"
        SignOutcome.QR_EXPIRED -> "签到码已失效，请对准班牌上最新的二维码重扫"
        SignOutcome.OUTSIDE_SIGN_WINDOW -> "当前不在签到时间内"
        SignOutcome.SIGN_RESULT_UNKNOWN -> "签到结果未知，请回首页刷新课程状态后再确认"
    }
}
