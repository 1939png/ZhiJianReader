# 纸间阅读器

完全离线的长文本 TXT 小说阅读器。无账号、无网络、无服务器，**不申请任何权限（包括网络权限）**。

- 技术栈：Kotlin + Jetpack Compose（Material 3）+ Room + DataStore + Coroutines
- 包名：`com.qin.paperreader`，minSdk 26 / targetSdk 34 / compileSdk 35
- 源码目录：`ZhiJianReader/`（本说明同级的 Gradle 工程）

## 功能清单

| 模块 | 说明 |
| --- | --- |
| 书架 | 封面、书名、阅读进度、最近阅读时间；排序（最近阅读 / 加入时间 / 书名拼音）；重命名；删除 |
| 导入 | 系统文件选择器（SAF）导入 TXT，复制进应用私有目录；自动识别编码（BOM → UTF-16 零字节密度 → 严格 UTF-8 → GB18030 回退），带实时预览的手动选码对话框；书架支持「重新指定编码」（进度重置） |
| 目录 | 识别「第一章」「第001章」「第九百二十一章」「第 12 卷」「序章/楔子/引子/番外/终章/尾声/后记/大结局」等标题行与「Chapter N」；识别失败整本书按「全文」单章阅读；首个标题前的正文归入「开篇」 |
| 阅读 | 沉浸式（隐藏系统栏），点击左/右区域翻页、点击中央唤出工具栏；水平拖拽跟手翻页；音量键翻页（可关）；屏幕常亮（可开） |
| 翻页动画 | 无动画 / 覆盖 / 平移 / 仿真卷页。卷页：折痕位置跟手、纸背纸色柱面渐变、折痕在前后页投影、松手按位置与速度决定完成或回弹 |
| 书签 | 添加（保存字符偏移 + 正文预览）、备注编辑、删除、跳转 |
| 搜索 | 本书离线全文搜索，结果带上下文与关键词高亮（上限 200 条），点击定位原文 |
| 排版 | 字体（默认/衬线/导入 TTF·OTF）、字号、行距、段距、左右/上下页边距、首行缩进；设置面板内含样张预览，且阅读页本身实时生效 |
| 主题 | 暖白 / 豆绿 / 牛皮纸 / 浅灰 / 夜间五个预设；自定义背景色与文字色（RGB 滑杆）；纸纹噪点纹理开关 |
| 备份 | 书架菜单导出 zip（backup.json + 全部书籍原文），恢复时按 id 覆盖书籍、书签与设置 |

## 分页与偏移规则（重要约定）

1. **字符偏移**：导入时整本书按所选编码一次解码为内存字符串，所有进度、书签、搜索结果、页边界保存的都是该字符串的 **UTF-16 字符下标**（`0 <= offset < charCount`）。
2. **字节偏移**：与文件字节偏移的换算只发生在解码这一步（由 charset 决定，应用不持久化字节偏移）。换码后正文偏移会漂移，因此「重新指定编码」会重置进度并清空该书书签。
3. **进度恢复**：`lastOffset` 存字符偏移。打开书籍或字号/边距/屏幕尺寸变化后，分页引擎先重排目标章节，用二分查找把偏移定位到页，再后台渐进铺开全书——**从不是只存页码**。
4. **分页一致性**：测量与绘制共用同一个 `LayoutEngine`（同一 TextPaint、同一宽度、同一 `linesOf` 断行数据），行界、行位、段距三处同源，保证不漏字、不重复、不截行。首行缩进用 `LeadingMarginSpan` 参与断行计算，不改写原文。
5. **性能**：章节为排版单元（无章节的书是单个「全文」章节），先排当前章立即可读，后台按「当前 → 之后 → 之前」渐进铺开；超长无换行段落自动切块测量；百万字级别文本的读取、解码、解析、分页、搜索全部在后台线程。

## 工程结构

