package dev.hotreload.integration.boot2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** v1: two routes. The reload variant adds /demo/c using the injected service. */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @Autowired
    private DemoService demoService;

    @GetMapping("/a")
    public String a() {
        return "A1" + demoService.value();
    }

    @GetMapping("/{id}")
    public String byId(@PathVariable("id") Long id) {
        return "ID" + id;
    }
}
