/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.api;

/**
 * Interface defining the contract for interacting with a Panel's API (Pterodactyl or Pelican).
 * Provides methods for controlling server power state and querying status.
 */
public interface PanelAPIClient {
    /**
     * Sends a power signal (start, stop, restart, kill) to the specified server.
     * Implementations should handle API requests and rate limiting updates.
     *
     * @param serverId The unique identifier of the server on the panel.
     * @param signal   The power action to perform (START, STOP, RESTART, KILL).
     */
    void powerServer(String serverId, PowerSignal signal);
    /**
     * Checks if a server is currently online and running according to the configured method.
     * This might involve pinging the server via Velocity or querying the Panel API.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The unique identifier of the server on the panel.
     * @return {@code true} if the server is considered online, {@code false} otherwise.
     */
    boolean isServerOnline(String serverName, String serverId);
    /**
     * Checks if a server registered in Velocity currently has any players connected.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server has no players connected or is not found in Velocity, {@code false} otherwise.
     */
    boolean isServerEmpty(String serverName);
    /**
     * Shuts down any resources used by the API client, such as thread pools.
     * Should be called when the plugin is disabled.
     */
    void shutdown();
}
