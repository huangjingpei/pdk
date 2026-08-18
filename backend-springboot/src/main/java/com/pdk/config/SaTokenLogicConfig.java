package com.pdk.config;

import cn.dev33.satoken.stp.StpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SaTokenLogicConfig {
    @Bean("adminStpLogic")
    @Primary
    public StpLogic adminStpLogic() {
        return new StpLogic("admin");
    }

    @Bean("clientStpLogic")
    public StpLogic clientStpLogic() {
        return new StpLogic("client");
    }
}
