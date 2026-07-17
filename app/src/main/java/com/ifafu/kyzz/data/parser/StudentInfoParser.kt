package com.ifafu.kyzz.data.parser

import com.ifafu.kyzz.data.model.StudentInfo
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentInfoParser @Inject constructor(
    private val htmlParser: HtmlParser
) {

    fun parseStudentInfo(doc: Document, account: String): StudentInfo {
        val info = StudentInfo()
        info.account = account

        val fields = mutableMapOf<String, String>()
        val tds = doc.select("td")
        for (i in 0 until tds.size - 1) {
            val label = tds[i].text().trim().removeSuffix("：").removeSuffix(":").trim()
            val value = tds[i + 1].text().trim()
            if (label.isNotEmpty() && value.isNotEmpty()) {
                fields[label] = htmlParser.cleanNbsp(value)
            }
        }

        // The page is an old table form and contains photo cells, empty cells and
        // nested tables. Prefer the explicit label spans when available so one
        // empty/photo cell cannot shift the label/value pairing for later fields.
        doc.select("span[id^=lbxsgrxx_]").forEach { labelSpan ->
            val label = labelSpan.text().trim()
                .removeSuffix("：")
                .removeSuffix(":")
                .trim()
            val labelCell = labelSpan.closest("td") ?: return@forEach
            val valueCell = labelCell.nextElementSibling() ?: return@forEach
            val value = htmlParser.cleanNbsp(valueCell.text())
            if (label.isNotEmpty() && value.isNotEmpty()) {
                fields[label] = value
            }
        }

        info.name = fields["姓名"] ?: ""
        info.formerName = fields["曾用名"] ?: ""
        info.gender = fields["性别"] ?: ""
        info.birthday = fields["出生日期"] ?: ""
        info.nation = fields["民族"] ?: ""
        info.nativePlace = fields["籍贯"] ?: ""
        info.originRegion = fields["来源地区"] ?: ""
        info.originProvince = fields["来源省"] ?: ""
        info.birthPlace = fields["出生地"] ?: ""
        info.healthStatus = fields["健康状况"] ?: ""
        info.college = fields["学院"] ?: ""
        info.department = fields["系"] ?: ""
        info.major = fields["专业名称"] ?: ""
        info.className = fields["教学班名称"] ?: ""
        info.adminClass = fields["行政班"] ?: ""
        info.duration = fields["学制"] ?: ""
        info.studyYears = fields["学习年限"] ?: ""
        info.status = fields["学籍状态"] ?: ""
        info.currentGrade = fields["当前所在级"] ?: ""
        info.candidateNumber = fields["考生号"] ?: ""
        info.idType = fields["证件类型"] ?: ""
        info.idNumber = fields["身份证号"] ?: ""
        info.enrollmentDate = fields["入学日期"] ?: ""
        info.graduationSchool = fields["毕业中学"] ?: ""
        info.dormitory = fields["宿舍号"] ?: ""
        info.politicalStatus = fields["政治面貌"] ?: ""
        info.phone = fields["联系电话"] ?: ""
        info.email = fields["电子邮箱"] ?: ""
        info.postalCode = fields["邮政编码"] ?: ""
        info.educationLevel = fields["学历层次"] ?: ""
        info.studyForm = fields["学习形式"] ?: ""
        info.homeAddress = fields["家庭地址"] ?: ""
        info.homeLocation = fields["家庭所在地（/省/县）"] ?: ""

        if (info.name.isEmpty()) {
            val nameMatch = Regex("xhxm\">(.+?)同学").find(doc.html())
            if (nameMatch != null) {
                info.name = nameMatch.groupValues[1]
            }
        }

        return info
    }
}
