# 纸墨Markdown · Markdown Reader

本地优先的 Android Markdown 阅读器，带基础编辑能力。Kotlin + Android Views，自有 Markdown 解析器，无账号、广告或统计 SDK。最低 Android 8.0（API 26），当前版本 1.1.4（versionCode 13）。

**无需编译，直接下载：[纸墨Markdown.apk](release/纸墨Markdown.apk)。** 随仓库提供的是已签名的 Release APK，关闭应用及原生调试，开启 R8 和资源压缩。文件校验见 [SHA256SUMS.txt](release/SHA256SUMS.txt)，安装说明见[这里](docs/安装说明.md)。

默认不请求网络。开启「加载网络图片」后，会访问文档中的 HTTPS 图片地址；外部链接经确认后交给其他应用。系统文件选择器中的云盘文件由文件提供方管理，可能需要网络。应用不上传文档或诊断。

## 功能

- 标题、强调、删除线、高亮、行内代码、引用、列表、任务列表、表格、图片及围栏代码。
- 参考式链接、单行脚注、文内搜索、书签、章节折叠、目录及阅读位置恢复。
- 阅读页按块复用视图；长代码、大表格及列表拆为较小单元。极宽表格按文本展示。
- 图片接近屏幕时加载，限制下载体积和解码像素；点击后支持缩放。
- 代码复制、自动折行与轻量语法高亮；宽表格可横向滚动。
- SAF 文件授权、只读来源提示、相对文档和图片链接（外部文件需关联目录）。
- 编辑工具栏、差量撤销/重做、列表自动续写、横屏预览；自动保存和独立草稿，旧保存请求不会覆盖较新的草稿。
- 阅读与编辑横屏默认全屏；顶部中央小浮签切换标题栏和系统栏，页面停止触摸约 6 秒后自动收起；竖屏保持常规布局。
- 保存前校验原文版本；应用目录使用原子替换，SAF 保存后校验内容；保留上一次写入前的原文恢复副本。
- 保留编码、BOM 和换行风格；非 UTF-8 文件可在阅读页菜单手动指定编码。导出文档、另存编辑内容及分享文件。

## 内置图文长文

[《从计算机到人工智能》](app/src/main/assets/sample.md) 从机械计算、逻辑与电路、存储程序讲到个人电脑、互联网、机器学习、大模型与智能体，包含 20 个章节、时间线、术语表、代码例子、问答和学习清单。八幅原创配图随应用打包，关闭网络图片也能完整阅读。

新装与从旧版本升级时，这份长文只自动加入一次；不覆盖已有文档或草稿，用户删除后也不会每次启动重新生成。旧示例若已在设备上存在，则由用户自行保留或删除。示例配图放在独立目录；导出 Markdown 正文不会自动打包配图，跨设备阅读请同时复制对应图片目录。

正文、配图及[可编辑图源](docs/illustrations/)采用项目 MIT 许可。修改配图可使用 draw.io；`python tools/render-sample-diagrams.py --render` 重新生成图片。普通构建不需要安装 draw.io 或联网下载图片。

## 构建与验证

需要 JDK 17、Android SDK platform 36、build-tools 35.0.0。仓库使用 AGP 8.10.1 和 Gradle Wrapper 8.11.1，发行包带官方 SHA-256 校验。不要用旧版系统 Gradle 替代 Wrapper。

