package com.pdk.update.service;

import com.pdk.common.exception.BusinessException;
import com.pdk.update.config.ClientUpdateProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class UpdateArtifactStorage {
    private final ClientUpdateProperties properties;

    public StoredFile store(long bizId, long releaseId, long artifactId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(42290, "升级包不能为空");
        String name = safeFileName(file.getOriginalFilename());
        Path root = root();
        Path temp = root.resolve("quarantine").resolve(artifactId + ".part").normalize();
        try {
            Files.createDirectories(temp.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream input = new java.security.DigestInputStream(file.getInputStream(), digest)) {
                size = Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            String storageKey = "biz-" + bizId + "/release-" + releaseId + "/artifact-" + artifactId + "-" + sha + ".zip";
            return new StoredFile(name, storageKey, temp, size, sha);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(50390, "升级包存储失败: " + e.getMessage()); }
    }

    public StoredFile promote(StoredFile stored) {
        Path published=root().resolve("published"); Path target=published.resolve(stored.storageKey()).normalize();
        ensureInside(published,target);
        try { Files.createDirectories(target.getParent()); Files.move(stored.path(),target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) {
            try { Files.move(stored.path(),target,StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception inner) { throw new BusinessException(50390,"构件移入发布区失败"); }
        } catch (Exception e) { throw new BusinessException(50390,"构件移入发布区失败"); }
        return new StoredFile(stored.fileName(),stored.storageKey(),target,stored.size(),stored.sha256());
    }

    public void discard(StoredFile stored) { try { Files.deleteIfExists(stored.path()); } catch (Exception ignored) { } }

    public void removePublished(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.startsWith("pending/")) return;
        Path published=root().resolve("published"); Path target=published.resolve(storageKey).normalize(); ensureInside(published,target);
        try { Files.deleteIfExists(target); } catch (Exception ignored) { }
    }

    public void removeArtifact(long artifactId, String storageKey) {
        removePublished(storageKey);
        Path quarantine=root().resolve("quarantine").resolve(artifactId+".part").normalize();
        ensureInside(root().resolve("quarantine"),quarantine);
        try { Files.deleteIfExists(quarantine); } catch (Exception ignored) { }
    }

    public Path resolve(String storageKey) {
        Path published = root().resolve("published");
        Path target = published.resolve(storageKey).normalize();
        ensureInside(published, target);
        if (!Files.isRegularFile(target)) throw new BusinessException(40490, "升级构件文件不存在");
        return target;
    }

    public String safeFileName(String value) {
        String raw = value == null ? "update.zip" : value;
        if (raw.contains("..") || raw.startsWith("/") || raw.startsWith("\\") || raw.matches("^[A-Za-z]:.*")
                || raw.chars().anyMatch(c -> c < 32)) {
            throw new BusinessException(42290, "升级包文件名不安全");
        }
        String name = Paths.get(raw).getFileName().toString();
        if (name.length() > 180) throw new BusinessException(42290, "升级包文件名过长");
        if (!name.toLowerCase().endsWith(".zip")) throw new BusinessException(42290, "首期仅支持 ZIP 完整包");
        return name;
    }

    private Path root() { return Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize(); }
    private void ensureInside(Path parent, Path target) {
        if (!target.startsWith(parent.toAbsolutePath().normalize())) throw new BusinessException(42290, "非法存储路径");
    }
    public record StoredFile(String fileName, String storageKey, Path path, long size, String sha256) {}
}
