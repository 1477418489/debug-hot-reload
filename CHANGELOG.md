# Changelog

版本号唯一来源为根项目 `build.gradle.kts`。插件内发布说明见 `hotreload-idea/src/main/resources/META-INF/plugin.xml`。

## 1.0.1 - 2026-08-04

首个正式基线的可靠性补丁，收紧 Class、Mapper XML、配置文件和静态资源热更新的成功判定与失败边界。

- Class 批次在 JVM 原子重定义失败后逐类隔离重试，避免普通方法体变更被其他结构变更连带判为需重启。
- E3 generation 仅在变更可由可赋值子类表达且对应直接 Spring Bean 确实重建成功后报告成功；删除/改签名成员、层级变化、非 Spring 类及加载目标歧义会明确要求重启。
- 新类只在真实应用类加载器中定义；注解索引写入、Spring 重绑及 MVC 路由恢复失败不再产生假成功，条件化组件交由重启后的 Spring 冷启动流程判定。
- Mapper XML 多会话调度改为原子接纳并补全队列拒绝、文件生命周期、内容类型切换和有界目录扫描处理。
- 配置文件在多个 Spring Context 间事务更新，失败时回滚；回滚失败会保留明确的需重启结论和诊断。
- 静态资源按有效 Debug 会话和 source root 隔离，同步后严格检查 Spring MVC 缓存清理结果；大目录截断及缓存清理失败要求重启。
- 明确静态资源流程不调用 LiveReload、模板引擎、浏览器或其他 UI 控件。

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
