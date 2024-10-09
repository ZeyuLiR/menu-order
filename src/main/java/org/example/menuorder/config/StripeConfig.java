package org.example.menuorder.config;

import com.stripe.Stripe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Configuration
public class StripeConfig {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @PostConstruct
    public void init() {
        // 通过读取配置文件中的 secret-key，初始化 Stripe API 密钥
        Stripe.apiKey = secretKey;
        System.out.println("Stripe API key initialized: " + secretKey); // 用于调试
    }
}

