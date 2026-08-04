# Debug Hot Reload

面向 IntelliJ IDEA **Debug** 场景的 Java / Spring / MyBatis 全栈热更新插件。
目标：以 JRebel 级体验覆盖日常开发的高频热更场景——**零配置、零许可证、零常驻开销**。

> 仅用于开发调试，不替代生产部署。

**当前版本：1.0.0（首个正式基线）**

---

## 核心特性

- **增强类重定义引擎（E2）**：目标 JVM 为 DCEVM 或 JetBrains Runtime 时，增删字段/方法等**结构变更保持类身份**——全部存活实例即时生效、Bean 不销毁、运行状态保留
- **免重建 Bean**：结构/注解变更后原地刷新（重做注入、重算拦截链）；仅在 CGLIB 代理方法集过期或首次引入切面时定向重建代理
- **四态可信结论**：每次热更明确给出「成功 / 已跳过 / 失败+根因 / 需重启」，路由自检回查真实注册状态，杜绝假成功
- **通用性**：功能路径零注解硬编码；切面注解引入由 Spring 自身的 advisor 重算判定，任意自定义 AOP 注解与标准注解一视同仁
- **全栈覆盖**：Java 类（含方法/字段/注解增删改）、Spring MVC 路由、AOP/事务/安全、MyBatis / MyBatis-Plus Mapper XML、application 配置文件、Web 静态资源和 Jackson 序列化缓存

## 引擎分级（自动探测，日志明示）

```
E1 标准 redefine   方法体/常量修改              所有 JVM
E2 增强 redefine   任意结构变更，类身份/状态保留   DCEVM 或 JBR 17+（主线）
E3 generation      结构变更降级子类方案（受限）    无增强运行时时自动降级并明示
```

会话激活日志显示当前引擎：`热更引擎=增强(结构变更原生支持，Bean与状态保留)` 或 `标准(...)`。

## 环境要求

| 组件 | 要求 |
|---|---|
| IntelliJ IDEA | 2024.3 – 2026.2 |
| 目标应用 JDK | 8 / 11 / 17 / 21 |
| Spring / Boot | Spring 5.x/6.x，Boot 2.x/3.x |
| MyBatis / Plus | 3.5.x（可选） |

### 启用增强引擎（强烈推荐）

插件在 Debug 启动时**自动检测并注入**对应参数（可在设置中关闭）：

- **JDK 8**：为项目 JDK 安装 [DCEVM](https://dcevm.github.io/)（以 altjvm 方式安装到 `jre/bin/dcevm`，不影响 JDK 正常使用）。检测到后自动追加 `-XXaltjvm=dcevm -XX:TieredStopAtLevel=1`
- **JDK 17/21**：项目 JDK 使用 [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime)（IDEA 自带）。检测到后自动追加 `-XX:+AllowEnhancedClassRedefinition`

未启用增强运行时也可使用（E1/E3 降级链），结构变更能力受限并在日志明示。

## 安装

1. 构建或获取发行包：`hotreload-idea/build/distributions/hotreload-idea-<version>.zip`
2. IDEA：`Settings → Plugins → ⚙ → Install Plugin from Disk...`
3. 重启 IDEA

## 使用

1. 用 **Debug** 启动应用（普通 Run 不注入）
2. 修改 Java / Mapper XML 后触发编译（Build 或 Ctrl+F9）；保存配置文件或 Web 静态资源时会自动处理
3. 底部工具窗口 `Debug Hot Reload` 查看中文结果日志
4. 结论为「需重启」时按提示重启 Debug 会话

设置项（`Settings → Tools → Debug Hot Reload`）：

- 全局默认：插件总开关、Java / Mapper XML / 配置文件 / 静态资源独立开关、增强运行时、日志级别和日志容量
- 当前项目：继承全局，或显式启用/禁用并覆盖各功能开关
- 运行配置：可排除指定 Application / Spring Boot Debug 配置
- 运行环境：显示项目 JDK、DCEVM/JBR 探测结果和预计注入参数

静态资源热更新会先把 `static/`、`public/`、`resources/`、`META-INF/resources/` 下的 HTML、CSS、JavaScript、图片和字体等文件安全同步到当前模块的 Debug 输出目录，再清理 Spring MVC 静态资源缓存。它不会调用 Spring DevTools LiveReload、模板引擎或其他 UI 控件；浏览器是否立即重新请求资源仍由浏览器/开发工具决定。文件新增、修改、删除、移动和目录重命名均支持。

## 构建与打包

必须使用 JDK 21 启动 Gradle（产物 agent 仍兼容 JDK 8）。项目中的 Gradle toolchain 只负责选择编译器，不能修正一个已经由 Java 8 启动的 Gradle 进程。

Windows PowerShell 临时切换：

```powershell
$env:JAVA_HOME = 'D:\tools\jdk\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --version
.\gradlew.bat clean test
.\gradlew.bat :hotreload-idea:buildPlugin
```

如需固定本机 Gradle JDK，可在用户级 `%USERPROFILE%\.gradle\gradle.properties` 配置（不要把机器路径提交到仓库）：

```properties
org.gradle.java.home=D:/tools/jdk/jdk-21.0.11+10
```

常用任务：

```bash
# 完整插件包（含 agent），产物在 hotreload-idea/build/distributions/
./gradlew :hotreload-idea:buildPlugin

# 仅 agent jar（含依赖 relocate），产物在 hotreload-agent/build/libs/
./gradlew :hotreload-agent:shadowJar
```

## 测试

```bash
# 单元测试（agent / protocol / idea）
./gradlew test

# 纯 MyBatis 端到端矩阵（需本机 JDK 8/11/21）
./gradlew :integration:e2e-tests:fullMatrixTest \
  -Photreload.jdk8.home=<jdk8> -Photreload.jdk11.home=<jdk11> -Photreload.jdk21.home=<jdk21>

# Spring Boot 2.7 + JDK8/DCEVM 端到端（核心场景回归：路由/注入/注解/回滚）
./gradlew :integration:e2e-tests:springMvcTest -Photreload.jdk8.home=<装有dcevm的jdk8>
```

## 模块结构

```
hotreload-idea/       IDEA 插件：Debug 注入、增强参数、日志窗口、设置
hotreload-agent/      目标 JVM 内执行体：引擎路由、Spring/MyBatis 刷新（shadow 打包）
hotreload-bootstrap/  bootstrap ClassLoader 桥（结构降级方案的私有成员访问）
hotreload-protocol/   插件与 agent 的二进制协议
integration/          端到端测试（真实 JVM + 真实 Spring Boot fixture）
docs/                 运行环境与框架兼容矩阵
```

## 已知限制

- 枚举加值：`values()` 不含新值（静态初始化不重跑），提示需重启
- 删除的方法若仍被未热更的调用方引用会 `NoSuchMethodError`（与编译期语义一致）
- 新增**全新** Mapper XML 文件（新 namespace）暂需重启
- `@Value`/`@ConfigurationProperties` 已注入值不随配置文件热更自动刷新（Environment 动态读取即时生效）
- YAML 配置热更新只接受可扁平化为字符串键值的简单 mapping；列表、多文档、锚点/别名、流式集合、块标量和 null 会整体拒绝，避免半更新
- 配置文件必须已经存在于当前 Spring Environment 的属性源中；无法证明其优先级时会安全跳过
- 插件只清理服务端静态资源缓存，不负责触发浏览器刷新
- 删除类、类卸载不支持（JVM 限制）

详细能力清单见 [FEATURES.md](FEATURES.md)，版本历史见 [CHANGELOG.md](CHANGELOG.md)。
