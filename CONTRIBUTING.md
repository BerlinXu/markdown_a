# 参与开发

欢迎提交问题和补丁。请提供可公开的最小复现文档，避免上传真实业务资料、个人路径和访问令牌。

使用 JDK 17、Android SDK 36，通过仓库的 Gradle Wrapper 构建。提交前运行：

```sh
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

解析器变更应增加语法与源行号回归用例；存储变更应验证并发冲突、保存失败及原始字节保留；布局变更应沿用真实 measure/layout 的 Robolectric 守卫。真机检查见 `docs/验收清单.md`。

保持本地优先，避免自动扫描目录、后台联网和默认上传诊断。新增运行时依赖需说明性能、包体与许可证影响，并更新 `NOTICE.md`。源码采用 MIT；提交贡献即同意按项目许可证发布贡献。
