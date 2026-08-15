package com.sajtech.compromisedpassword.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "hooshix.compromised-password")
@Validated
public record CompromisedPasswordProperties(
    @Min(1) @Max(65_535) int grpcPort,
    @Min(1) @Max(512) int maxConcurrentLookups,
    @Valid @NotNull Dataset dataset) {

  public record Dataset(
      @NotNull Path path,
      @NotNull Path manifestPath,
      @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,64}") String requiredSourceKind,
      @Min(1) int maxPrefixCardinality,
      @Min(1) long maxSerializedResponseBytes) {}
}
