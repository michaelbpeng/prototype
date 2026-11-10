package com.example.demo.controller;

import com.example.demo.util.PathSecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.management.MBeanServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);
    private static final String HOTSPOT_BEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";
    private static final String DEFAULT_ALLOWED_DIR = "/tmp";

    /**
     * DTO for File Metadata Response
     */
    public static class FileInfo {
        public String name;
        public String absolutePath;
        public long sizeBytes;
        public long lastModified;

        public FileInfo(File file) {
            this.name = file.getName();
            this.absolutePath = file.getAbsolutePath();
            this.sizeBytes = file.length();
            this.lastModified = file.lastModified();
        }
    }

    /**
     * 1.1 Trigger Garbage Collection
     */
    @PostMapping("/gc")
    public ResponseEntity<String> triggerGC() {
        log.info("Manually triggering Garbage Collection requested.");
        Runtime.getRuntime().gc();
        return ResponseEntity.ok("Garbage collection triggered successfully.");
    }

    /**
     * 1.2 Generate Heap Dump
     * This ensures creation directories are validated within safe zones before executing the dump.
     */
    @PostMapping("/heapdump")
    public ResponseEntity<String> generateHeapDump(@RequestParam(value = "dir", defaultValue = DEFAULT_ALLOWED_DIR) String dir) {
        try {
            // Normalize path structure securely first
            Path baseDir = Paths.get(DEFAULT_ALLOWED_DIR).toAbsolutePath().normalize();
            Path targetDir = Paths.get(dir).toAbsolutePath().normalize();

            if (!targetDir.startsWith(baseDir)) {
                log.warn("SECURITY ALERT: Blocked heap dump creation directory redirect: {}", dir);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access Denied: Cannot dump outside permitted area.");
            }

            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            String fileName = "heapdump-" + Instant.now().toEpochMilli() + ".hprof";
            Path dumpPath = targetDir.resolve(fileName);
            String fullPath = dumpPath.toAbsolutePath().toString();

            log.info("Generating heap dump at: {}", fullPath);
            dumpHeap(fullPath, true);

            return ResponseEntity.ok("Heap dump generated successfully at: " + fullPath);
        } catch (Exception e) {
            log.error("Failed to generate heap dump", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to generate heap dump: " + e.getMessage());
        }
    }

    /**
     * 1.4 Secure List Files in Dump Zone
     */
    @GetMapping("/files")
    public ResponseEntity<?> listFiles(
            @RequestParam(value = "dir", defaultValue = DEFAULT_ALLOWED_DIR) String dir,
            @RequestParam(value = "hprofOnly", defaultValue = "true") boolean hprofOnly) {
        
        try {
            // Apply path security verification for directory type
            Path safeDir = PathSecurityUtil.validateAndGetSafePath(dir, DEFAULT_ALLOWED_DIR, false);

            try (Stream<Path> walk = Files.walk(safeDir, 1)) {
                List<FileInfo> fileList = walk
                        .map(Path::toFile)
                        .filter(File::isFile)
                        .filter(file -> !hprofOnly || file.getName().endsWith(".hprof"))
                        .map(FileInfo::new)
                        .collect(Collectors.toList());

                return ResponseEntity.ok(fileList);
            }
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getOriginalMessage() != null ? e.getMessage() : "Access Denied.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IOException e) {
            log.error("Failed to list files in directory", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing directory stream.");
        }
    }

    /**
     * 1.3 Retrieve Large Heap Dump File (Chunked Streaming API with Path Security)
     */
    @GetMapping("/heapdump/download")
    public ResponseEntity<?> downloadHeapDump(@RequestParam("path") String filePath) {
        try {
            // Apply path security verification for file type
            Path safeFilePath = PathSecurityUtil.validateAndGetSafePath(filePath, DEFAULT_ALLOWED_DIR, true);
            File file = safeFilePath.toFile();
            long fileSize = file.length();

            log.info("Streaming file securely: {} (Size: {} bytes)", safeFilePath, fileSize);

            StreamingResponseBody responseBody = outputStream -> {
                try (InputStream inputStream = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; // Flat 8KB chunks
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.flush();
                } catch (IOException e) {
                    log.error("Error occurred while streaming file to connection channel", e);
                }
            };

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(fileSize)
                    .body(responseBody);

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    /**
     * 1.5 Secure Delete Target Heap Dump
     */
    @DeleteMapping("/heapdump")
    public ResponseEntity<String> deleteHeapDump(@RequestParam("path") String filePath) {
        try {
            // Apply path security verification for file type
            Path safeFilePath = PathSecurityUtil.validateAndGetSafePath(filePath, DEFAULT_ALLOWED_DIR, true);

            // Additional Extension Hardening Rule
            String fileName = safeFilePath.getFileName().toString();
            if (!fileName.endsWith(".hprof")) {
                log.warn("SECURITY ALERT: Blocked attempt to delete a non-heapdump asset: {}", fileName);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Invalid Request: Only .hprof configurations can be dropped from memory diagnostics.");
            }

            Files.delete(safeFilePath);
            log.info("Successfully dropped diagnostic log context trace: {}", safeFilePath);
            return ResponseEntity.ok("File deleted successfully: " + fileName);

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IOException e) {
            log.error("Failed to execute asset deletion framework on path: {}", filePath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete file from volume: " + e.getMessage());
        }
    }

    /**
     * Native HotSpot MXBean Interface Loader
     */
    private static void dumpHeap(String filePath, boolean live) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Class<?> clazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
        Object bean = ManagementFactory.newPlatformMXBeanProxy(server, HOTSPOT_BEAN_NAME, clazz);
        Method method = clazz.getMethod("dumpHeap", String.class, boolean.class);
        method.invoke(bean, filePath, live);
    }
}
