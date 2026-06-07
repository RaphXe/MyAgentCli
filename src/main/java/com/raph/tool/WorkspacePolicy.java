package com.raph.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the file-system roots an agent may access during the current session.
 */
public class WorkspacePolicy {
    private final Path primaryRoot;
    private final Set<Path> workspaceRoots = ConcurrentHashMap.newKeySet();
    private final Set<Path> oneTimeAllowedPaths = ConcurrentHashMap.newKeySet();

    public WorkspacePolicy(Path primaryRoot) {
        Path root = normalize(primaryRoot == null ? Path.of(".") : primaryRoot);
        this.primaryRoot = root;
        this.workspaceRoots.add(root);
    }

    public static WorkspacePolicy defaultPolicy() {
        return new WorkspacePolicy(Path.of(System.getProperty("paicli.workspace.root", ".")));
    }

    public static WorkspacePolicy withRoots(Path primaryRoot, Collection<Path> extraRoots) {
        WorkspacePolicy policy = new WorkspacePolicy(primaryRoot);
        if (extraRoots != null) {
            for (Path root : extraRoots) {
                policy.expand(root);
            }
        }
        return policy;
    }

    public Path primaryRoot() {
        return primaryRoot;
    }

    public Set<Path> roots() {
        return Set.copyOf(workspaceRoots);
    }

    public Path resolve(String rawPath) {
        String value = rawPath == null || rawPath.isBlank() ? "." : rawPath.trim();
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = primaryRoot.resolve(path);
        }
        return normalize(path);
    }

    public boolean isInsideWorkspace(Path path) {
        Path normalized = normalize(path);
        for (Path root : workspaceRoots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAllowedOrConsumeOneTime(Path path) {
        Path normalized = normalize(path);
        if (isInsideWorkspace(normalized)) {
            return true;
        }
        for (Path allowed : oneTimeAllowedPaths) {
            if (normalized.startsWith(allowed) || normalized.equals(allowed)) {
                oneTimeAllowedPaths.remove(allowed);
                return true;
            }
        }
        return false;
    }

    public void allowOnce(Path path) {
        if (path != null) {
            oneTimeAllowedPaths.add(normalize(path));
        }
    }

    public Path expandFor(Path target, boolean directoryIntent) {
        Path root = suggestedRoot(target, directoryIntent);
        expand(root);
        return root;
    }

    public void expand(Path root) {
        if (root != null) {
            workspaceRoots.add(normalize(root));
        }
    }

    public void resetSessionState() {
        workspaceRoots.clear();
        workspaceRoots.add(primaryRoot);
        oneTimeAllowedPaths.clear();
    }

    public Path suggestedRoot(Path target, boolean directoryIntent) {
        Path normalized = normalize(target);
        if (directoryIntent || Files.isDirectory(normalized)) {
            return normalized;
        }
        Path parent = normalized.getParent();
        return parent == null ? normalized : parent;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
