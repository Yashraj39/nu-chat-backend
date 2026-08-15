package com.pulsechat.config;
import org.springframework.context.annotation.*;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {
 private final StompAuthInterceptor interceptor;
 public WebSocketSecurityConfig(StompAuthInterceptor i){interceptor=i;}
 @Override public void configureClientInboundChannel(ChannelRegistration r){r.interceptors(interceptor);}
}
