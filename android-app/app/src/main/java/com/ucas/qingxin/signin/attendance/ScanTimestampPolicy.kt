package com.ucas.qingxin.signin.attendance

import kotlin.math.abs

/**
 * 扫码提交时该用哪个 `timestamp`。
 *
 * ## 为什么需要一条策略，而不是「照抄二维码」或「一律用校时」
 *
 * `stu_scan_sign.action` 的 `timestamp` 是**新鲜度证明**，学校拿它跟自己的时钟比：
 *
 * - 学校自己的客户端每 **5 秒**重新生成一次二维码
 *   （[com.ucas.qingxin.signin.network.QingxinApiService.QR_REFRESH_CAP_MS]），
 *   换的就是这个值 —— 它不参与身份，只证明「这一帧是刚出来的」；
 * - 本项目一键签到（同一条 action）发的是**本校校时**（`get_timestamp.do`）
 *   的即时读数，学校同样接受 —— 说明学校收的是「够新的时间」，
 *   而不是「它自己发过的那个值」。
 *
 * 由此，照抄二维码里的值**只在二维码还新鲜时**成立。轻新课堂小程序总是当场读屏，
 * 所以它照抄没有问题；而我们的用户可能面对的是一块**不再刷新**的班牌
 * （实测记录：连续三帧字节完全相同，时间戳已过去 224 秒），
 * 照抄一个旧值必然被学校判「已失效」。
 *
 * ## 两条不变量
 *
 * 1. **二维码新鲜时一个字都不改** —— 与唯一已知可用的客户端逐字一致，
 *    不为一个已经成立的情形引入新变量。
 * 2. **绝不提交本机时间** —— 校时拿不到就退回二维码原值：宁可用一个可能已过期的
 *    *学校*值，也不用一个我们无法证明可信的本地值（本项目自 1.0 起的既有约束）。
 */
object ScanTimestampPolicy {

    /**
     * 二维码时间戳与「现在」相差超过多少，就不再认为它是活的那一帧。
     *
     * 取学校自己的轮换周期（5 秒）：它是「一帧的有效寿命」唯一已知的刻度。
     * 取小了会把正在轮换的活码误判成过期（白改一次参数）；取大了会漏掉
     * 「班牌刷得很慢」这一类 —— 而那正是这条策略要修的。
     */
    const val REPLAY_TOLERANCE_MS = 5_000L

    /**
     * 只需要判断「值不值得为它取一次校时」时的廉价筛查（**只看本机时间**）。
     *
     * 它只用来决定要不要多发一个 `get_timestamp.do`，**不参与最终判定** ——
     * 最终判定一律以校时为准（见 [resolve]）。因此本机时钟不准只会让它
     * 多取一次校时，不会让结论出错。
     */
    fun looksStaleByLocalClock(qrTsMs: Long?, localNowMs: Long): Boolean {
        if (qrTsMs == null) return true
        return abs(localNowMs - qrTsMs) > REPLAY_TOLERANCE_MS
    }

    /**
     * 决定提交哪个时间戳；两个可用取值一个都没有时返回 null（调用方只能如实失败）。
     *
     * @param qrTsMs 二维码自带的 `timestamp`（缺失 / 不是数字时为 null）。
     * @param schoolNowMs 本校校时读数；null = 不可用。
     */
    fun resolve(qrTsMs: Long?, schoolNowMs: Long?): Decision? {
        // 没有可照抄的值：请求只能由我们拼完整 ⇒ 必须带一个新鲜度证明。
        // 连校时也拿不到时**没有**任何可以提交的东西 —— 本机时间不算（见不变量 2），
        // 因此返回 null 让调用方如实失败，而不是编一个值出来。
        if (qrTsMs == null) {
            val schoolNow = schoolNowMs ?: return null
            return Decision(
                valueMs = schoolNow,
                fromSchoolClock = true,
                qrAgeMs = null,
                qrStale = false,
                confidence = Decision.Confidence.NO_QR_TIMESTAMP,
            )
        }
        // 校时不可用 ⇒ 不变量 2：不动二维码原值。
        if (schoolNowMs == null) {
            return Decision(
                valueMs = qrTsMs,
                fromSchoolClock = false,
                qrAgeMs = null,
                qrStale = false,
                confidence = Decision.Confidence.NO_SCHOOL_CLOCK,
            )
        }
        val ageMs = schoolNowMs - qrTsMs
        val fresh = abs(ageMs) <= REPLAY_TOLERANCE_MS
        return Decision(
            valueMs = if (fresh) qrTsMs else schoolNowMs,
            fromSchoolClock = !fresh,
            qrAgeMs = ageMs,
            qrStale = !fresh,
            confidence = Decision.Confidence.SCHOOL_CLOCK,
        )
    }

    /**
     * 一次判定结果：提交什么、为什么、以及界面该怎么措辞。
     *
     * @param valueMs 实际提交的 `timestamp` 值。
     * @param fromSchoolClock 该值是否来自本校校时（false = 二维码原值）。
     * @param qrAgeMs 二维码时间戳相对**校时**的年龄（毫秒）：正数 = 已经过去这么久，
     *   负数 = 比学校时间还晚。null = 二维码没带时间戳，或校时不可用 ——
     *   后一种情况**不能**编一个本机算出来的数字充数。
     * @param qrStale 是否因为二维码时间戳陈旧而改用了校时值。
     * @param confidence 判定所依据的时基，界面据此决定话说多满。
     */
    data class Decision(
        val valueMs: Long,
        val fromSchoolClock: Boolean,
        val qrAgeMs: Long?,
        val qrStale: Boolean,
        val confidence: Confidence,
    ) {
        enum class Confidence {
            /** 拿本校校时判定的，结论可信。 */
            SCHOOL_CLOCK,

            /** 校时不可用，按「不动二维码原值」处理。 */
            NO_SCHOOL_CLOCK,

            /** 二维码没带时间戳，请求本来就由我们拼完整。 */
            NO_QR_TIMESTAMP,
        }

        /** 「3 分 44 秒」这种给人看的说法；算不出来时返回 null。 */
        fun humanQrAge(): String? {
            val ms = qrAgeMs ?: return null
            val total = abs(ms) / 1000
            val text = when {
                total < 60 -> "$total 秒"
                total < 3600 -> "${total / 60} 分 ${total % 60} 秒"
                else -> "${total / 3600} 小时 ${(total % 3600) / 60} 分"
            }
            return if (ms >= 0) text else "$text（比校时还晚）"
        }
    }
}
