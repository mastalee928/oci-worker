package com.ociworker.controller;

import com.ociworker.model.vo.ResponseData;
import com.ociworker.service.BackupService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/sys/backup")
public class BackupController {

    @Resource
    private BackupService backupService;

    @PostMapping("/create")
    public void createBackup(@RequestParam String password, HttpServletResponse response) throws IOException {
        byte[] data = backupService.createBackup(password);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=oci-worker-backup.zip");
        response.getOutputStream().write(data);
    }

    @PostMapping("/restore")
    public ResponseData<?> restore(@RequestParam("file") MultipartFile file,
                                    @RequestParam String password) throws IOException {
        backupService.restoreBackup(file.getBytes(), password);
        return ResponseData.ok();
    }
}
