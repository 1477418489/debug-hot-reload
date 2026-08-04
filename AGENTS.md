# AGENTS.md - AI 开发规范

> 本文件为 AI Agent 开发时的项目约束和规范说明。

---

## 📦 版本管理规范

### 版本号定义位置
**唯一来源**：`build.gradle.kts` 根项目的 `version` 字段

```kotlin
// build.gradle.kts
group = "dev.hotreload"
version = "1.0.0"  // 当前版本
```

所有子模块自动继承根项目版本：
```kotlin
subprojects {
    version = rootProject.version
}
```

---

### 版本号规则

遵循 **语义化版本 (Semantic Versioning 2.0.0)**：`MAJOR.MINOR.PATCH`

| 版本位 | 触发条件 | 示例 |
|--------|---------|------|
| **MAJOR** | 不兼容的 API 变更、架构重构 | `1.0.0` → `2.0.0` |
| **MINOR** | 向下兼容的功能新增 | `1.0.0` → `1.1.0` |
| **PATCH** | 向下兼容的 Bug 修复 | `1.0.0` → `1.0.1` |

---

### 版本变更流程

#### 1. 开发新功能前
- 评估变更类型（新增功能/修复 Bug/破坏性变更）
- 确定版本号递增规则

#### 2. 修改版本号
```bash
# 仅修改 build.gradle.kts 根项目的 version 字段
vim build.gradle.kts
# version = "1.0.0" → version = "1.1.0"
```

#### 3. 重新构建
```bash
./gradlew clean build
./gradlew :hotreload-idea:buildPlugin
```

#### 4. 记录变更历史（见下方）

---

### 当前版本变更历史

| 版本 | 日期 | 变更类型 | 主要内容 |
|------|------|---------|---------|
| **1.0.0** | 2026-08-04 | MAJOR | 首个正式基线：Java/Spring/MyBatis 全栈热更新，覆盖 Class、Mapper XML、配置文件和 Web 静态资源，并提供作用范围、运行配置排除、诊断日志及安全回滚能力 |

---

## 🎯 开发约束

### 1. 构建要求
- **Java 版本**：JDK 21（Gradle 启动 JVM；toolchain 负责各模块编译器）
- **JDK 路径参考**：
  - JDK 8: `D:\tools\jdk\jdk8`
  - JDK 11: `D:\tools\jdk\jdk-11.0.2`
  - JDK 21: `D:\tools\jdk\jdk-21.0.11+10` ✅ 构建推荐
  - JDK 23: `D:\tools\jdk\jdk-23.0.2`
  - JDK 24: `D:\tools\jdk\jdk-24.0.1`
  - JDK 25: `D:\tools\jdk\jdk-25.0.2`
- **编码**：UTF-8（已配置）
- **构建前清理**：`./gradlew clean` 避免缓存问题
- **切换 JDK**：
  ```powershell
  # 临时切换（当前会话）
  $env:JAVA_HOME = 'D:\tools\jdk\jdk-21.0.11+10'
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  
  # 验证版本
  java -version
  ```

### 2. 模块职责划分

| 模块 | 职责 | 依赖约束 |
|------|------|---------|
| `hotreload-protocol` | 协议定义、类型检测 | 无外部依赖（纯 Java） |
| `hotreload-bootstrap` | Agent 启动引导、JVM Hook | 最小依赖 |
| `hotreload-agent` | 核心热重载逻辑 | 反射调用 Spring（不直接依赖） |
| `hotreload-idea` | IDEA 插件 UI/通信 | IntelliJ Platform SDK |

### 3. 兼容性要求
- **Java 8 模块**：`protocol`、`bootstrap`、`agent`（JDK 8 target）
- **Java 21 模块**：`hotreload-idea`（IDEA 插件要求）

---

## 🚫 严格禁止

### 1. 版本号管理
- ❌ 在子模块 `build.gradle.kts` 中单独定义 `version`
- ❌ 在代码中硬编码版本号（如 `"1.0.0"` 字符串）
- ❌ 功能开发后忘记递增版本号

