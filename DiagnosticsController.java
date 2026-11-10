package com.example.demo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);
    private static final String HOTSPOT_BEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

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
     */
    @PostMapping("/heapdump")
    public ResponseEntity<String> generateHeapDump(@RequestParam(value = "dir", defaultValue = "/tmp") String dir) {
        try {
            Path targetDir = Paths.get(dir);
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
     * 1.3 Retrieve Large Heap Dump File (Chunked Streaming API)
     */
    @GetMapping("/heapdump/download")
    public ResponseEntity<StreamingResponseBody> downloadHeapDump(@RequestParam("path") String filePath) {
        File file = new File(filePath);
        
        if (!file.exists() || !file.isFile()) {
            log.error("Requested file not found: {}", filePath);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long fileSize = file.length();
        log.info("Streaming file: {} (Size: {} bytes)", filePath, fileSize);

        // Stream the file in chunks instead of loading it entirely into JVM memory
        StreamingResponseBody responseBody = outputStream -> {
            try (InputStream inputStream = new FileInputStream(file)) {
                byte[] buffer = new byte[8192]; // 8KB chunks
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            } catch (IOException e) {
                log.error("Error occurred while streaming heap dump file to client", e);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(fileSize)
                .body(responseBody);
    }

    /**
     * Helper method utilizing Reflection to invoke the HotSpotDiagnosticMXBean natively.
     */
    private static void dumpHeap(String filePath, boolean live) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Class<?> clazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
        Object bean = ManagementFactory.newPlatformMXBeanProxy(server, HOTSPOT_BEAN_NAME, clazz);
        Method method = clazz.getMethod("dumpHeap", String.class, boolean.class);
        method.invoke(bean, filePath, live);
    }
}
