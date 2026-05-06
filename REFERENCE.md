# iFAFU-KYZZ Reference Guide

## 项目概述
基于福建农林大学教务系统（jwgl.fafu.edu.cn）的 Android 客户端重构项目。
原始版本 0.7.1 alpha，现使用现代 Android 技术栈重写。

## 目录结构

```
ifafu-kyzz/
├── app/                          # 主应用模块（实际开发目录）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ifafu/kyzz/
│       │   ├── MainApplication.kt    # Application 入口，全局单例
│       │   ├── data/
│       │   │   ├── model/            # 数据模型（Kotlin data class）
│       │   │   ├── api/              # 网络接口层（OkHttp + 正则解析）
│       │   │   └── repository/       # 数据仓库
│       │   ├── ui/                   # 界面层
│       │   │   ├── base/             # BaseActivity
│       │   │   ├── main/             # 主页
│       │   │   ├── login/            # 登录
│       │   │   ├── syllabus/         # 课表
│       │   │   ├── score/            # 成绩
│       │   │   ├── exam/             # 考试
│       │   │   ├── elective/         # 选课
│       │   │   ├── comment/          # 评教
│       │   │   ├── about/            # 关于
│       │   │   └── web/              # WebView
│       │   ├── widget/               # 桌面小部件
│       │   ├── service/              # 后台服务
│       │   └── util/                 # 工具类
│       └── res/                      # 资源文件
│
└── reference/                    # 旧版参考资源（不参与编译）
    ├── res/
    │   ├── layout/                # 旧版布局 XML（UI 设计参考）
    │   ├── drawable/              # 旧版 drawable（形状、样式参考）
    │   ├── values/                # 旧版值资源（字符串、颜色、尺寸参考）
    │   ├── anim/                  # 旧版动画
    │   └── xml/                   # 旧版 widget 配置
    ├── assets/                    # 旧版 assets（config.properties, theta.dat）
    └── src/                       # 旧版 Java/Kotlin 源码（业务逻辑参考）
```

## 技术栈
- Kotlin 1.9.22 / AGP 8.2.2 / Gradle 8.5
- compileSdk 34 / minSdk 24 / targetSdk 34
- OkHttp 4.12（网络请求）
- Gson 2.10（JSON 解析）
- Glide 4.16（图片加载）
- Material3 + ViewBinding
- Coroutines（异步）
- SharedPreferences（本地存储）

## 教务系统接口说明
- 主机: http://jwgl.fafu.edu.cn
- 编码: GBK
- 认证: 基于 ASP.NET ViewState 的表单登录
- Token: URL 中的 Session ID，格式 `/(token)/page.aspx`

### 接口列表
| 功能 | 页面 | 参数 gnmkdm |
|------|------|------------|
| 登录 | default2.aspx | - |
| 修改密码 | mmxg.aspx | - |
| 课表查询 | xskbcx.aspx | N121603 |
| 成绩查询 | xscjcx_dq_fafu.aspx | N121605 |
| 培养计划 | pyjh.aspx | N121607 |
| 考试查询 | xskscx.aspx | N121604 |
| 选课 | xf_xsqxxxk.aspx | N121400/N121203 |
| 评教 | xsjxpj2fafu.aspx | N121400 |

## 功能模块对应关系
| 模块 | 新项目 Activity | 旧版参考源码 | 旧版参考布局 |
|------|----------------|-------------|-------------|
| 主页 | MainActivity | MainActivity.java | activity_main.xml + gadget_*.xml |
| 登录 | LoginActivity | LoginActivity.java | activity_login.xml |
| 课表 | SyllabusActivity | SyllabusActivity.java | activity_syllabus.xml |
| 成绩 | ScoreActivity | ScoreActivity.java | activity_score.xml |
| 成绩详情 | - | ScoreInfoActivity.java | activity_score_info.xml |
| 选修学分 | ElectiveScoreActivity | ElectiveScoreActivity.java | activity_elective_score.xml |
| 考试 | ExamActivity | ExamActivity.java | activity_exam.xml |
| 选课 | ElectiveCourseActivity | ElectiveCourseActivity.kt | activity_elective_course.xml |
| 评教 | CommentTeacherActivity | CommentTeacherActivity.kt | activity_comment_teacher.xml |
| 关于 | AboutActivity | AboutActivity.java | activity_about.xml |
| WebView | WebActivity | WebActivity.java | activity_web.xml |
