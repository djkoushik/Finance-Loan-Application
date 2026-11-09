package com.rajarajeshwari.finance.admin;

import com.rajarajeshwari.finance.auth.Ctx;
import com.rajarajeshwari.finance.support.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/** Database backup and restore. Administrators only. */
@RestController
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/backup")
    public ResponseEntity<byte[]> backup() throws SQLException, IOException {
        requireAdmin();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + BackupService.fileName() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(service.createBackup());
    }

    @PostMapping("/api/admin/restore")
    public Map<String, Object> restore(@RequestParam("file") MultipartFile file, HttpSession session)
            throws SQLException, IOException {
        requireAdmin();
        if (file.isEmpty()) {
            throw new ApiException(400, "Please choose a backup file");
        }
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("finance-upload-", ".tmp");
        Map<String, Object> result;
        try {
            file.transferTo(tmp.toFile());
            result = service.restore(tmp);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
        session.invalidate(); // the users in the backup replace the current ones: log in again
        return result;
    }

    private static void requireAdmin() {
        if (!Ctx.user().isAdmin()) {
            throw new ApiException(403, "Only an administrator can back up or restore data");
        }
    }
}
