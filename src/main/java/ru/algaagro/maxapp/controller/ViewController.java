package ru.algaagro.maxapp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    @GetMapping("/")
    public String root() {
        return "redirect:/miniapp/";
    }

    @GetMapping("/miniapp")
    public String miniApp() {
        return "forward:/miniapp/index.html";
    }

    @GetMapping("/miniapp/")
    public String miniAppWithSlash() {
        return "forward:/miniapp/index.html";
    }
}
