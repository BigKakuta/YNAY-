package io.ynay.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
@PropertySource("classpath:ynay-agent.properties")
class AgentConfiguration {
}
