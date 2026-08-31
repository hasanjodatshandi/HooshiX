package com.sajtech.identity.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!migration")
@EnableConfigurationProperties(IdentityProperties.class)
@Import({
  IdentityCoreRuntimeConfiguration.class,
  IdentityAccountRuntimeConfiguration.class,
  IdentityTenantRuntimeConfiguration.class,
  IdentityTransportRuntimeConfiguration.class
})
public class RuntimeConfiguration {}