### 2. 依赖管理
- ❌ 在 `protocol`/`bootstrap` 中引入 Spring 依赖
- ❌ 在 `agent` 中直接 `import org.springframework.*`（必须反射调用）

### 3. 构建流程
- ❌ 跳过 `clean` 直接 `build`（可能残留旧 class）
- ❌ 修改代码后不验证插件包是否正确生成

---

## 📋 AI Agent 工作流程

### 新功能开发
```
1. 确定版本号递增规则（MAJOR/MINOR/PATCH）
2. 修改 build.gradle.kts 的 version 字段
3. 实现功能
4. 构建验证：
   ./gradlew clean build -x test
   ./gradlew :hotreload-idea:buildPlugin
5. 更新 AGENTS.md 版本变更历史
6. 提交代码（commit message 需包含版本号）
```

### Bug 修复
```
1. 递增 PATCH 版本号（如 1.0.0 → 1.0.1）
2. 修复 Bug
3. 构建验证
4. 更新版本历史
```

---

## 📄 提交规范

### Commit Message 格式
```
<type>: <subject> (v<version>)

<body>

<footer>
```

**示例**：
```
feat: 发布首个正式基线版本 (v1.0.0)

- 支持 Java/Spring/MyBatis 全栈热更新
- 支持配置文件与 Web 静态资源热更新
- 提供作用范围、运行配置排除与诊断日志

Closes #123
```

### Type 类型
| Type | 说明 | 版本影响 |
|------|------|---------|
| `feat` | 新功能 | MINOR |
| `fix` | Bug 修复 | PATCH |
| `refactor` | 重构（不改变功能） | PATCH |
| `perf` | 性能优化 | PATCH |
| `docs` | 文档更新 | 无 |
| `test` | 测试相关 | 无 |
| `chore` | 构建/工具配置 | 无 |
| `breaking` | 破坏性变更 | MAJOR |

---

## 🔍 验证清单

开发完成后必须检查：

- [ ] `build.gradle.kts` 版本号已更新
- [ ] `AGENTS.md` 版本历史已记录
- [ ] 插件包路径包含正确版本号：`hotreload-idea-<version>.zip`
- [ ] Commit message 包含版本号标识
- [ ] 所有模块构建成功（无编译错误）
- [ ] 功能已验证（手动测试或单元测试）

---

## 🛠️ 本地 JDK 路径参考

| JDK 版本 | 路径 | 用途 |
|---------|------|------|
| JDK 8 | `D:\tools\jdk\jdk8` | 运行时兼容性测试 |
| JDK 11 | `D:\tools\jdk\jdk-11.0.2` | 备用 |
| JDK 21 | `D:\tools\jdk\jdk-21.0.11+10` | **构建插件使用** |
| JDK 23 | `D:\tools\jdk\jdk-23.0.2` | 测试新特性 |
| JDK 24 | `D:\tools\jdk\jdk-24.0.1` | 测试新特性 |
| JDK 25 | `D:\tools\jdk\jdk-25.0.2` | 测试新特性 |

### JDK 切换命令

```powershell
# 构建插件（使用 JDK 21）
$env:JAVA_HOME = 'D:\tools\jdk\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build -x test
.\gradlew.bat :hotreload-idea:buildPlugin

# 使用其他版本
$env:JAVA_HOME = 'D:\tools\jdk\jdk8'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat <task>
```

---

## 📌 注意事项

1. **版本号是唯一标识**：发布后不可修改已发布版本的版本号
2. **向下兼容优先**：非必要不做 MAJOR 版本升级
3. **变更历史必填**：方便后续追溯问题
4. **构建产物路径**：`hotreload-idea/build/distributions/hotreload-idea-<version>.zip`
5. **构建环境要求**：必须使用 JDK 21 启动 Gradle；toolchain 不会替换启动 Gradle 的旧 JVM

---

**最后更新**：2026-08-04
**当前版本**：v1.0.0
