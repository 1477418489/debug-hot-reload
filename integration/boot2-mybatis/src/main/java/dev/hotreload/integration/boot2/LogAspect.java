package dev.hotreload.integration.boot2;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Forces CGLIB proxying of controllers and provides an @annotation advice for hot reload tests. */
@Aspect
@Component
public class LogAspect {
    @Around("execution(* dev.hotreload.integration.boot2.DemoController.*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        return joinPoint.proceed();
    }

    @Around("@annotation(dev.hotreload.integration.boot2.Tagged)")
    public Object aroundTagged(ProceedingJoinPoint joinPoint) throws Throwable {
        return "TAGGED:" + joinPoint.proceed();
    }
}
