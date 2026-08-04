package dev.hotreload.integration.boot2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** v2 reload payload: /demo/c is NEW and reads the injected service (user's null-service bug). */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @Autowired
    private DemoService demoService;

    @Tagged
    @GetMapping("/a")
    public String a() {
        return "A2" + demoService.value();
    }

    @GetMapping("/{id}")
    public String byId(@PathVariable("id") Long id) {
        return "ID" + id;
    }

    @GetMapping("/c")
    public String c() {
        return "C1" + demoService.value();
    }
}
