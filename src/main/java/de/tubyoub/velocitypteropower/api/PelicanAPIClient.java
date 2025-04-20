package de.tubyoub.velocitypteropower.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tubyoub.velocitypteropower.config.ConfigurationManager;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;


/**
 * Implementation of {@link PanelAPIClient} for interacting with the Pelican Panel API.
 * NOTE: The current implementation is the same as the PterodactylAPIClient.
 * if Pelican changes the workings of their API, this class will be updated.
 */
public class PelicanAPIClient implements PanelAPIClient {
    public final Logger logger;
    public final ConfigurationManager configurationManager;
    public final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;
    public final RateLimitTracker rateLimitTracker;

    private final HttpClient httpClient;
    private final ExecutorService executorService;

    /**
     * Constructs a PelicanAPIClient.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public PelicanAPIClient(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.proxyServer = plugin.getProxyServer();
        this.rateLimitTracker = plugin.getRateLimitTracker();

        this.executorService = Executors.newFixedThreadPool(10); // Limit to 10 threads
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();
    }

    /**
     * Sends a power signal to a Pelican server.
     *
     * @param serverId The Pelican server identifier (UUID).
     * @param signal   The power action (START, STOP, RESTART, KILL).
     */
    @Override
    public void powerServer(String serverId, PowerSignal signal) {
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn(
                "Rate limit reached. Cannot send power signal {} to server {}.",
                signal,
                serverId
            );
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/power"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                .POST(HttpRequest.BodyPublishers.ofString("{\"signal\": \"" + signal.getApiSignal() + "\"}"))
                .build();

