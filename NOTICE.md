# 第三方来源与许可

项目自有代码：MIT，版权信息见 `LICENSE`。

| 来源 | 用途 | 许可 |
|---|---|---|
| AndroidX Core、AppCompat、Activity、Lifecycle、RecyclerView、DocumentFile | Android UI、生命周期、文件选择 | Apache-2.0 |
| Google Material Components / Material Design Icons | 组件与矢量图标 | Apache-2.0 |
| Kotlin 标准库、kotlinx.coroutines | Kotlin 与协程 | Apache-2.0 |
| Gradle Wrapper | 构建启动程序 | Apache-2.0 |
| JUnit 4 | 测试，仅开发时使用 | EPL-1.0 |
| Robolectric | 测试，仅开发时使用 | MIT |
| AndroidX Test | 测试，仅开发时使用 | Apache-2.0 |

Apache-2.0 许可证副本见 `LICENSES/Apache-2.0.txt`。依赖按各自发行包携带的许可证和版权声明使用；完整版本及传递组件可用 `bash gradlew :app:dependencies` 查看。

Markdown 解析器为本项目实现的语法子集，未复制第三方 Markdown 引擎。测试案例使用合成数据。内置《从计算机到人工智能》的正文、八幅教学示意及 `docs/illustrations/` 图源为本项目原创内容，随项目采用 MIT 许可；参考资料只提供链接，未捆绑来源图片或论文插图。图中文字由本机字体渲染，未打包字体文件。

draw.io 仅为维护者修改示意图的可选工具，不随应用分发，也不是构建依赖。外部参考资料仍按各自权利人的条款使用。发布前仍需由作者确认自有代码、图标改动及贡献内容的版权归属；本文件不构成权属证明。
