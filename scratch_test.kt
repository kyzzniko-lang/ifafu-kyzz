import java.util.regex.Pattern

fun main() {
    val html1 = "<script>alert('你还没有进行本学期的教学质量评价,在本系统的“教学质量评价”栏中完成评价工作后，才能进入系统。');</script>"
    val html2 = "<script>window.alert(\"测试\");</script>"
    val html3 = "<script defer>alert('test\\ntest');</script>"
    
    val regex = Regex("""(?:window\.)?alert\s*\(\s*(['"])(.*?)\1\s*\)""")
    
    for (html in listOf(html1, html2, html3)) {
        val match = regex.find(html)
        if (match != null) {
            println("Matched! Message: ${match.groupValues[2]}")
        } else {
            println("No match for: $html")
        }
    }
}