            rateLimitTracker.updateRateLimitInfo(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            logger.error("Error powering server.", e);
        }
    }

    /**
     * Checks if a server is online using the configured method (Velocity Ping or Pterodactyl API).
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The Pterodactyl server identifier (UUID).
     * @return {@code true} if the server is considered online, {@code false} otherwise.
     */
    @Override
    public boolean isServerOnline(String serverName, String serverId) {
        ConfigurationManager.ServerCheckMethod method =
            configurationManager.getServerCheckMethod();

        switch (method) {
            case VELOCITY_PING:
                return checkOnlineViaVelocityPing(serverName);
            case PANEL_API:
                return checkOnlineViaPanelApi(serverName, serverId);
            default:
                // Should not happen with enum, but just in case
                logger.error(
                    "Unknown ServerCheckMethod: {}. Defaulting to false.",
                    method
                );
                return false;
        }
    }

    /**
     * Checks server status using Velocity's built-in ping mechanism.
     *
     * @param serverName The name of the server registered in Velocity.
     * @return True if the ping succeeds within the configured timeout, false otherwise.
     */
    private boolean checkOnlineViaVelocityPing(String serverName) {
        Optional<RegisteredServer> serverOptional =
            proxyServer.getServer(serverName);
        if (serverOptional.isEmpty()) {
            logger.debug(
                "Cannot perform PING check: Server '{}' not registered in Velocity.",
                serverName
            );
            return false; // Server not known to Velocity
        }

        RegisteredServer server = serverOptional.get();
        try {
            CompletableFuture<ServerPing> pingFuture = server.ping();
            ServerPing pingResult = pingFuture.get(
                configurationManager.getPingTimeout(),
                TimeUnit.MILLISECONDS
            );
            boolean online = pingResult != null;
            logger.debug(
                "Ping check for {}: {}",
                serverName,
                online ? "Success" : "Failed (No result/Timeout)"
            );
            return online;
        } catch (TimeoutException e) {
            logger.debug(
                "Ping check for {} timed out after {}ms.",
                serverName,
                configurationManager.getPingTimeout()
            );
            return false;
        } catch (ExecutionException e) {
            // Log the underlying cause if possible
            Throwable cause = e.getCause();
            logger.debug(
                "Ping check for {} failed: {}",
                serverName,
                cause != null ? cause.getMessage() : e.getMessage()
            );
            return false;
        } catch (InterruptedException e) {
            logger.warn("Ping check for {} interrupted.", serverName);
            Thread.currentThread().interrupt(); // Re-interrupt the thread
            return false;
        } catch (Exception e) { // Catch unexpected errors during ping
            logger.warn(
                "Unexpected error pinging server {}: {}",
                serverName,
                e.getMessage(),
                e
            );
            return false;
        }
    }

    /**
     * Checks server status by querying the Pelican API /resources endpoint.
     * Includes retry logic for specific HTTP/2 GOAWAY errors.
     *
     * @param serverName The name of the server (for logging).
     * @param serverId   The Pelican server identifier (UUID).
     * @return True if the API reports the server state as "running", false otherwise.
     */
    private boolean checkOnlineViaPanelApi(String serverName, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            logger.error(
                "Cannot perform API check: Server ID is missing for server '{}'.",
                serverName
            );
            return false;
        }
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn(
                "Rate limit reached. Cannot perform API status check for server {} ({}).",
                serverName,
                serverId
            );
            return false; // Cannot check status due to rate limit
        }

        int maxRetries = 3; // Max retries specifically for GOAWAY errors
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            configurationManager.getPterodactylUrl() +
                            "api/client/servers/" +
                            serverId +
                            "/resources"
                        )
                    )
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header(
                        "Authorization",
                        "Bearer " + configurationManager.getPterodactylApiKey()
                    )
                    .GET()
                    .timeout(Duration.ofSeconds(10)) // Timeout for the API request
                    .build();

                // Use synchronous send here as the result is needed immediately
                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(response); // Update rate limit info

                String responseBody = response.body();
                if (response.statusCode() == 200 && responseBody != null) {
                    // Check if the state attribute indicates running
                    // A more robust solution would parse the JSON properly
                    boolean running =
                        responseBody.contains("\"current_state\":\"running\"");
                    logger.debug(
                        "API check for {} (ID {}): Status {}, State Running: {}",
                        serverName,
                        serverId,
                        response.statusCode(),
                        running
                    );
                    return running;
                } else {
                    logger.warn(
                        "API check for {} (ID {}) failed with status code: {} Body: {}",
                        serverName,
                        serverId,
                        response.statusCode(),
                        responseBody != null ? responseBody.substring(0, Math.min(responseBody.length(), 100)) + "..." : "null" // Log truncated body
                    );
                    return false; // Non-200 or null body means not running or error
                }
            } catch (IOException e) {
                // Check specifically for GOAWAY which might indicate HTTP/2 issues
                if (e.getMessage() != null && e.getMessage().contains("GOAWAY")) {
                    logger.warn(
                        "API check for {} (ID {}) received GOAWAY (Attempt {}/{}). Retrying...",
                        serverName,
                        serverId,
                        attempt,
                        maxRetries
                    );
                    if (attempt == maxRetries) {
                        logger.error(
                            "API check for {} (ID {}) failed after {} retries due to GOAWAY: {}",
                            serverName,
                            serverId,
                            maxRetries,
                            e.getMessage()
                        );
                        return false; // Failed after retries
                    }
                    try {
                        Thread.sleep(
                            1000 * attempt
                        ); // Simple exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn(
                            "API check retry sleep interrupted for {} (ID {}).",
                            serverName,
                            serverId
                        );
                        return false; // Interrupted during sleep
                    }
                    // Continue to the next iteration of the loop
                } else {
                    // Other IOExceptions
                    logger.error(
                        "API check for {} (ID {}) failed with IOException: {}",
                        serverName,
                        serverId,
                        e.getMessage(),
                        e
                    );
                    return false; // Other I/O error
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn(
                    "API check for {} (ID {}) interrupted.",
                    serverName,
                    serverId
                );
                return false;
            } catch (Exception e) { // Catch unexpected errors
                logger.error(
                    "Unexpected error during API check for {} (ID {}): {}",
                    serverName,
                    serverId,
                    e.getMessage(),
                    e
                );
                return false;
            }
        }
        // Should only be reached if all retries failed
        return false;
    }

    /**
     * Checks if a server registered in Velocity has any players connected.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server has no players or is not found, {@code false} otherwise.
     */
    @Override
    public boolean isServerEmpty(String serverName) {
        return proxyServer.getServer(serverName)
            .map(server -> server.getPlayersConnected().isEmpty())
            .orElse(true); // Treat non-existent server as empty
    }
    /**
     * Shuts down the executor service used for API requests.
     */
    public void shutdown() {
        executorService.shutdownNow();
    }
}
