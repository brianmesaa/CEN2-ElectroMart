package com.electromart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration of the backend.
 *
 * <p>{@code electromart.data-file} is bound to the {@code ELECTROMART_DATA_FILE} environment
 * variable in {@code application.properties} and defaults to {@code data/electromart-state.json}.</p>
 */
@Component
@ConfigurationProperties(prefix = "electromart")
public class ElectroMartProperties {

    /** Location of the single JSON state file (inventory + orders + idempotency records). */
    private String dataFile = "data/electromart-state.json";

    /** User that is allowed to see every order (kept from the original prototype behaviour). */
    private String adminUser = "bmesa@gmail.com";

    public String getDataFile() {
        return dataFile;
    }

    public void setDataFile(String dataFile) {
        this.dataFile = dataFile;
    }

    public String getAdminUser() {
        return adminUser;
    }

    public void setAdminUser(String adminUser) {
        this.adminUser = adminUser;
    }
}