```sh
# 设置 ANDROID_HOME，或在本机 local.properties 中配置 sdk.dir
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Windows 使用 `gradlew.bat`，也可以运行 `powershell -File tools/build.ps1`。Linux/macOS/Git Bash 可运行 `bash tools/build.sh`。SDK 安装辅助脚本为 `tools/setup-toolchain.sh`，需预先安装官方 command-line tools 和 JDK。

Gradle 官方下载较慢时可以选择可信镜像，但必须保持 `distributionSha256Sum` 的官方值。依赖校验文件在 `gradle/verification-metadata.xml`；更新依赖时需检查来源和新摘要，不应为绕过校验直接关闭验证。

自行构建的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。未配置签名时，Gradle 的 Release 输出为 unsigned APK，不能直接安装；这与仓库已提供的签名 APK 不同。

## 自行构建与签名

构建自己的安装包时，用以下环境变量提供私有签名配置；密钥放在工程目录外：

```text
MD_RELEASE_STORE_FILE       密钥文件的绝对路径
MD_RELEASE_STORE_PASSWORD   密钥库密码
MD_RELEASE_KEY_ALIAS        密钥别名
MD_RELEASE_KEY_PASSWORD     密钥密码
```

配置后运行 `:app:assembleRelease`，得到 `app/build/outputs/apk/release/app-release.apk`。Windows 的 `tools/重新打包.bat` 会先测试与检查，再把签名 Release 复制到 `release/纸墨Markdown.apk`；没有签名配置时不会把 unsigned 文件当作可安装包发布。

随仓库的 APK 沿用此前本机测试证书，以兼容已有测试安装；构建类型为 Release，`debuggable=false`。证书类型与调试开关相互独立。私钥不随源码公开。长期维护可使用维护者专用密钥，但更换证书通常无法直接覆盖此前安装，应先导出重要文档。

当前目录可直接作为公开仓库根目录，包含源码、测试、文档、图源及一个可安装 APK，不需要上传源码压缩包。旧 Git 历史和本机配置另存于工程外。`.gitignore` 特别保留 `release/纸墨Markdown.apk`，其他构建产物与私有配置仍被忽略。GitHub CI 已提供，远端构建不包含维护者签名密钥。

## 边界

- 自有解析器实现日常语法子集，未声称完整 CommonMark/GFM 一致性；脚注暂支持单行定义。
- 单文档最多 10 MiB、100,000 源行；图片压缩数据最多 12 MiB、解码最多约 400 万像素。阅读解析仍需将文本和语法树放入内存，视图复用不等于无限文件大小。
- 超长行内内容降级为文字；超长代码分段后，复制操作复制当前段；超长标题显示前 16,000 字符。编辑预览最多 120 个单元且约 30,000 字符，完整阅读请切换阅读页。
- 非 UTF-8 自动识别存在歧义；乱码时先手动指定源编码。图片地址最多 8,192 字符，过长的替代文本仅显示摘要。原编码无法表示新增字符时拒绝保存，请另存后在外部工具转换为 UTF-8。混合换行会统一为识别到的主风格。
- SAF 提供方通常不能原子替换，也没有跨应用比较并交换接口；校验可发现很多冲突，但无法保证消除外部应用在检查和写入间的竞态。请勿同时编辑同一外部文件；失败时保留草稿和恢复副本。
- 相对链接限定在文档目录或用户授权树内，不自动扩展权限；不支持不透明文档 ID 提供方的相对路径推导。云盘离线项保留在书架。
- 草稿在停止输入约 1 秒后及进入后台时保存；突然断电或强制终止可能丢失最近尚未完成持久化的输入。卸载清理应用目录、草稿和恢复副本，重要文件先导出。
- 暂不引入公式、Mermaid、HTML 表格、同步、加密或 PDF 引擎，避免增加常驻性能、网络和维护负担。后续以按需模块评估。
- 自动测试与静态检查无法代替各品牌 ROM、输入法、真实 SAF 提供方及 Android 16 的设备验收。

项目布局：`core/` 为解析、存储和渲染，`ui/` 为页面，`app/src/test/` 为回归测试。更新记录见 [CHANGELOG](CHANGELOG.md)，实现边界见[架构说明](docs/架构说明.md)，发布步骤见[开源发布说明](docs/开源发布说明.md)，设备检查见[验收清单](docs/验收清单.md)，第三方来源见 [NOTICE](NOTICE.md)。

MIT © Berlin，见 `LICENSE`。贡献规范见 `CONTRIBUTING.md`，安全报告见 `SECURITY.md`。
