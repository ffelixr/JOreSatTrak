package jsattrak.awareness.config;

/**
 * Configuration for a single external launch or TLE data source endpoint.
 */
public class DataSourceConfig {

    /**
     * Category of awareness data supplied by a feed.
     */
    public enum FeedCategory {
        LAUNCH,
        TLE
    }

    private String id = "";
    private FeedCategory feedCategory;
    private String displayName = "";
    private String endpointUrl = "";
    private boolean enabled;
    private int priority;
    private AuthMode authMode = AuthMode.NONE;
    private String authKeyName = "";
    private String authSecret = "";
    private String authUsername = "";
    private int refreshIntervalMinutes = 60;
    private boolean syncFixedPadsToGroundStations;

    public DataSourceConfig() {
    }

    public DataSourceConfig(String id,
                            FeedCategory feedCategory,
                            String displayName,
                            String endpointUrl,
                            boolean enabled,
                            int priority,
                            AuthMode authMode,
                            String authKeyName,
                            String authSecret,
                            String authUsername,
                            int refreshIntervalMinutes,
                            boolean syncFixedPadsToGroundStations) {
        this.id = id;
        this.feedCategory = feedCategory;
        this.displayName = displayName;
        this.endpointUrl = endpointUrl;
        this.enabled = enabled;
        this.priority = priority;
        this.authMode = authMode;
        this.authKeyName = authKeyName;
        this.authSecret = authSecret;
        this.authUsername = authUsername;
        this.refreshIntervalMinutes = refreshIntervalMinutes;
        this.syncFixedPadsToGroundStations = syncFixedPadsToGroundStations;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public FeedCategory getFeedCategory() {
        return feedCategory;
    }

    public void setFeedCategory(FeedCategory feedCategory) {
        this.feedCategory = feedCategory;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public String getAuthKeyName() {
        return authKeyName;
    }

    public void setAuthKeyName(String authKeyName) {
        this.authKeyName = authKeyName;
    }

    public String getAuthSecret() {
        return authSecret;
    }

    public void setAuthSecret(String authSecret) {
        this.authSecret = authSecret;
    }

    public String getAuthUsername() {
        return authUsername;
    }

    public void setAuthUsername(String authUsername) {
        this.authUsername = authUsername;
    }

    public int getRefreshIntervalMinutes() {
        return refreshIntervalMinutes;
    }

    public void setRefreshIntervalMinutes(int refreshIntervalMinutes) {
        this.refreshIntervalMinutes = refreshIntervalMinutes;
    }

    public boolean isSyncFixedPadsToGroundStations() {
        return syncFixedPadsToGroundStations;
    }

    public void setSyncFixedPadsToGroundStations(boolean syncFixedPadsToGroundStations) {
        this.syncFixedPadsToGroundStations = syncFixedPadsToGroundStations;
    }
}
