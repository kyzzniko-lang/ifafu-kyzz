## 目标
让校园讨论页面进入后立即显示上次缓存内容，避免每次等待网络；缓存超过 30 分钟或用户下拉刷新时，再请求最新评论。

## 实施方案

### 1. 在 CacheManager 增加讨论缓存
修改：
- `app/src/main/java/com/ifafu/kyzz/data/cache/CacheManager.kt`

新增：
- 评论列表 JSON 存储
- 评论缓存时间戳
- `saveComments(...)`
- `loadComments(...)`
- `getCommentsCacheTimestamp()` 或统一的缓存状态读取方法

缓存内容保存未按标签过滤的原始评论列表，限制缓存数量，避免分页无限增长。缓存 key 不按账号区分，因为讨论内容是公共数据。

### 2. 扩展 CommentRepository
修改：
- `app/src/main/java/com/ifafu/kyzz/data/repository/CommentRepository.kt`

内容：
- 注入 `CacheManager`
- 网络请求成功后更新评论缓存
- 提供读取本地缓存的方法
- 提供缓存时间和是否超过 30 分钟的判断
- 发帖、删除、点赞成功后同步更新内存/缓存，避免重新进入时恢复旧数据

### 3. 调整 DiscussionViewModel 为 stale-while-revalidate
修改：
- `app/src/main/java/com/ifafu/kyzz/ui/comment/DiscussionViewModel.kt`

行为：
- 首次进入：先在 IO 线程读取缓存，立即发出 Success 显示缓存；随后仅当缓存超过 30 分钟时后台请求第一页
- 没有缓存：显示 Loading 并立即请求网络
- 缓存未过期：不请求网络，直接展示缓存
- 下拉刷新/重试：无视缓存时间，强制请求最新第一页
- 网络请求成功：替换第一页数据、更新缓存和分页状态
- 网络失败：保留缓存/已有评论，不把失败误判为空列表；如果完全没有缓存，再显示错误状态
- 保留现有标签本地筛选和分页逻辑
- 防止刷新时旧请求覆盖新数据

### 4. 调整 DiscussionActivity 刷新体验
修改：
- `app/src/main/java/com/ifafu/kyzz/ui/comment/DiscussionActivity.kt`

内容：
- 进入页面先显示缓存，不显示长时间全屏 Loading
- 下拉刷新继续强制请求网络
- 可选显示“显示缓存 · xx分钟前更新”轻量提示，不阻塞评论查看
- 进入页面/返回页面不重复发起未过期请求

### 5. 修正评论 API 的失败语义
修改：
- `app/src/main/java/com/ifafu/kyzz/data/api/GitHubIssuesApi.kt`

将“网络失败”和“真实空页”区分开，避免网络异常被当作 `emptyList()` 导致 `hasMore=false`。保持 Worker 优先、GitHub 直连回退逻辑。

## 验证
执行：
- `./gradlew.bat :app:processDebugResources`
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest`
- `./gradlew.bat :app:assembleDebug`

验证重点：
- 冷启动有缓存时立即显示评论
- 30 分钟内不重复请求
- 超过 30 分钟自动刷新
- 下拉刷新立即请求
- 网络失败仍保留缓存
- 发帖/删除/点赞后缓存保持一致
- APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`

## 范围
不修改 Worker 数据格式，不改变标签筛选和评论展示样式，不改现有登录/昵称流程。