```
app/src/main/java/com/qin/paperreader/
├── App.kt / MainActivity.kt          # 应用图与入口（含音量键桥）
├── text/                             # 纯 JVM 文本层（可单测）
│   ├── EncodingDetector.kt           # 编码识别与解码
│   ├── ChapterParser.kt              # 章节标题识别
│   └── BookLoader.kt / Model.kt      # 段落/章节结构
├── engine/                           # 分页引擎
│   ├── PageAssembler.kt              # 纯逻辑高度装箱（可单测）
│   ├── LayoutEngine.kt               # StaticLayout 测量（测量/绘制同源）
│   └── PageRenderer.kt               # 页面绘制（背景/纸纹/正文/页脚）
├── reader/                           # 阅读层
│   ├── PaginationController.kt       # 渐进分页、偏移定位
│   ├── ReaderViewModel.kt            # 状态与 ReaderClient 实现
│   └── ReaderView.kt                 # 手势 + 四种翻页动画
├── data/                             # Room / DataStore / 书库 / 备份
└── ui/                               # Compose 界面（书架、阅读、面板、设置）
```

## 本机构建方法（Windows）

构建必须在纯 ASCII 路径进行（工作区路径含中文会让工具链异常），流程与之前的 Android 项目一致：

```powershell
robocopy "D:\文本阅读器\ZhiJianReader" "C:\zbuild\PaperReader" /MIR /XD .gradle build .idea dist .kotlin
# 写入 C:\zbuild\PaperReader\local.properties：sdk.dir=C\:\\Users\\qin\\android-sdk
$env:JAVA_HOME  = 'C:\Users\qin\jdk-dist\jdk-21.0.12.1+1'
$env:ANDROID_HOME = 'C:\Users\qin\android-sdk'
& 'C:\Users\qin\gradle-dist\gradle-8.14\bin\gradle.bat' -p 'C:\zbuild\PaperReader' :app:assembleDebug testDebugUnitTest --console=plain
```

