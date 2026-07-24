package com.ociworker.webssh;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ociworker.service.WebSshBookmarkService;

import java.util.Map;

@RestController
@RequestMapping("/webssh-api/bookmarks")
public class WebSshBookmarkController {

    private final WebSshBookmarkService bookmarkService;

    public WebSshBookmarkController(WebSshBookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @GetMapping
    public Map<String, Object> list() {
        return WebSshResponse.ok(bookmarkService.list());
    }

    @PostMapping("/connections")
    public Map<String, Object> saveConnection(@RequestBody WebSshBookmarkDto.ConnectionInput input) {
        return WebSshResponse.ok(bookmarkService.saveConnection(input));
    }

    @DeleteMapping("/connections/{id}")
    public Map<String, Object> deleteConnection(@PathVariable String id) {
        return WebSshResponse.ok(bookmarkService.deleteConnection(id));
    }

    @PostMapping("/scripts")
    public Map<String, Object> saveScript(@RequestBody WebSshBookmarkDto.ScriptInput input) {
        return WebSshResponse.ok(bookmarkService.saveScript(input));
    }

    @DeleteMapping("/scripts/{id}")
    public Map<String, Object> deleteScript(@PathVariable String id) {
        return WebSshResponse.ok(bookmarkService.deleteScript(id));
    }

    @PostMapping("/migrate")
    public Map<String, Object> migrate(@RequestBody(required = false) WebSshBookmarkDto.MigrationRequest request) {
        return WebSshResponse.ok(bookmarkService.migrate(request));
    }
}
