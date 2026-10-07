package com.aicontent.platform.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class LockConfig {

    @Bean
    @ConditionalOnProperty(name = "app.lock.redis-enabled", havingValue = "true")
    DistributedLock redisDistributedLock(StringRedisTemplate redis) {
        return new RedisDistributedLock(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "app.lock.redis-enabled", havingValue = "false", matchIfMissing = true)
    DistributedLock inJvmDistributedLock() {
        return new InJvmDistributedLock();
    }
}
