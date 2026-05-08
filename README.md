# iFAFU - 福农校园助手

福建农业大学校园助手 Android 客户端，提供课表、成绩、考试查询等功能。

## 功能

- **课表查询** — 一维列表 / 二维网格课表，支持调停补课信息
- **成绩查询** — 按学期筛选，GPA 趋势图，加权均分统计
- **考试安排** — 考试时间地点，自动检测时间冲突
- **教师评价** — 一键完成教师评价
- **校园讨论** — 匿名讨论区，基于 GitHub Issues
- **选修课程** — 在线选课、抢课
- **工具箱** — 培养计划、等级考试、个人信息、密码修改等
- **离线模式** — 缓存数据，断网也能查看
- **课程提醒** — 每日课程 / 成绩更新通知
- **自动更新** — 从 GitHub Releases 检测并下载新版本

## 技术栈

- Kotlin + MVVM + Hilt
- Jetpack Navigation + BottomNavigationView
- OkHttp + Jsoup（ASP.NET WebForms 网页抓取）
- SharedPreferences + EncryptedSharedPreferences
- Material Design 3

## 致谢

此应用借鉴了 FAFU 18 届学长的开源项目，由 24 届网络工程 KYZZ 进行重写。感谢开源社区的贡献。

## 开源协议

本项目秉承开源精神，所有人都能免费使用。
