import org.jsoup.Jsoup

fun main() {
    val html1 = "<script language='javascript'>alert('你还没有进行本学期的教学质量评价,在本系统的“教学质量评价”栏中完成评价工作后，才能进入系统。');window.opener=null;window.close();</script>"
    
    val doc = Jsoup.parse(html1)
    val parsedHtml = doc.html()
    println("Parsed HTML: $parsedHtml")
    
    val regex = Regex("""(?is)(?:window\.)?alert\s*\(\s*(['"])(.*?)\1\s*\)""")
    val rawMatch = regex.find(parsedHtml)
    if (rawMatch != null) {
        println("Matched! Message: ${rawMatch.groupValues[2]}")
    } else {
        println("No match for parsed HTML")
    }
}
