/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.eureka;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

/**
 * An instance information.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonRootName("instance")
public final class InstanceInfo {

    private static final Logger logger = LoggerFactory.getLogger(InstanceInfo.class);

    private static final AttributeKey<InstanceInfo> INSTANCE_INFO = AttributeKey.valueOf(
            InstanceInfo.class, "INSTANCE_INFO");

    /**
     * Returns the {@link InstanceInfo} associated with the given {@link Endpoint}.
     *
     * @param endpoint The {@link Endpoint} whose {@link InstanceInfo} is to be retrieved.
     * @return The {@link InstanceInfo} associated with the specified {@link Endpoint}.
     */
    @Nullable
    public static InstanceInfo instanceInfo(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        return endpoint.attr(INSTANCE_INFO);
    }

    /**
     * Sets the Eureka {@link InstanceInfo} parameter to the {@link Endpoint} as an attribute.
     *
     * @param endpoint The {@link Endpoint} to which the {@link InstanceInfo} will be set as an attribute.
     * @return The same {@link Endpoint} passed as a parameter.
     */
    public static Endpoint setInstanceInfo(Endpoint endpoint, InstanceInfo instanceInfo) {
        requireNonNull(endpoint, "endpoint");
        requireNonNull(instanceInfo, "instanceInfo");
        return endpoint.withAttr(INSTANCE_INFO, instanceInfo);
    }

    @Nullable
    private final String instanceId;

    @Nullable
    private final String hostName;
    @Nullable
    private final String appName;
    @Nullable
    private final String appGroupName;
    @Nullable
    private final String ipAddr;
    @Nullable
    private final String vipAddress;
    @Nullable
    private final String secureVipAddress;

    private final PortWrapper port;
    private final PortWrapper securePort;
    private final InstanceStatus status;

    @Nullable
    private final String homePageUrlPath;
    @Nullable
    private final String homePageUrl;
    @Nullable
    private final String statusPageUrlPath;
    @Nullable
    private final String statusPageUrl;
    @Nullable
    private final String healthCheckUrlPath;
    @Nullable
    private final String healthCheckUrl;
    @Nullable
    private final String secureHealthCheckUrl;
    private final DataCenterInfo dataCenterInfo;
    private final LeaseInfo leaseInfo;
    private final Map<String, String> metadata;

    private final long lastUpdatedTimestamp;
    private final long lastDirtyTimestamp;

    /**
     * Creates a new instance.
     */
    public InstanceInfo(@Nullable @JsonProperty("instanceId") String instanceId,
                        @Nullable @JsonProperty("app") String appName,
                        @Nullable @JsonProperty("appGroupName") String appGroupName,
                        @Nullable @JsonProperty("hostName") String hostName,
                        @Nullable @JsonProperty("ipAddr") String ipAddr,
                        @Nullable @JsonProperty("vipAddress") String vipAddress,
                        @Nullable @JsonProperty("secureVipAddress") String secureVipAddress,
                        @JsonProperty("port") PortWrapper port,
                        @JsonProperty("securePort") PortWrapper securePort,
                        @JsonProperty("status") InstanceStatus status,
                        @Nullable @JsonProperty("homePageUrl") String homePageUrl,
                        @Nullable @JsonProperty("statusPageUrl") String statusPageUrl,
                        @Nullable @JsonProperty("healthCheckUrl") String healthCheckUrl,
                        @Nullable @JsonProperty("secureHealthCheckUrl") String secureHealthCheckUrl,
                        @JsonProperty("dataCenterInfo") DataCenterInfo dataCenterInfo,
                        @JsonProperty("leaseInfo") LeaseInfo leaseInfo,
                        @Nullable @JsonProperty("metadata") Map<String, String> metadata) {
        this(instanceId, appName, appGroupName, hostName, ipAddr, vipAddress, secureVipAddress, port,
             securePort, status, null, homePageUrl, null, statusPageUrl, null, healthCheckUrl,
             secureHealthCheckUrl, dataCenterInfo, leaseInfo, metadata);
    }

    /**
     * Creates a new instance which may have {@link #healthCheckUrlPath}.
     */
    public InstanceInfo(@Nullable String instanceId,
                        @Nullable String appName,
                        @Nullable String appGroupName,
                        @Nullable String hostName,
                        @Nullable String ipAddr,
                        @Nullable String vipAddress,
                        @Nullable String secureVipAddress,
                        PortWrapper port,
                        PortWrapper securePort,
                        InstanceStatus status,
                        @Nullable String homePageUrlPath, // Not in JSON
                        @Nullable String homePageUrl,
                        @Nullable String statusPageUrlPath, // Not in JSON
                        @Nullable String statusPageUrl,
                        @Nullable String healthCheckUrlPath, // Not in JSON
                        @Nullable String healthCheckUrl,
                        @Nullable String secureHealthCheckUrl,
                        DataCenterInfo dataCenterInfo,
                        LeaseInfo leaseInfo,
                        @Nullable Map<String, String> metadata) {
        this.instanceId = instanceId;
        this.hostName = hostName;
        this.appName = appName;
        this.appGroupName = appGroupName;
        this.ipAddr = ipAddr;
        this.vipAddress = vipAddress;
        this.secureVipAddress = secureVipAddress;
        this.port = requireNonNull(port, "port");
        this.securePort = requireNonNull(securePort, "securePort");
        this.status = requireNonNull(status, "status");
        this.homePageUrlPath = homePageUrlPath;
        this.homePageUrl = homePageUrl;
        this.statusPageUrlPath = statusPageUrlPath;
        this.statusPageUrl = statusPageUrl;
        this.healthCheckUrlPath = healthCheckUrlPath;
        this.healthCheckUrl = healthCheckUrl;
        this.secureHealthCheckUrl = secureHealthCheckUrl;
        this.dataCenterInfo = dataCenterInfo;
        this.leaseInfo = requireNonNull(leaseInfo, "leaseInfo");
        if (metadata != null) {
            this.metadata = metadata;
        } else {
            this.metadata = ImmutableMap.of();
        }

        lastUpdatedTimestamp = System.currentTimeMillis();
        lastDirtyTimestamp = lastUpdatedTimestamp;
    }

    /**
     * Returns the ID of this instance.
     */
    @Nullable
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Return the name of the application which this instance belongs to.
     */
    @Nullable
    @JsonProperty("app")
    public String getAppName() {
        return appName;
    }

    /**
     * Return the group name of the application which this instance belongs to.
     */
    @Nullable
    public String getAppGroupName() {
        return appGroupName;
    }

    /**
     * Return the hostname of this instance.
     */
    @Nullable
    public String getHostName() {
        return hostName;
    }

    /**
     * Returns the IP address of this instance.
     */
    @Nullable
    public String getIpAddr() {
        return ipAddr;
    }

    /**
     * Returns the VIP address of this instance.
     */
    @Nullable
    public String getVipAddress() {
        return vipAddress;
    }

    /**
     * Returns the secure VIP address of this instance.
     */
    @Nullable
    public String getSecureVipAddress() {
        return secureVipAddress;
    }

    /**
     * Returns the {@link PortWrapper} of this instance.
     */
    public PortWrapper getPort() {
        return port;
    }

    /**
     * Returns the secure {@link PortWrapper} of this instance.
     */
    public PortWrapper getSecurePort() {
        return securePort;
    }

    /**
     * Returns the {@link InstanceStatus} of this instance.
     */
    public InstanceStatus getStatus() {
        return status;
    }

    /**
     * Returns the home page URL path of this instance.
     *
     * <p>When set, {@link #getHomePageUrl()} will be built with {@link #getHostName()} and {@link #getPort()}.
     */
    @Nullable
    @JsonIgnore
    public String getHomePageUrlPath() {
        return homePageUrlPath;
    }

    /**
     * Returns the home page URL of this instance.
     */
    @Nullable
    public String getHomePageUrl() {
        return homePageUrl;
    }

    /**
     * Returns the status page URL path of this instance.
     *
     * <p>When set, {@link #getStatusPageUrl()} will be built with {@link #getHostName()} and
     * {@link #getPort()}.
     */
    @Nullable
    @JsonIgnore
    public String getStatusPageUrlPath() {
        return statusPageUrlPath;
    }

    /**
     * Returns the status page URL of this instance.
     */
    @Nullable
    public String getStatusPageUrl() {
        return statusPageUrl;
    }

    /**
     * Returns the health check path of this instance.
     *
     * <p>When set, {@link #getHealthCheckUrl()} will be built from {@link #getHostName()} and
     * {@link #getPort()} or {@link #getSecurePort()} for {@link #getSecureHealthCheckUrl()}.
     */
    @Nullable
    @JsonIgnore
    public String getHealthCheckUrlPath() {
        return healthCheckUrlPath;
    }

    /**
     * Returns the health check URL of this instance.
     */
    @Nullable
    public String getHealthCheckUrl() {
        return healthCheckUrl;
    }

    /**
     * Returns the secure health check URL of this instance.
     */
    @Nullable
    public String getSecureHealthCheckUrl() {
        return secureHealthCheckUrl;
    }

    /**
     * Returns the data center information which this instance belongs to.
     */
    public DataCenterInfo getDataCenterInfo() {
        return dataCenterInfo;
    }

    /**
     * Returns the lease information of this instance.
     */
    public LeaseInfo getLeaseInfo() {
        return leaseInfo;
    }

    /**
     * Returns the metadata of this instance.
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Returns the last updated timestamp of this instance.
     */
    public long getLastUpdatedTimestamp() {
        return lastUpdatedTimestamp;
    }

    /**
     * Returns the last dirty timestamp of this instance.
     */
    public long getLastDirtyTimestamp() {
        return lastDirtyTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InstanceInfo)) {
            return false;
        }

        final InstanceInfo that = (InstanceInfo) o;
        return Objects.equal(instanceId, that.instanceId) &&
               Objects.equal(hostName, that.hostName) &&
               Objects.equal(appName, that.appName) &&
               Objects.equal(appGroupName, that.appGroupName) &&
               Objects.equal(ipAddr, that.ipAddr) &&
               Objects.equal(vipAddress, that.vipAddress) &&
               Objects.equal(secureVipAddress, that.secureVipAddress) &&
               Objects.equal(port, that.port) &&
               Objects.equal(securePort, that.securePort) &&
               status == that.status &&
               Objects.equal(homePageUrlPath, that.homePageUrlPath) &&
               Objects.equal(homePageUrl, that.homePageUrl) &&
               Objects.equal(statusPageUrlPath, that.statusPageUrlPath) &&
               Objects.equal(statusPageUrl, that.statusPageUrl) &&
               Objects.equal(healthCheckUrlPath, that.healthCheckUrlPath) &&
               Objects.equal(healthCheckUrl, that.healthCheckUrl) &&
               Objects.equal(secureHealthCheckUrl, that.secureHealthCheckUrl) &&
               Objects.equal(dataCenterInfo, that.dataCenterInfo) &&
               Objects.equal(leaseInfo, that.leaseInfo) &&
               Objects.equal(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(instanceId, hostName, appName, appGroupName, ipAddr, vipAddress,
                                secureVipAddress, port, securePort, status,
                                homePageUrlPath, homePageUrl, statusPageUrlPath, statusPageUrl,
                                healthCheckUrlPath, healthCheckUrl,
                                secureHealthCheckUrl, dataCenterInfo, leaseInfo, metadata);
    }

    @Override
    public String toString() {
        return toStringHelper(this).omitNullValues()
                                   .add("instanceId", instanceId)
                                   .add("hostName", hostName)
                                   .add("appName", appName)
                                   .add("appGroupName", appGroupName)
                                   .add("ipAddr", ipAddr)
                                   .add("vipAddress", vipAddress)
                                   .add("secureVipAddress", secureVipAddress)
                                   .add("port", port)
                                   .add("securePort", securePort)
                                   .add("status", status)
                                   .add("homePageUrlPath", homePageUrlPath)
                                   .add("homePageUrl", homePageUrl)
                                   .add("statusPageUrlPath", statusPageUrlPath)
                                   .add("statusPageUrl", statusPageUrl)
                                   .add("healthCheckUrlPath", healthCheckUrlPath)
                                   .add("healthCheckUrl", healthCheckUrl)
                                   .add("secureHealthCheckUrl", secureHealthCheckUrl)
                                   .add("dataCenterInfo", dataCenterInfo)
                                   .add("leaseInfo", leaseInfo)
                                   .add("metadata", metadata)
                                   .add("lastUpdatedTimestamp", lastUpdatedTimestamp)
                                   .add("lastDirtyTimestamp", lastDirtyTimestamp)
                                   .toString();
    }

    /**
     * The status of an {@link InstanceInfo}.
     */
    public enum InstanceStatus {

        UP,
        DOWN,
        STARTING,
        OUT_OF_SERVICE,
        UNKNOWN;

        /**
         * Returns the {@link Enum} value corresponding to the specified {@code str}.
         * {@link #UNKNOWN} is returned if none of {@link Enum}s are matched.
         */
        public static InstanceStatus toEnum(String str) {
            requireNonNull(str, "str");
            try {
                return valueOf(str);
            } catch (IllegalArgumentException e) {
                logger.warn("unknown enum value: {} (expected: {}), {} is set by default. ",
                            str, values(), UNKNOWN);
            }
            return UNKNOWN;
        }
    }

    /**
     * The port information.
     */
    public static class PortWrapper {
        private final boolean enabled;
        private final int port;

        /**
         * Constructs a new PortWrapper instance.
         *
         * @param enabled Whether the port is enabled or not.
         * @param port The port number.
         */
        public PortWrapper(@JsonProperty("@enabled") boolean enabled, @JsonProperty("$") int port) {
            this.enabled = enabled;
            this.port = port;
        }

        /**
         * Returns whether the port is enabled or not.
         *
         * @return {@code true} if the port is enabled, {@code false} otherwise.
         */
        @JsonProperty("@enabled")
        @JsonSerialize(using = ToStringSerializer.class)
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Returns the port number.
         *
         * @return The port number.
         */
        @JsonProperty("$")
        public int getPort() {
            return port;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PortWrapper)) {
                return false;
            }
            final PortWrapper that = (PortWrapper) obj;
            return enabled == that.enabled && port == that.port;
        }

        @Override
        public int hashCode() {
            return port * 31 + Boolean.hashCode(enabled);
        }

        @Override
        public String toString() {
            return toStringHelper(this).add("enabled", enabled)
                                       .add("port", port)
                                       .toString();
        }
    }
}
