package dev.hotreload.integration.boot2;

import org.springframework.stereotype.Service;

/** v3 service: value() gains the CUSTOM @Tagged annotation — the un-proxied bean must
 *  get a proxy woven via the generic advisor recomputation (no annotation name list). */
@Service
public class DemoService {
    @Tagged
    public String value() {
        return "SVC";
    }
}
