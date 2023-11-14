package com.github.safetytrick.sqlexplorer.performanceschema.fileloader;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("performance-schema-file-loader")
public class PerformanceSchemaFileLoaderConfig {

    private boolean enabled = false;

    private String directory;

    /**
     * a regex with patterns extracting a source identifier and the file date
     */
    private String pattern;

    private String dateFormat;

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormat = dateFormat;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
