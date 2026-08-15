package com.sajtech.compromisedpassword.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "hooshix.compromised-password")
@Validated
public final class CompromisedPasswordProperties {
    @Min(1)
    @Max(65_535)
    private int grpcPort = 9090;

    @Min(1)
    @Max(512)
    private int maxConcurrentLookups = 32;

    @Valid @NotNull private Dataset dataset = new Dataset();

    public int getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(int grpcPort) {
        this.grpcPort = grpcPort;
    }

    public int getMaxConcurrentLookups() {
        return maxConcurrentLookups;
    }

    public void setMaxConcurrentLookups(int maxConcurrentLookups) {
        this.maxConcurrentLookups = maxConcurrentLookups;
    }

    public Dataset getDataset() {
        return dataset;
    }

    public void setDataset(Dataset dataset) {
        this.dataset = dataset;
    }

    public static final class Dataset {
        @NotNull private Path path;

        @NotBlank
        @Pattern(regexp = "[0-9a-f]{64}")
        private String expectedSha256;

        @NotNull private Instant acquiredAt;

        @Min(1)
        private int formatVersion = 1;

        @Min(1)
        private int maxPrefixCardinality;

        public Path getPath() {
            return path;
        }

        public void setPath(Path path) {
            this.path = path;
        }

        public String getExpectedSha256() {
            return expectedSha256;
        }

        public void setExpectedSha256(String expectedSha256) {
            this.expectedSha256 = expectedSha256;
        }

        public Instant getAcquiredAt() {
            return acquiredAt;
        }

        public void setAcquiredAt(Instant acquiredAt) {
            this.acquiredAt = acquiredAt;
        }

        public int getFormatVersion() {
            return formatVersion;
        }

        public void setFormatVersion(int formatVersion) {
            this.formatVersion = formatVersion;
        }

        public int getMaxPrefixCardinality() {
            return maxPrefixCardinality;
        }

        public void setMaxPrefixCardinality(int maxPrefixCardinality) {
            this.maxPrefixCardinality = maxPrefixCardinality;
        }
    }
}
