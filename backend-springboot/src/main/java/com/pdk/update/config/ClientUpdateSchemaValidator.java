package com.pdk.update.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.update.domain.*;
import com.pdk.update.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 升级数据不可用删库重建修复；缺表/缺列时让应用启动明确失败。 */
@Component
@RequiredArgsConstructor
public class ClientUpdateSchemaValidator implements ApplicationRunner {
    private final ClientUpdatePolicyMapper policyMapper;
    private final ClientReleaseMapper releaseMapper;
    private final ClientArtifactMapper artifactMapper;
    private final ClientUpdateEventMapper eventMapper;
    private final ClientUpdateOperationMapper operationMapper;

    @Override public void run(ApplicationArguments args) {
        policyMapper.selectList(new LambdaQueryWrapper<ClientUpdatePolicy>().last("LIMIT 1"));
        releaseMapper.selectList(new LambdaQueryWrapper<ClientRelease>().last("LIMIT 1"));
        artifactMapper.selectList(new LambdaQueryWrapper<ClientArtifact>().last("LIMIT 1"));
        eventMapper.selectList(new LambdaQueryWrapper<ClientUpdateEvent>().last("LIMIT 1"));
        operationMapper.selectList(new LambdaQueryWrapper<ClientUpdateOperation>().last("LIMIT 1"));
    }
}
