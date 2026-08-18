package com.pdk.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局插件配置。
 *
 * <p>之前工程里<b>缺失分页拦截器</b>，导致所有 {@code selectPage(...)} 调用（典型如
 * {@code AdminTokenController#list} 的 Token 池分页查询）不会拼接 LIMIT/OFFSET，也不会执行 COUNT：
 * <ul>
 *   <li>每次请求都把整张表（导入 5000 行后即为 5000 行）一次性全部返回，前端拿到 ~1.5MB 超大响应；</li>
 *   <li>返回的 {@code total} 恒为 0，{@code pages} 为 0，分页器完全失效；</li>
 *   <li>前端一次性渲染 5000 行 DOM（每行含 el-progress / el-tag 等），页面卡死/白屏，表现为“没有数据”。</li>
 * </ul>
 * 注册 {@link PaginationInnerInterceptor} 后，{@code selectPage} 才会真正分页并返回正确的 {@code total}。
 */
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