- 依赖走 `settings.gradle.kts` 内置的阿里云镜像（google/public/gradle-plugin），失败自动回退官方源。
- 工程不带 Gradle Wrapper（`services.gradle.org` 连通性校验在本机不稳定），直接使用本机 Gradle 8.14。
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`，已复制为 `dist/ZhiJianReader-v1.0.5-debug.apk`（10.1 MB）。

## 安装

把 `dist/ZhiJianReader-v1.0.5-debug.apk` 传到手机直接安装（debug 签名）。首次使用：书架 → 「导入 TXT」选书 → 确认编码预览 → 点击封面进入阅读。

## 启动页（v1.0.4）

原生动画方案（无 Lottie 依赖）：独立 `SplashActivity` + 自定义 `SplashRippleView`，总时长 2.2s，波纹循环扩散，Logo 淡入+缩放+呼吸，底部署名「HIM开发-开源免费」（13sp / 60% 透明度 / 轻微淡入）。动画播完且后台预热（数据库首读、DataStore 配置首载、字体目录扫描，协程并行 + 1.5s 兜底超时）完成后，根容器收缩淡出 300ms → `fade_in/fade_out` 转场无缝进入主界面。刘海屏用 `shortEdges` + `fitsSystemWindows` 适配；配色按 DayNight 结构布置（`values/` + `values-night/`，当前应用整体深色故两套同值，浅色色值已注释预留）。

资源与代码清单：
- `ui/splash/SplashRippleView.kt` — 波纹扩散自定义 View（单 ValueAnimator 驱动相位，onDraw 零分配，稳定 60fps）
- `SplashActivity.kt` — 动画时间轴 + 预热编排 + 退出转场（参数集中在 companion，含注释）
- `res/layout/activity_splash.xml` — 布局（渐变背景 / 中央 280dp 波纹+Logo 叠放 / 底部署名）
- `res/drawable/splash_background.xml`、`res/drawable/splash_logo.xml` — 渐变背景与 Logo 矢量
- `res/values{,-night}/colors_splash.xml` — DayNight 配色（bg/波纹/Logo/署名）
- `res/anim/fade_in.xml`、`res/anim/fade_out.xml` — 主界面转场
- `res/values/themes.xml` — `Theme.PaperReader.Splash`（windowBackground=渐变图，冷启动无白屏）
- 集成：Manifest 中 LAUNCHER 已移至 `.SplashActivity`（`Theme.PaperReader.Splash`），`MainActivity` 变为普通 Activity；依赖仅新增 `lifecycle-runtime-ktx`（lifecycleScope）

- **v1.0.5 进度即存（真机反馈）**：修复「中途划掉后台丢失阅读进度」——原实现进度写入有 400ms 防抖，连续翻页时旧写入被取消、最后一次未落库；现改为每次页面落定（翻页/跳转）立即写库，并在页面 ON_STOP 时同步限时限存（150ms 上限）兕底，划掉后台前必经 ON_STOP，进度不再丢失
## 验证情况（如实记录）


- `:app:assembleDebug` ✓ **BUILD SUCCESSFUL**（APK 10.1 MB；aapt badging 确认：`com.qin.paperreader` v1.0.0，minSdk 26 / targetSdk 34 / compileSdk 35，应用名「纸间阅读器」，无网络等敏感权限）
- `:app:assembleDebug testDebugUnitTest` 最终轮 ✓ **BUILD SUCCESSFUL in 1m12s**（v1.0.4 轮），单元测试 **22/22 通过、0 失败**：ChapterParserTest 9 例（标题变体/正文误判/开篇/无章节/英文章节/二分定位）、EncodingDetectorTest 7 例（BOM/GB18030 往返/严格 UTF-8/无 BOM UTF-16/ASCII/预览截断/宽松解码）、PageAssemblerTest 6 例（高度装箱/跨页拆段/多段填充/行高大于页高兜底/空区间/偏移覆盖连续）
- **v1.0.3 手感与个性化（真机反馈）**：① 翻页触控更灵敏——滑动阈值改用系统分页阈值并限制上限（约 12dp），斜向滑动放宽到横向分量超过纵向 0.7 倍即触发，松手确认门槛降低（进度 > 0.28 或甩动速度 > 500px/s 即翻页，卷页阈值同向放宽）；② 书籍支持自定义封面——书籍菜单「更换封面」选图自动缩放存入应用私有目录（DB v1→v2 增量迁移，保留书架数据），可移除恢复默认封面，备份/恢复包含封面；③ 书架菜单新增「阅读设置」，与阅读页共用同一设置面板（字体大小、字体、行距段距边距缩进、主题、翻页动画、辅助开关），并修复导入字体后字体列表不刷新的问题；25/25 单测通过，构建 BUILD SUCCESSFUL
- **v1.0.2 深色模式**：应用外壳（书架/面板/对话框/启动背景）整体强制深色，不再跟随系统；暖棕深色调（背景 #141210，主题色 #B08D6A），状态栏图标转亮色；阅读页主题仍独立可选，新装设备默认「夜间」，已装设备在阅读设置 → 主题中切换
- **v1.0.1 修复（真机反馈）**：导入超过 64KB 的纯中文 UTF-8 文件（如 md/txt）乱码——根因是编码识别只看头 64KB 样本，65536 % 3 = 1 导致截断点落在多字节字符中间，严格 UTF-8 校验误报失败而误判 GB18030；修复为样本先截掉尾部不完整序列再校验（`trimUtf8Tail`），并新增 3 条回归测试，单测增至 **25/25 通过**（第四轮 BUILD SUCCESSFUL in 31s），版本号升为 v1.0.1（versionCode 2）
- 迭代过程：第一轮修 6 处编译错误（DAO 调用形式、Long/Int、TextPaint、viewModelScope 导入、Result 链、State 委托），第二轮修 1 处测试断言口径（跨页 startLine 索引），第三轮全绿。
- 「重新指定编码」与「备份/恢复」无自动化测试覆盖。
- 本机无 Android 设备/模拟器，**未做真机运行验证**；翻页手势、SAF 选文件、Room 读写等运行时行为未在真机上验证过，安装后如有问题请反馈。
