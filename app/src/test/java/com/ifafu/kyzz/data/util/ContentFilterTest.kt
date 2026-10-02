package com.ifafu.kyzz.data.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引流话术正则的词边界回归测试。
 *
 * 背景：旧模式 (加我|加v|扫码)[^，。,.]{0,10} 在去空白文本上误伤"加我们平时分"
 * 等正常评课语句——{0,10} 允许 0 字符，退化为含"加我"即命中。修复后"加我/加v"
 * 必须紧跟联系方式类词（微信/v/QQ/威/5 位以上数字账号）才拦截。
 */
class ContentFilterTest {

    // ---- 正常语句必须放行 ----

    @Test
    fun normalSentenceWithJiaWoMenPasses() {
        val r1 = ContentFilter.check("老师人很好，加我们平时分很高，强烈推荐")
        assertTrue("『加我们平时分』是正常评课语句: ${r1.reason}", r1.passed)
        val r2 = ContentFilter.check("大家加我们一起组队做课程设计吧")
        assertTrue("『加我们…』句式不应拦截: ${r2.reason}", r2.passed)
    }

    @Test
    fun vipLectureSentencePasses() {
        // "参加VIP讲座"含"加v"子串，但 v 后无联系方式词，不应误拦
        val r = ContentFilter.check("参加了学校的VIP讲座，收获很大")
        assertTrue("『参加VIP…』不应拦截: ${r.reason}", r.passed)
    }

    // ---- 引流话术必须拦截 ----

    @Test
    fun referralWithContactWordBlocked() {
        assertFalse(ContentFilter.check("想提分的加我微信abc123私聊").passed)
        assertFalse(ContentFilter.check("资料分享，加我qq123456").passed)
        assertFalse(ContentFilter.check("有问题加我威：td888").passed)
    }

    @Test
    fun referralWithBareAccountBlocked() {
        // "加v"+5 位以上数字账号同样是引流（旧模式可拦截，不得回退漏放）
        assertFalse(ContentFilter.check("加v123456拿资料").passed)
    }

    @Test
    fun scanCodeReferralStillBlocked() {
        assertFalse(ContentFilter.check("扫码进群领取学习资料").passed)
    }
}
