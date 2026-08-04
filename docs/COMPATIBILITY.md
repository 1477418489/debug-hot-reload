# Compatibility Matrix

## Product baseline

- **Version**: 1.0.1 (initial enhanced-redefine baseline patch)
- **Goal**: JRebel-grade hot reload for Spring / Spring Boot / MyBatis Debug development

## Supported

| Layer | Versions | Strategy |
|---|---|---|
| IntelliJ IDEA | 2024.3 – 2026.2 | Plugin `sinceBuild=243`, `untilBuild=262.*` |
| Target JDK | 8 / 11 / 17 / 21 | Agent bytecode `release=8` |
| Enhanced runtime | DCEVM-8 (altjvm) / JBR 17+ | Auto-detected; args injected on Debug only |
| Spring Framework | 5.x / 6.x | Reflection; no compile-time Spring dependency in agent |
| Spring Boot | 2.x / 3.x | Context / Environment reflection |
| MyBatis | 3.5.x | Instrumentation + Configuration snapshot reload |
| MyBatis-Plus | 3.5.x | `MybatisXMLMapperBuilder` instrumentation + optional cache clear |
| Servlet API | javax / jakarta | Runtime probe |

## Engine tiers

| Tier | Runtime | Structural changes |
|---|---|---|
| E2 enhanced redefine (primary) | DCEVM / JBR 17+ | Class identity kept, live instances & state preserved |
| E1 standard redefine | any JVM | Method bodies only |
| E3 generation fallback | any JVM | Additive changes representable by an assignable subclass; direct Spring beans only |

Notes:
- DCEVM-8 sessions additionally get `-XX:TieredStopAtLevel=1` to avoid a known
  non-deterministic C2 JIT crash after redefinition (VM-level DCEVM issue).
- JBR flag is injected only for JDK 17+ with `IMPLEMENTOR=JetBrains` in the release file;
  DCEVM only when the altjvm directory actually contains a JVM library.
- E3 reports success only after the corresponding direct Spring bean is rebound. Member
  removal/retyping, hierarchy changes, and non-Spring structural changes require E2 or restart.

## Capability adaptation

At agent start / HELLO the plugin logs `ENVIRONMENT_PROBE` with jdk/vendor,
spring/springBoot, mybatis/mybatisPlus, servletApi and the capability list; the
session-active line shows the resolved engine tier. Reload paths degrade gracefully
when a capability is absent, and the verdict is always one of
SUCCESS / SKIPPED / FAILED(+cause) / RESTART_REQUIRED.
