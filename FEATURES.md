# 功能列表（1.0.0 基线）

> 通用热更新能力，不做业务项目定制。功能路径零注解硬编码。
> 状态标记：✅ 已支持并有测试防线；◐ 受限支持（日志明示）；➖ 不支持（明确提示）。

## 引擎分级

| 引擎 | 触发条件 | 能力 |
|---|---|---|
| E2 增强 redefine（主线） | DCEVM(-XXaltjvm=dcevm) / JBR 17+(-XX:+AllowEnhancedClassRedefinition)，自动注入 | 任意结构变更保持类身份，实例与状态保留 |
| E1 标准 redefine | 所有 JVM | 方法体/常量修改 |
| E3 generation 降级 | 无增强运行时时的结构变更 | 可赋值子类方案，限 Spring Bean 场景 |

## 能力矩阵

### A. Java 类
- ✅ 方法体修改（E1/E2，全部实例即时生效）
- ✅ 新增/删除方法、修改签名（E2 就地生效；E3 降级）
- ✅ 新增/删除字段、修改字段类型（E2；已存实例新字段为默认值）
- ✅ 新增类（含新 @Component 自动注册 Bean）
- ✅ 非 Spring Bean（DTO/实体/工具类）结构变更（E2 原生支持）
- ✅ 单例运行状态保留（E2 不销毁 Bean）
- ◐ 枚举加值 / 静态初始化表达式变更（提示需重启）
- ➖ 删除类（JVM 限制）

### B. 注解（通用，无名单）
- ✅ 类/方法/参数注解增删改，反射与 Spring 缓存一致
- ✅ mapping 注解变更 → 路由增量注销/重注册，其他 Controller 零影响
- ✅ 切面注解（标准或**任意自定义** @annotation 切面）增删即时生效：
  - 已代理 Bean：拦截链缓存清空后按新注解重算
  - 首次引入：Spring advisor 重算判定 → 定向重建织入代理
- ✅ @Transactional / @PreAuthorize 属性缓存清理
- ✅ Jackson 序列化注解（@JsonIgnore 等）→ 序列化器缓存刷新
- ✅ 新增 @Autowired/@Value/@Resource 字段 → 对存活实例补注入

### C. Spring MVC
- ✅ RequestMapping 增量刷新（注销/重注册精确限定变更类）
- ✅ 新增接口带 @PathVariable 的路由精度（不被模板路由误抢）
- ✅ 路由自检（SelfCheck）：注销后未恢复 → 强制「需重启」结论
- ✅ 删除接口后的路由语义与冷启动应用逐比特一致（e2e 断言）

### D. Mapper XML（MyBatis / MyBatis-Plus 3.5.x）
- ✅ 语句/resultMap/sqlFragment 增删改（快照回滚 + SHA 校验 + 缓存失效）
- ✅ 多 SqlSessionFactory、多模块同名资源、输出目录缺副本回退源文件
- ◐ 新增全新 XML 文件（新 namespace）→ 提示需重启

### E. 配置文件
- ✅ application*.properties / yml 中的配置键增删改 → Environment 即时生效
- ✅ 在原属性源优先级处替换已加载配置，保留命令行参数/系统属性优先级并支持键删除
- ✅ 无法确认原属性源时安全跳过，不用 `addFirst` 抢占最高优先级
- ◐ YAML 仅支持简单嵌套 mapping 和字符串标量；列表、多文档、锚点、流式集合、块标量、null 等整体拒绝
- ◐ @Value 已注入字段不自动刷新（触发相关类热更即可拿到新值）

### F. Web 静态资源
- ✅ HTML/CSS/JS/JSON、图片、字体等静态资源新增、修改、删除、移动和目录重命名
- ✅ 保存后原子同步到对应模块 Debug 输出目录，再清理 Spring MVC 资源缓存
- ✅ 检测有序 Debug 类路径遮蔽；仍保持模块输出同步，但遮蔽期间不发送无效缓存刷新
- ✅ 路径包含检查、符号链接拒绝、稳定读取、大小限制和写入后校验
- ➖ 不调用 Spring DevTools LiveReload、模板引擎或浏览器控件；浏览器自动刷新不属于插件职责

### G. 设置与作用范围
- ✅ 全局默认与项目级继承/显式启用/显式禁用
- ✅ Java、Mapper XML、配置文件、静态资源四类功能独立开关
- ✅ 排除指定 Application / Spring Boot Debug 运行配置
- ✅ 普通/诊断日志级别和 100—10000 条日志容量
- ✅ 设置页显示项目 JDK、增强运行时探测和预计注入参数

### H. 可靠性与可观测
- ✅ 四态结论：成功 / 已跳过 / 失败+根因 / 需重启（错误码 SPRING_REBIND_INCOMPLETE 等）
- ✅ 中文结果日志 + verbose 技术摘要
- ✅ 会话激活显示引擎等级与环境探测（JDK/Spring/Boot/MyBatis/Servlet API）
- ✅ 自动禁用 IDEA 内置 HotSwap 避免冲突，会话结束恢复

## 测试防线

- 单元测试：引擎探测矩阵、路由决策、in-place 决策表、协议往返（120+ 用例）
- e2e（真实 JVM）：纯 MyBatis 矩阵（JDK 8/11/21）；Spring Boot 2.7 + JDK8/DCEVM 五场景
  （纯注解增删 / 无代理 Bean 自定义注解引入 / 新增方法+注入+拦截链 / 回滚 / 删除路由语义比对）
