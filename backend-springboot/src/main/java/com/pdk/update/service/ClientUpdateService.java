package com.pdk.update.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.Business;
import com.pdk.platform.business.BusinessService;
import com.pdk.security.AdminBusinessScope;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.AdminAuditService;
import com.pdk.update.config.ClientUpdateProperties;
import com.pdk.update.domain.*;
import com.pdk.update.dto.UpdateAdminDtos.*;
import com.pdk.update.dto.UpdateClientDtos.*;
import com.pdk.update.mapper.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
public class ClientUpdateService {
    private static final Set<String> CHANNELS = Set.of("STABLE", "BETA");
    private static final Set<String> PLATFORMS = Set.of("WINDOWS");
    private static final Set<String> ARCHES = Set.of("X64");
    private static final Set<String> EVENTS = Set.of("CHECKED", "OFFERED", "DOWNLOAD_STARTED", "DOWNLOAD_COMPLETED",
            "VERIFY_SUCCEEDED", "VERIFY_FAILED", "INSTALL_STARTED", "INSTALL_SUCCEEDED", "INSTALL_FAILED");
    private final ClientUpdatePolicyMapper policyMapper;
    private final ClientReleaseMapper releaseMapper;
    private final ClientArtifactMapper artifactMapper;
    private final ClientUpdateEventMapper eventMapper;
    private final ClientUpdateOperationMapper operationMapper;
    private final BusinessService businessService;
    private final AdminBusinessScope businessScope;
    private final AdminAuditService auditService;
    private final UpdateArtifactStorage storage;
    private final UpdateSecurityService security;
    private final ClientUpdateProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public IPage<ClientRelease> releases(AdminPrincipal admin, Long bizId, String channel, String status, int page, int size) {
        bizId = businessScope.enforce(admin, bizId);
        LambdaQueryWrapper<ClientRelease> query = new LambdaQueryWrapper<>();
        query.eq(bizId != null, ClientRelease::getBizId, bizId)
                .eq(channel != null && !channel.isBlank(), ClientRelease::getChannel, upper(channel))
                .eq(status != null && !status.isBlank(), ClientRelease::getStatus, upper(status))
                .orderByDesc(ClientRelease::getCreatedAt);
        return releaseMapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), query);
    }

    public List<ClientArtifact> artifacts(AdminPrincipal admin, long releaseId) {
        ClientRelease release = requireRelease(releaseId);
        businessScope.enforce(admin, release.getBizId());
        return artifactMapper.selectList(new LambdaQueryWrapper<ClientArtifact>()
                .eq(ClientArtifact::getReleaseId, releaseId).orderByAsc(ClientArtifact::getId));
    }

    @Transactional
    public ClientRelease createRelease(CreateRelease dto, AdminPrincipal admin, HttpServletRequest request) {
        Business business = businessService.requireByAppId(dto.appId());
        businessScope.enforce(admin, business.getId());
        ClientRelease retry = releaseMapper.selectOne(new LambdaQueryWrapper<ClientRelease>()
                .eq(ClientRelease::getBizId, business.getId()).eq(ClientRelease::getRequestId, dto.requestId()));
        if (retry != null) {
            if (!retry.getVersion().equals(dto.version()) || !retry.getChannel().equals(upper(dto.channel())))
                throw new BusinessException(40990,"requestId 已用于其他版本草稿");
            return retry;
        }
        SemanticVersion version = SemanticVersion.parse(dto.version());
        validateChannel(dto.channel());
        SemanticVersion.parse(dto.minimumUpdaterVersion());
        ClientRelease release = new ClientRelease();
        release.setBizId(business.getId()); release.setVersion(version.toString());
        release.setVersionMajor(version.major()); release.setVersionMinor(version.minor()); release.setVersionPatch(version.patch());
        release.setChannel(upper(dto.channel())); release.setMinimumProtocolVersion(dto.minimumProtocolVersion());
        release.setMinimumUpdaterVersion(dto.minimumUpdaterVersion()); release.setReleaseNotes(dto.releaseNotes());
        release.setStatus("DRAFT"); release.setRolloutPercentage(dto.rolloutPercentage()); release.setEverPublished(0);
        release.setCreatedBy(admin.username()); release.setUpdatedBy(admin.username()); release.setRequestId(dto.requestId());
        try { releaseMapper.insert(release); } catch (DuplicateKeyException e) { throw new BusinessException(40991, "该业务版本或 requestId 已存在"); }
        auditService.record(admin, business.getId(), "CREATE_CLIENT_RELEASE", "CLIENT_RELEASE", release.getId().toString(), null,
                snapshot(release), "创建升级版本草稿", request);
        return release;
    }

    @Transactional
    public ClientRelease editRelease(long id, EditRelease dto, AdminPrincipal admin, HttpServletRequest request) {
        ClientRelease release = requireRelease(id); businessScope.enforce(admin, release.getBizId());
        if (!Set.of("DRAFT", "READY").contains(release.getStatus())) stateError();
        String before = snapshot(release);
        if (dto.releaseNotes() != null) release.setReleaseNotes(dto.releaseNotes());
        if (dto.rolloutPercentage() != null) release.setRolloutPercentage(dto.rolloutPercentage());
        if (dto.minimumProtocolVersion() != null) release.setMinimumProtocolVersion(dto.minimumProtocolVersion());
        if (dto.minimumUpdaterVersion() != null) { SemanticVersion.parse(dto.minimumUpdaterVersion()); release.setMinimumUpdaterVersion(dto.minimumUpdaterVersion()); }
        release.setUpdatedBy(admin.username()); releaseMapper.updateById(release);
        auditService.record(admin, release.getBizId(), "EDIT_CLIENT_RELEASE", "CLIENT_RELEASE", id + "", before, snapshot(release), "编辑发布元数据", request);
        return release;
    }

    @Transactional
    public ClientArtifact createArtifact(long releaseId, CreateArtifact dto, AdminPrincipal admin, HttpServletRequest request) {
        ClientRelease release = requireRelease(releaseId); businessScope.enforce(admin, release.getBizId());
        if (!"DRAFT".equals(release.getStatus())) stateError();
        validateTarget(dto.platform(), dto.arch(), dto.packageType());
        ClientArtifact retry = artifactMapper.selectOne(new LambdaQueryWrapper<ClientArtifact>()
                .eq(ClientArtifact::getBizId, release.getBizId()).eq(ClientArtifact::getRequestId, dto.requestId()));
        if (retry != null) {
            if (!retry.getReleaseId().equals(releaseId) || !retry.getPlatform().equals(upper(dto.platform())) || !retry.getArch().equals(upper(dto.arch())))
                throw new BusinessException(40990,"requestId 已用于其他构件上传");
            return retry;
        }
        ClientArtifact existing = artifactMapper.selectOne(new LambdaQueryWrapper<ClientArtifact>()
                .eq(ClientArtifact::getReleaseId, releaseId).eq(ClientArtifact::getPlatform, upper(dto.platform()))
                .eq(ClientArtifact::getArch, upper(dto.arch())).eq(ClientArtifact::getPackageType, upper(dto.packageType())).last("LIMIT 1"));
        if (existing != null) return existing; // 允许上传/签名中断后从原 artifact 恢复，避免唯一键冲突
        ClientArtifact artifact = new ClientArtifact();
        artifact.setReleaseId(releaseId); artifact.setBizId(release.getBizId()); artifact.setPlatform(upper(dto.platform()));
        artifact.setArch(upper(dto.arch())); artifact.setPackageType(upper(dto.packageType()));
        artifact.setFileName(storage.safeFileName(dto.fileName())); artifact.setStorageKey("pending/" + UUID.randomUUID());
        artifact.setStatus("UPLOADING"); artifact.setRequestId(dto.requestId());
        artifactMapper.insert(artifact);
        auditService.record(admin, release.getBizId(), "CREATE_UPLOAD_SESSION", "CLIENT_ARTIFACT", artifact.getId()+"", null,
                snapshot(artifact), "创建构件上传会话", request);
        return artifact;
    }

    @Transactional
    public ClientArtifact uploadContent(long artifactId, MultipartFile file, AdminPrincipal admin) {
        ClientArtifact artifact = requireArtifact(artifactId); businessScope.enforce(admin, artifact.getBizId());
        if ("AVAILABLE".equals(artifact.getStatus())) return artifact;
        if (!Set.of("UPLOADING","STORED").contains(artifact.getStatus())) stateError();
        ClientRelease release = requireRelease(artifact.getReleaseId());
        String previousStorageKey=artifact.getStorageKey();
        UpdateArtifactStorage.StoredFile stored = storage.store(artifact.getBizId(), artifact.getReleaseId(), artifactId, file);
        try { validateZip(stored.path(), release, artifact); stored=storage.promote(stored); }
        catch (RuntimeException e) { storage.discard(stored); throw e; }
        artifact.setFileName(stored.fileName()); artifact.setStorageKey(stored.storageKey()); artifact.setFileSize(stored.size());
        artifact.setSha256(stored.sha256()); artifact.setStatus("STORED"); artifactMapper.updateById(artifact);
        if (!stored.storageKey().equals(previousStorageKey)) storage.removePublished(previousStorageKey);
        return artifact;
    }

    @Transactional
    public ClientArtifact completeArtifact(long artifactId, Action action, AdminPrincipal admin, HttpServletRequest request) {
        ClientArtifact artifact = requireArtifact(artifactId); businessScope.enforce(admin, artifact.getBizId());
        if (repeated(artifact.getBizId(),action.requestId(),"COMPLETE_ARTIFACT","CLIENT_ARTIFACT",artifactId+"")) return artifact;
        if ("AVAILABLE".equals(artifact.getStatus())) { recordOperation(artifact.getBizId(),action.requestId(),"COMPLETE_ARTIFACT","CLIENT_ARTIFACT",artifactId+""); return artifact; }
        if (!"STORED".equals(artifact.getStatus())) throw new BusinessException(40990, "构件尚未上传并通过校验");
        ClientRelease release = requireRelease(artifact.getReleaseId());
        validateZip(storage.resolve(artifact.getStorageKey()), release, artifact);
        String canonical = artifactCanonical(businessService.requireById(artifact.getBizId()).getAppId(), release, artifact);
        artifact.setSignatureAlgorithm("Ed25519"); artifact.setSigningKeyId(properties.getArtifactKeyId());
        artifact.setSignatureValue(security.signArtifact(canonical)); artifact.setStatus("AVAILABLE"); artifactMapper.updateById(artifact);
        auditService.record(admin, artifact.getBizId(), "COMPLETE_CLIENT_ARTIFACT", "CLIENT_ARTIFACT", artifactId+"", "STORED", "AVAILABLE", action.reason(), request);
        recordOperation(artifact.getBizId(),action.requestId(),"COMPLETE_ARTIFACT","CLIENT_ARTIFACT",artifactId+"");
        return artifact;
    }

    @Transactional
    public ClientRelease transition(long id, String target, Action action, AdminPrincipal admin, HttpServletRequest request) {
        ClientRelease release = requireRelease(id); businessScope.enforce(admin, release.getBizId()); target = upper(target);
        String operation="RELEASE_"+target;
        if (repeated(release.getBizId(),action.requestId(),operation,"CLIENT_RELEASE",id+"")) return release;
        if (target.equals(release.getStatus())) { recordOperation(release.getBizId(),action.requestId(),operation,"CLIENT_RELEASE",id+""); return release; }
        Map<String, Set<String>> allowed = Map.of("DRAFT", Set.of("READY"), "READY", Set.of("DRAFT", "PUBLISHED"),
                "PUBLISHED", Set.of("SUSPENDED", "ARCHIVED"), "SUSPENDED", Set.of("PUBLISHED", "ARCHIVED"));
        if (!allowed.getOrDefault(release.getStatus(), Set.of()).contains(target)) stateError();
        if ("READY".equals(target) || "PUBLISHED".equals(target)) requireAvailableArtifact(id);
        if ("SUSPENDED".equals(target) || "ARCHIVED".equals(target)) ensureNotMandatoryTarget(id);
        String before = release.getStatus(); release.setStatus(target); release.setUpdatedBy(admin.username());
        if ("PUBLISHED".equals(target)) { release.setEverPublished(1); release.setPublishedBy(admin.username()); release.setPublishedAt(LocalDateTime.now()); }
        releaseMapper.updateById(release);
        auditService.record(admin, release.getBizId(), "CLIENT_RELEASE_" + target, "CLIENT_RELEASE", id+"", before, target, action.reason(), request);
        recordOperation(release.getBizId(),action.requestId(),operation,"CLIENT_RELEASE",id+"");
        return release;
    }

    @Transactional
    public ClientRelease updateRollout(long id, UpdateRollout body, AdminPrincipal admin, HttpServletRequest request) {
        ClientRelease release=requireRelease(id); businessScope.enforce(admin,release.getBizId());
        if (repeated(release.getBizId(),body.requestId(),"UPDATE_ROLLOUT","CLIENT_RELEASE",id+"")) return release;
        if (!"PUBLISHED".equals(release.getStatus())) stateError();
        if (body.rolloutPercentage()!=100 && policyMapper.selectCount(new LambdaQueryWrapper<ClientUpdatePolicy>()
                .eq(ClientUpdatePolicy::getMandatoryReleaseId,id).eq(ClientUpdatePolicy::getUpdateEnabled,1))>0)
            throw new BusinessException(40990,"强制更新目标必须保持 100% 全量");
        int before=release.getRolloutPercentage(); release.setRolloutPercentage(body.rolloutPercentage()); release.setUpdatedBy(admin.username()); releaseMapper.updateById(release);
        auditService.record(admin,release.getBizId(),"UPDATE_CLIENT_ROLLOUT","CLIENT_RELEASE",id+"",String.valueOf(before),String.valueOf(body.rolloutPercentage()),body.reason(),request);
        recordOperation(release.getBizId(),body.requestId(),"UPDATE_ROLLOUT","CLIENT_RELEASE",id+"");
        return release;
    }

    @Transactional
    public void deleteDraft(long id, Action action, AdminPrincipal admin, HttpServletRequest request) {
        ClientUpdateOperation retry=operationMapper.selectOne(new LambdaQueryWrapper<ClientUpdateOperation>()
                .eq(ClientUpdateOperation::getRequestId,action.requestId()).eq(ClientUpdateOperation::getOperationType,"DELETE_DRAFT")
                .eq(ClientUpdateOperation::getTargetType,"CLIENT_RELEASE").eq(ClientUpdateOperation::getTargetId,id+"").last("LIMIT 1"));
        if (retry!=null) return;
        ClientRelease release=requireRelease(id);businessScope.enforce(admin,release.getBizId());
        if (!"DRAFT".equals(release.getStatus()) || release.getEverPublished()!=0) stateError();
        List<ClientArtifact> artifacts=artifactMapper.selectList(new LambdaQueryWrapper<ClientArtifact>().eq(ClientArtifact::getReleaseId,id));
        String before=snapshot(Map.of("release",release,"artifacts",artifacts));
        artifactMapper.delete(new LambdaQueryWrapper<ClientArtifact>().eq(ClientArtifact::getReleaseId,id));
        releaseMapper.deleteById(id);
        recordOperation(release.getBizId(),action.requestId(),"DELETE_DRAFT","CLIENT_RELEASE",id+"");
        auditService.record(admin,release.getBizId(),"DELETE_CLIENT_DRAFT","CLIENT_RELEASE",id+"",before,null,action.reason(),request);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { artifacts.forEach(a->storage.removeArtifact(a.getId(),a.getStorageKey())); }
                });
    }

    public ClientUpdatePolicy getPolicy(AdminPrincipal admin, long bizId, String channel, String platform, String arch) {
        businessScope.enforce(admin, bizId); validateChannel(channel); validateTarget(platform, arch, "ZIP");
        return findPolicy(bizId, upper(channel), upper(platform), upper(arch));
    }

    @Transactional
    public ClientUpdatePolicy savePolicy(long bizId, SavePolicy dto, AdminPrincipal admin, HttpServletRequest request) {
        businessService.requireById(bizId); businessScope.enforce(admin, bizId); validateChannel(dto.channel()); validateTarget(dto.platform(), dto.arch(), "ZIP");
        ClientUpdatePolicy policy = findPolicy(bizId, upper(dto.channel()), upper(dto.platform()), upper(dto.arch()));
        if (repeated(bizId,dto.requestId(),"SAVE_POLICY","CLIENT_UPDATE_POLICY",bizId+":"+upper(dto.channel())+":"+upper(dto.platform())+":"+upper(dto.arch()))) return policy;
        if (policy != null && dto.policyRevision() != null && !dto.policyRevision().equals(policy.getPolicyRevision()))
            throw new BusinessException(40990, "策略已被其他管理员修改，请刷新后重试");
        if (dto.minimumSupportedVersion() != null && !dto.minimumSupportedVersion().isBlank()) {
            SemanticVersion minimum = SemanticVersion.parse(dto.minimumSupportedVersion());
            ClientRelease mandatory = requireRelease(dto.mandatoryReleaseId() == null ? 0 : dto.mandatoryReleaseId());
            if (!mandatory.getBizId().equals(bizId) || !mandatory.getChannel().equals(upper(dto.channel()))
                    || !"PUBLISHED".equals(mandatory.getStatus()) || mandatory.getRolloutPercentage() != 100
                    || SemanticVersion.parse(mandatory.getVersion()).compareTo(minimum) < 0) {
                throw new BusinessException(42290, "强制目标必须是同业务同版本线、已发布、100% 全量且版本不低于最低版本");
            }
            requireArtifact(mandatory.getId(), upper(dto.platform()), upper(dto.arch()));
        } else if (dto.mandatoryReleaseId() != null || Boolean.TRUE.equals(dto.serverEnforcementEnabled())) {
            throw new BusinessException(42290, "启用强制拦截前必须同时设置最低版本和强制目标");
        }
        String before = policy == null ? null : snapshot(policy);
        if (policy == null) { policy = new ClientUpdatePolicy(); policy.setBizId(bizId); policy.setChannel(upper(dto.channel())); policy.setPlatform(upper(dto.platform())); policy.setArch(upper(dto.arch())); policy.setPolicyRevision(1L); }
        else policy.setPolicyRevision(policy.getPolicyRevision() + 1);
        policy.setUpdateEnabled(dto.updateEnabled()?1:0); policy.setMinimumSupportedVersion(blankToNull(dto.minimumSupportedVersion()));
        policy.setMandatoryReleaseId(dto.mandatoryReleaseId()); policy.setServerEnforcementEnabled(dto.serverEnforcementEnabled()?1:0);
        policy.setOfflineGraceHours(dto.offlineGraceHours()); policy.setCheckIntervalSeconds(dto.checkIntervalSeconds()); policy.setUpdatedBy(admin.username());
        if (policy.getId() == null) policyMapper.insert(policy); else policyMapper.updateById(policy);
        auditService.record(admin, bizId, "SAVE_CLIENT_UPDATE_POLICY", "CLIENT_UPDATE_POLICY", policy.getId()+"", before, snapshot(policy), dto.reason(), request);
        recordOperation(bizId,dto.requestId(),"SAVE_POLICY","CLIENT_UPDATE_POLICY",bizId+":"+upper(dto.channel())+":"+upper(dto.platform())+":"+upper(dto.arch()));
        return policy;
    }

    public CheckResponse check(long appId, String deviceId, String currentVersion, String platform, String arch,
                               String channel, int protocolVersion, String updaterVersion) {
        Business business = businessService.requireByAppId(appId); // deliberately ignores business runtime status/handler
        rateLimit("check", business.getId(), deviceId, 120);
        SemanticVersion current = SemanticVersion.parse(currentVersion); SemanticVersion updater = SemanticVersion.parse(updaterVersion);
        validateChannel(channel); validateTarget(platform, arch, "ZIP");
        String ch = upper(channel), pf = upper(platform), ar = upper(arch); LocalDateTime now = LocalDateTime.now();
        String requestId = "UC-" + UUID.randomUUID();
        ClientUpdatePolicy policy = findPolicy(business.getId(), ch, pf, ar);
        if (policy == null) return none(requestId, protocolVersion, business, ch, pf, ar, currentVersion, updaterVersion, "UPDATE_POLICY_NOT_CONFIGURED", null, now);
        if (!properties.isEnabled() || policy.getUpdateEnabled() != 1) return none(requestId, protocolVersion, business, ch, pf, ar, currentVersion, updaterVersion, "UPDATE_SERVICE_DISABLED", policy, now);
        List<ClientRelease> published = releaseMapper.selectList(new LambdaQueryWrapper<ClientRelease>()
                .eq(ClientRelease::getBizId, business.getId()).eq(ClientRelease::getChannel, ch).eq(ClientRelease::getStatus, "PUBLISHED")
                .orderByDesc(ClientRelease::getVersionMajor).orderByDesc(ClientRelease::getVersionMinor).orderByDesc(ClientRelease::getVersionPatch));
        ClientRelease latest = published.stream().filter(r -> compatible(r, protocolVersion, updater)).filter(r -> findArtifact(r.getId(), pf, ar) != null).findFirst().orElse(null);
        ClientRelease target = null; String updatePolicy = "NONE", reason = latest == null ? "NO_COMPATIBLE_ARTIFACT" : "UP_TO_DATE";
        if (policy.getMinimumSupportedVersion() != null && current.compareTo(SemanticVersion.parse(policy.getMinimumSupportedVersion())) < 0) {
            ClientRelease mandatory = requireRelease(policy.getMandatoryReleaseId());
            if (!compatible(mandatory, protocolVersion, updater)) {
                return none(requestId, protocolVersion, business, ch, pf, ar, currentVersion, updaterVersion, "UPDATER_INCOMPATIBLE", policy, now);
            }
            target = mandatory; updatePolicy = "REQUIRED"; reason = "BELOW_MINIMUM_SUPPORTED_VERSION";
        } else if (latest != null && SemanticVersion.parse(latest.getVersion()).compareTo(current) > 0) {
            String anonymous = security.anonymousDevice(business.getId(), deviceId);
            if (anonymous != null && security.rolloutBucket(appId, latest.getId(), anonymous) < latest.getRolloutPercentage() * 100) {
                target = latest; updatePolicy = "OPTIONAL"; reason = "ROLLOUT_MATCHED";
            } else reason = anonymous == null ? "DEVICE_ID_REQUIRED_FOR_ROLLOUT" : "ROLLOUT_NOT_MATCHED";
        }
        ClientArtifact artifact = target == null ? null : requireArtifact(target.getId(), pf, ar);
        LocalDateTime expires = now.plusHours(properties.getPolicyTtlHours());
        String canonical = policyCanonical(protocolVersion, appId, ch, pf, ar, policy.getPolicyRevision(), updatePolicy,
                policy.getMinimumSupportedVersion(), policy.getMandatoryReleaseId(), target == null ? null : target.getVersion(), now, expires);
        String policySignature = security.signPolicy(canonical);
        ArtifactView artifactView = artifact == null ? null : artifactView(appId, artifact, now);
        String eventToken = security.issueEventToken(appId, requestId, artifact == null ? null : artifact.getId(), Instant.now().plusSeconds(86400).getEpochSecond());
        recordChecked(business.getId(), target, artifact, deviceId, currentVersion, pf, requestId);
        return new CheckResponse(requestId, protocolVersion, appId, business.getBizCode(), ch, pf, ar, currentVersion, updaterVersion,
                target != null, updatePolicy, reason, latest == null ? null : latest.getVersion(), policy.getMinimumSupportedVersion(), policy.getMandatoryReleaseId(),
                target == null ? null : target.getVersion(), policy.getPolicyRevision(), policy.getCheckIntervalSeconds(), policy.getOfflineGraceHours(), now, expires,
                "Ed25519", properties.getPolicyKeyId(), policySignature, target == null ? null : target.getId(), target == null ? null : target.getReleaseNotes(),
                target == null ? null : target.getPublishedAt(), now, artifactView, eventToken);
    }

    @Transactional
    public void recordEvent(long appId, String deviceId, EventRequest dto) {
        Business business = businessService.requireByAppId(appId);
        rateLimit("event", business.getId(), deviceId, 120);
        if (!EVENTS.contains(upper(dto.eventType()))) throw new BusinessException(42290, "未知升级事件类型");
        security.verifyEventToken(dto.eventToken(), appId, dto.checkRequestId(), dto.artifactId());
        ClientArtifact artifact = dto.artifactId() == null ? null : requireArtifact(dto.artifactId());
        if (artifact != null && !artifact.getBizId().equals(business.getId())) throw new BusinessException(42290, "事件构件不属于当前 appId");
        if (eventMapper.selectCount(new LambdaQueryWrapper<ClientUpdateEvent>().eq(ClientUpdateEvent::getCheckRequestId, dto.checkRequestId()).eq(ClientUpdateEvent::getEventType, upper(dto.eventType()))) > 0) return;
        ClientUpdateEvent event = new ClientUpdateEvent(); event.setBizId(business.getId());
        event.setArtifactId(dto.artifactId()); event.setReleaseId(artifact == null ? null : artifact.getReleaseId());
        event.setDeviceIdHash(security.anonymousDevice(business.getId(), deviceId)); event.setRolloutKeyVersion(properties.getRolloutKeyVersion());
        event.setFromVersion(dto.fromVersion()); event.setTargetVersion(dto.targetVersion()); event.setPlatform(upper(dto.platform()));
        event.setEventType(upper(dto.eventType())); event.setErrorCategory(dto.errorCategory()); event.setClientTime(dto.clientTime()); event.setCheckRequestId(dto.checkRequestId());
        try { eventMapper.insert(event); } catch (DuplicateKeyException ignored) { }
    }

    public Path artifactPath(long appId, long artifactId, String token) {
        Business business = businessService.requireByAppId(appId); ClientArtifact artifact = requireArtifact(artifactId);
        if (!artifact.getBizId().equals(business.getId()) || !"AVAILABLE".equals(artifact.getStatus())) throw new BusinessException(40490, "升级构件不存在");
        ClientRelease release = requireRelease(artifact.getReleaseId());
        if (!Set.of("PUBLISHED","SUSPENDED").contains(release.getStatus())) throw new BusinessException(40490, "该版本已停止下载");
        security.verifyDownloadToken(token, appId, artifactId); return storage.resolve(artifact.getStorageKey());
    }

    public ClientArtifact requireArtifactById(long id) { return requireArtifact(id); }
    public DownloadLinkView createAdminDownloadLink(long artifactId, CreateDownloadLink body, AdminPrincipal admin, HttpServletRequest request) {
        ClientArtifact artifact=requireArtifact(artifactId); businessScope.enforce(admin,artifact.getBizId());
        ClientRelease release=requireRelease(artifact.getReleaseId());
        if (!"AVAILABLE".equals(artifact.getStatus()) || !"PUBLISHED".equals(release.getStatus()))
            throw new BusinessException(40990,"只有已发布且签名完成的构件可以生成下载地址");
        long appId=businessService.requireById(artifact.getBizId()).getAppId();
        long expiresEpoch=Instant.now().plusSeconds(body.validHours()*3600L).getEpochSecond();
        String token=security.issueDownloadToken(appId,artifactId,expiresEpoch);
        String url=properties.getPublicBaseUrl().replaceAll("/$","")+"/api/v1/client/updates/download/"+artifactId+"?appId="+appId+"&token="+token;
        LocalDateTime expiresAt=LocalDateTime.ofInstant(Instant.ofEpochSecond(expiresEpoch),ZoneId.systemDefault());
        auditService.record(admin,artifact.getBizId(),"ISSUE_CLIENT_DOWNLOAD_LINK","CLIENT_ARTIFACT",artifactId+"",null,
                snapshot(Map.of("releaseId",release.getId(),"version",release.getVersion(),"expiresAt",expiresAt)),body.reason(),request);
        return new DownloadLinkView(artifactId,release.getId(),appId,release.getVersion(),artifact.getFileName(),url,expiresAt);
    }
    public IPage<ClientUpdateEvent> events(AdminPrincipal admin, Long bizId, int page, int size) {
        bizId = businessScope.enforce(admin, bizId); LambdaQueryWrapper<ClientUpdateEvent> q = new LambdaQueryWrapper<>();
        q.eq(bizId != null, ClientUpdateEvent::getBizId, bizId).orderByDesc(ClientUpdateEvent::getCreatedAt);
        return eventMapper.selectPage(new Page<>(page, size), q);
    }
    public List<Map<String,Object>> statistics(AdminPrincipal admin, Long bizId) {
        bizId = businessScope.enforce(admin, bizId); List<ClientUpdateEvent> events = eventMapper.selectList(new LambdaQueryWrapper<ClientUpdateEvent>().eq(bizId != null, ClientUpdateEvent::getBizId, bizId));
        Map<String,Long> counts = new TreeMap<>(); events.forEach(e -> counts.merge(e.getEventType(), 1L, Long::sum));
        return counts.entrySet().stream().map(e -> Map.<String,Object>of("eventType", e.getKey(), "count", e.getValue())).toList();
    }

    public static String artifactCanonical(long appId, ClientRelease r, ClientArtifact a) {
        return String.join("\n", "PDK-ARTIFACT-V1", String.valueOf(appId), r.getVersion(), a.getPlatform(), a.getArch(), a.getPackageType(), String.valueOf(a.getFileSize()), a.getSha256());
    }
    public static String policyCanonical(int protocol, long appId, String channel, String platform, String arch, Long revision,
            String updatePolicy, String minimum, Long mandatoryId, String target, LocalDateTime issued, LocalDateTime expires) {
        return String.join("\n", "PDK-POLICY-V1", String.valueOf(protocol), String.valueOf(appId), channel, platform, arch,
                String.valueOf(revision), updatePolicy, Objects.toString(minimum, ""), Objects.toString(mandatoryId, ""), Objects.toString(target, ""), issued.toString(), expires.toString());
    }

    private CheckResponse none(String requestId, int protocol, Business business, String ch, String pf, String ar, String current, String updater, String reason, ClientUpdatePolicy policy, LocalDateTime now) {
        return new CheckResponse(
                requestId, protocol, business.getAppId(), business.getBizCode(), ch, pf, ar, current, updater,
                false, "NONE", reason, null,
                policy == null ? null : policy.getMinimumSupportedVersion(),
                policy == null ? null : policy.getMandatoryReleaseId(),
                null,
                policy == null ? null : policy.getPolicyRevision(),
                policy == null ? 21600 : policy.getCheckIntervalSeconds(),
                policy == null ? 24 : policy.getOfflineGraceHours(),
                null, null, null, null, null,
                null, null, null, now, null,
                security.issueEventToken(business.getAppId(), requestId, null, Instant.now().plusSeconds(86400).getEpochSecond()));
    }
    private ArtifactView artifactView(long appId, ClientArtifact a, LocalDateTime now) {
        long expiry = Instant.now().plusSeconds(properties.getDownloadUrlTtlSeconds()).getEpochSecond();
        String token = security.issueDownloadToken(appId, a.getId(), expiry);
        String url = properties.getPublicBaseUrl().replaceAll("/$", "") + "/api/v1/client/updates/download/" + a.getId() + "?appId=" + appId + "&token=" + token;
        return new ArtifactView(a.getId(), a.getPlatform(), a.getArch(), a.getPackageType(), a.getFileName(), a.getFileSize(), a.getSha256(), a.getSignatureAlgorithm(), a.getSignatureValue(), a.getSigningKeyId(), url, now.plusSeconds(properties.getDownloadUrlTtlSeconds()));
    }
    private boolean compatible(ClientRelease r, int protocol, SemanticVersion updater) { return r.getMinimumProtocolVersion() <= protocol && SemanticVersion.parse(r.getMinimumUpdaterVersion()).compareTo(updater) <= 0; }
    private void recordChecked(long bizId, ClientRelease r, ClientArtifact a, String deviceId, String current, String platform, String requestId) {
        ClientUpdateEvent event = new ClientUpdateEvent(); event.setBizId(bizId); event.setReleaseId(r == null ? null : r.getId()); event.setArtifactId(a == null ? null : a.getId());
        event.setDeviceIdHash(security.anonymousDevice(bizId, deviceId)); event.setRolloutKeyVersion(properties.getRolloutKeyVersion()); event.setFromVersion(current); event.setTargetVersion(r == null ? null : r.getVersion()); event.setPlatform(platform); event.setEventType("CHECKED"); event.setCheckRequestId(requestId); eventMapper.insert(event);
    }
    private void validateZip(Path path, ClientRelease release, ClientArtifact artifact) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            long total = 0; JsonNode manifest = null; Set<String> packageFiles=new HashSet<>(); Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) { ZipEntry e = entries.nextElement(); String name = e.getName().replace('\\','/');
                if (name.startsWith("/") || name.contains("../") || e.getSize() > 2L * 1024 * 1024 * 1024) throw new BusinessException(42290, "ZIP 包含不安全条目");
                total += Math.max(0, e.getSize()); if (total > 4L * 1024 * 1024 * 1024) throw new BusinessException(42290, "ZIP 解压体积超过限制");
                if (!e.isDirectory()) packageFiles.add(name);
                if ("update-manifest.json".equals(name)) try (InputStream in = zip.getInputStream(e)) { manifest = objectMapper.readTree(in); }
            }
            if (manifest == null) throw new BusinessException(42290, "ZIP 根目录缺少 update-manifest.json");
            long appId = businessService.requireById(release.getBizId()).getAppId();
            long manifestAppId=manifest.path("appId").asLong(); String manifestVersion=manifest.path("version").asText();
            String manifestPlatform=upper(manifest.path("platform").asText()), manifestArch=upper(manifest.path("arch").asText());
            List<String> mismatches=new ArrayList<>();
            if (manifestAppId!=appId) mismatches.add("appId 期望="+appId+" 实际="+manifestAppId);
            if (!release.getVersion().equals(manifestVersion)) mismatches.add("version 期望="+release.getVersion()+" 实际="+manifestVersion);
            if (!artifact.getPlatform().equals(manifestPlatform)) mismatches.add("platform 期望="+artifact.getPlatform()+" 实际="+manifestPlatform);
            if (!artifact.getArch().equals(manifestArch)) mismatches.add("arch 期望="+artifact.getArch()+" 实际="+manifestArch);
            if (!mismatches.isEmpty()) throw new BusinessException(42290,"包清单不匹配："+String.join("；",mismatches));
            if (manifest.path("protocolVersion").asInt(0) != release.getMinimumProtocolVersion()
                    || !release.getMinimumUpdaterVersion().equals(manifest.path("minimumUpdaterVersion").asText()))
                throw new BusinessException(42290,"包清单协议或最低 Updater 与发布元数据不一致");
            String entryPoint=manifest.path("entryPoint").asText(); JsonNode files=manifest.path("files");
            if (entryPoint.isBlank() || entryPoint.startsWith("/") || entryPoint.contains("../") || !files.isArray())
                throw new BusinessException(42290,"包清单入口程序或文件白名单无效");
            Set<String> allowed=new HashSet<>(); files.forEach(n->allowed.add(n.asText().replace('\\','/'))); allowed.add("update-manifest.json");
            if (!allowed.contains(entryPoint) || !allowed.equals(packageFiles))
                throw new BusinessException(42290,"ZIP 实际文件必须与包清单 files 完全一致");
            String buildConfig=manifest.path("buildConfig").asText();
            if (buildConfig.isBlank()) throw new BusinessException(42290,"包清单必须声明受校验的 buildConfig");
            ZipEntry configEntry=zip.getEntry(buildConfig);
            if (configEntry==null) throw new BusinessException(42290,"包清单声明的 buildConfig 不存在");
            JsonNode config;
            try (InputStream in=zip.getInputStream(configEntry)) { config=objectMapper.readTree(in); }
            if (config.path("appId").asLong()!=appId || !release.getVersion().equals(config.path("version").asText())
                    || !entryPoint.equals(config.path("entryPoint").asText()))
                throw new BusinessException(42290,"内嵌构建配置的 appId/version/entryPoint 与发布清单不一致");
        } catch (BusinessException e) { throw e; } catch (Exception e) { throw new BusinessException(42290, "无法识别升级 ZIP: " + e.getMessage()); }
    }
    private void requireAvailableArtifact(long releaseId) { if (artifactMapper.selectCount(new LambdaQueryWrapper<ClientArtifact>().eq(ClientArtifact::getReleaseId, releaseId).eq(ClientArtifact::getStatus, "AVAILABLE")) == 0) throw new BusinessException(42290, "发布缺少已签名可用构件"); }
    private void ensureNotMandatoryTarget(long releaseId) { if (policyMapper.selectCount(new LambdaQueryWrapper<ClientUpdatePolicy>().eq(ClientUpdatePolicy::getMandatoryReleaseId, releaseId).eq(ClientUpdatePolicy::getUpdateEnabled, 1)) > 0) throw new BusinessException(40990, "该版本仍是强制更新目标，请先原子切换策略"); }
    private ClientRelease requireRelease(long id) { ClientRelease r = releaseMapper.selectById(id); if (r == null) throw new BusinessException(40490, "升级发布不存在"); return r; }
    private ClientArtifact requireArtifact(long id) { ClientArtifact a = artifactMapper.selectById(id); if (a == null) throw new BusinessException(40490, "升级构件不存在"); return a; }
    private ClientArtifact requireArtifact(long releaseId, String pf, String ar) { ClientArtifact a = findArtifact(releaseId,pf,ar); if (a == null) throw new BusinessException(42290, "强制目标缺少匹配平台构件"); return a; }
    private ClientArtifact findArtifact(long releaseId, String pf, String ar) {
        ClientArtifact artifact=artifactMapper.selectOne(new LambdaQueryWrapper<ClientArtifact>().eq(ClientArtifact::getReleaseId,releaseId).eq(ClientArtifact::getPlatform,pf).eq(ClientArtifact::getArch,ar).eq(ClientArtifact::getStatus,"AVAILABLE").last("LIMIT 1"));
        if (artifact==null) return null;
        try { storage.resolve(artifact.getStorageKey()); return artifact; }
        catch (BusinessException e) { return null; }
    }
    private ClientUpdatePolicy findPolicy(long bizId, String ch, String pf, String ar) { return policyMapper.selectOne(new LambdaQueryWrapper<ClientUpdatePolicy>().eq(ClientUpdatePolicy::getBizId,bizId).eq(ClientUpdatePolicy::getChannel,ch).eq(ClientUpdatePolicy::getPlatform,pf).eq(ClientUpdatePolicy::getArch,ar).last("LIMIT 1")); }
    private void validateChannel(String v) { if (!CHANNELS.contains(upper(v))) throw new BusinessException(40090, "channel 仅支持 STABLE/BETA"); }
    private void validateTarget(String platform,String arch,String type) { if (!PLATFORMS.contains(upper(platform)) || !ARCHES.contains(upper(arch)) || !"ZIP".equals(upper(type))) throw new BusinessException(40090, "首期仅支持 WINDOWS/X64/ZIP"); }
    private String upper(String v) { return v == null ? "" : v.trim().toUpperCase(Locale.ROOT); }
    private String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private String snapshot(Object v) { try { return objectMapper.writeValueAsString(v); } catch(Exception e) { return String.valueOf(v); } }
    private void rateLimit(String scope,long bizId,String deviceId,long limit) {
        String hash=security.anonymousDevice(bizId,deviceId); if (hash==null) return;
        String key="pdk:update:rate:"+scope+":"+bizId+":"+hash+":"+(System.currentTimeMillis()/60000);
        try { Long count=redisTemplate.opsForValue().increment(key); if (count!=null && count==1) redisTemplate.expire(key,2,java.util.concurrent.TimeUnit.MINUTES); if (count!=null && count>limit) throw new BusinessException(42990,"升级接口请求过于频繁"); }
        catch (BusinessException e) { throw e; } catch (Exception ignored) { /* Redis 故障不锁死强制升级链路 */ }
    }
    private boolean repeated(long bizId,String requestId,String operation,String targetType,String targetId) {
        ClientUpdateOperation old=operationMapper.selectOne(new LambdaQueryWrapper<ClientUpdateOperation>().eq(ClientUpdateOperation::getBizId,bizId).eq(ClientUpdateOperation::getRequestId,requestId).last("LIMIT 1"));
        if (old==null) return false;
        if (!operation.equals(old.getOperationType()) || !targetType.equals(old.getTargetType()) || !targetId.equals(old.getTargetId()))
            throw new BusinessException(40990,"requestId 已被其他升级操作使用");
        return true;
    }
    private void recordOperation(long bizId,String requestId,String operation,String targetType,String targetId) {
        ClientUpdateOperation row=new ClientUpdateOperation();row.setBizId(bizId);row.setRequestId(requestId);row.setOperationType(operation);row.setTargetType(targetType);row.setTargetId(targetId);operationMapper.insert(row);
    }
    private void stateError() { throw new BusinessException(40990, "当前发布状态不允许该操作"); }
}
