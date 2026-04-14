package com.ociworker.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {

    @GetMapping(value = {"/", "/{path:^(?!api|ws).*$}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
