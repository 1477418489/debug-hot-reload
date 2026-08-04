# Changelog

版本号唯一来源为根项目 `build.gradle.kts`。插件内发布说明见 `hotreload-idea/src/main/resources/META-INF/plugin.xml`。

## 1.0.0 - 2026-08-04

首个正式基线版本，面向 IntelliJ IDEA Debug 场景提供完整、可配置且可诊断的 Java / Spring / MyBatis 热更新能力。

- 提供 E1 标准 redefine、E2 DCEVM/JBR 增强 redefine 和 E3 generation 降级引擎，支持方法体、字段、方法、注解及新增类等变更。
- 原地刷新 Spring 依赖注入、MVC 路由、AOP/事务/安全元数据和 Jackson 缓存，必要时给出明确的重启结论。
- 支持 MyBatis / MyBatis-Plus Mapper XML 增删改，多 `Configuration` 原子提交、失败整体回滚及缓存元数据保护。
- 支持 `application.properties` / YAML 配置在原 Spring 属性源优先级处更新，并对不安全结构或无法定位的属性源安全跳过。
- 支持 HTML、CSS、JavaScript、JSON、图片和字体等静态资源新增、修改、删除、移动及目录重命名，两阶段同步至对应 Debug 输出目录并清理 Spring MVC 资源缓存。
- 静态资源流程不调用 Spring DevTools LiveReload、模板引擎、浏览器或其他 UI 控件。
- 支持全局默认、项目级继承/启用/禁用、四类功能独立开关和 Debug 运行配置排除。
- 提供成功、已跳过、失败及需重启四态结论，以及环境探测、中文日志、诊断级别和可配置日志容量。
- 加强多 Debug 会话隔离、有序 classpath 遮蔽检查、稳定文件读取、路径与符号链接校验、协议鉴权、队列背压和异常恢复。
