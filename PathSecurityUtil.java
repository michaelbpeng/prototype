package com.example.demo.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathSecurityUtil {

    private static final Logger log = LoggerFactory.getLogger(PathSecurityUtil.class);

    private PathSecurityUtil() {
        // Prevent instantiation
    }

    /**
     * Validates that a target path exists, is a regular file/directory, 
     * and strictly resides inside the allowed base directory.
     *
     * @param inputPath     The raw file or directory path string provided by the user.
     * @param allowedDirStr The root sandbox directory string.
     * @param requireFile   If true, ensures the path points to an existing file. If false, allows a directory.
     * @return A normalized, safe Path object.
     * @throws SecurityException If directory traversal or access violations are detected.
     * @throws IllegalArgumentException If the path doesn't exist or doesn't match the required type.
     */
    public static Path validateAndGetSafePath(String inputPath, String allowedDirStr, boolean requireFile) {
        if (inputPath == null || allowedDirStr == null) {
            throw new IllegalArgumentException("Paths cannot be null.");
        }

        // 1. Normalize both paths to strip out structural manipulations like system relative dots (../)
        Path baseDir = Paths.get(allowedDirStr).toAbsolutePath().normalize();
        Path targetPath = Paths.get(inputPath).toAbsolutePath().normalize();

        // 2. Enforce Sandbox Boundary check
        if (!targetPath.startsWith(baseDir)) {
            log.warn("SECURITY ALERT: Path Traversal attempt rejected! Target: '{}' outside Sandbox: '{}'", targetPath, baseDir);
            throw new SecurityException("Access Denied: Path is outside the permitted directory structure.");
        }

        // 3. Structural Existence validations
        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("The requested path does not exist.");
        }

        if (requireFile && !Files.isRegularFile(targetPath)) {
            throw new IllegalArgumentException("The requested path is not a valid regular file.");
        }

        if (!requireFile && !Files.isDirectory(targetPath)) {
            throw new IllegalArgumentException("The requested path is not a valid directory.");
        }

        return targetPath;
    }
}
