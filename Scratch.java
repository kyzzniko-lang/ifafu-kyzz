import java.util.regex.*;

public class Scratch {
    public static void main(String[] args) {
        String html = "<script language='javascript'>alert('你还没有进行本学期的教学质量评价,在本系统的“教学质量评价”栏中完成评价工作后，才能进入系统。');window.opener=null;window.close();</script>";
        Pattern p = Pattern.compile("(?is)(?:window\\.)?alert\\s*\\(\\s*(['\"])(.*?)\\1\\s*\\)");
        Matcher m = p.matcher(html);
        if (m.find()) {
            System.out.println("Match: " + m.group(2));
        } else {
            System.out.println("No match");
        }
    }
}
