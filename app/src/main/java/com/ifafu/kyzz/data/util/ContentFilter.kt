package com.ifafu.kyzz.data.util

object ContentFilter {

    data class FilterResult(val passed: Boolean, val reason: String = "")

    private val bannedKeywords = listOf(
        // 色情低俗
        "色情", "裸聊", "约炮", "援交", "卖淫", "嫖娼", "淫秽", "黄片",
        "成人视频", "一夜情", "性交", "口交", "肛交", "自慰", "情趣用品",
        "sm调教", "淫荡", "骚货", "荡妇", "鸡巴", "操你", "日你",
        "草你", "干你", "肏", "屌", "骚逼", "贱逼", "婊子",

        // 违法犯罪
        "代考", "替考", "枪手", "答案出售", "考试答案", "四六级答案",
        "作弊器", "论文代写", "代写论文", "刷单", "兼职日赚", "日入过千",
        "传销", "洗钱", "赌博", "网赌", "博彩", "赌球", "外围",
        "毒品", "大麻", "冰毒", "摇头丸", "K粉", "海洛因", "贩毒",
        "枪支", "买卖枪", "仿真枪", "炸药", "炸弹", "制作炸弹",
        "黑客攻击", "DDOS", "撞库", "脱库", "社工库",

        // 诈骗
        "刷单返利", "兼职刷单", "高薪日结", "足不出户赚钱",
        "贷款套现", "花呗套现", "信用卡套现", "校园贷", "裸贷",
        "杀猪盘", "投资理财保本", "稳赚不赔", "内部消息",

        // 暴力威胁
        "杀你", "弄死你", "砍死你", "打死你", "搞死你",
        "炸弹威胁", "我要杀人", "报复社会",

        // 人身攻击 / 侮辱
        "傻逼", "煞笔", "沙比", "弱智", "智障", "脑残", "废物",
        "贱人", "贱货", "去死", "死全家", "全家死", "你妈死",
        "操你妈", "草泥马", "尼玛逼", "狗日的", "王八蛋",
        "滚蛋", "垃圾人", "人渣",

        // 政治敏感（底线）
        "六四", "天安门事件", "法轮功", "翻墙教程",
        "vpn售卖", "ssr分享", "节点出售"
    )

    // 正则模式：手机号、银行卡号等（防诈骗信息）
    private val suspiciousPatterns = listOf(
        Regex("""(电话|手机|联系|微信|tel|phone)\s*[:：]?\s*1[3-9]\d{9}"""),  // 手机号（需有上下文关键词）
        Regex("""(?<![0-9])1[3-9]\d{9}(?![0-9]).{0,5}(加|微|信|聊|联系)"""),  // 手机号后跟引流词
        Regex("""(?<![0-9])\d{16,19}(?![0-9])"""),     // 银行卡号
        Regex("""(微信|QQ|qq)\s*[:：]?\s*\d{5,}"""),   // 联系方式引流
        Regex("""(加我|加v|加V|扫码)[^，。,.]{0,10}"""),  // 引流话术
    )

    // 高频垃圾信息特征
    private val spamPatterns = listOf(
        Regex("""(日赚|月入|躺赚|暴富)\d+"""),
        Regex("""(免费领|扫码领|点击领取)"""),
    )

    fun check(content: String): FilterResult {
        val text = content.lowercase().replace(Regex("""\s+"""), "")

        // 空内容
        if (text.isBlank()) {
            return FilterResult(false, "评论内容不能为空")
        }

        // 长度检查
        if (content.length > 500) {
            return FilterResult(false, "评论内容不能超过500字")
        }

        // 敏感词检测
        for (keyword in bannedKeywords) {
            if (text.contains(keyword.lowercase())) {
                return FilterResult(false, "评论包含违规内容，请修改后重试")
            }
        }

        // 可疑模式检测（手机号/银行卡在所有内容中检测）
        for (pattern in suspiciousPatterns) {
            if (pattern.containsMatchIn(text)) {
                return FilterResult(false, "评论疑似包含联系方式或敏感信息")
            }
        }
        // URL 拦截仅对短内容生效（长内容可能包含正常链接）
        if (content.length < 50) {
            if (Regex("""(http|https)://[^\s]+""").containsMatchIn(text)) {
                return FilterResult(false, "评论疑似垃圾信息")
            }
        }

        // 垃圾信息检测
        for (pattern in spamPatterns) {
            if (pattern.containsMatchIn(text)) {
                return FilterResult(false, "评论疑似垃圾信息")
            }
        }

        // 重复字符检测（如"啊啊啊啊啊啊啊啊啊啊"）
        if (Regex("""(.)\1{9,}""").containsMatchIn(text)) {
            return FilterResult(false, "评论包含大量重复字符")
        }

        // 纯符号/无意义内容
        if (Regex("""^[^一-龥a-zA-Z0-9]{5,}$""").matches(text)) {
            return FilterResult(false, "评论内容无意义")
        }

        return FilterResult(true)
    }
}
