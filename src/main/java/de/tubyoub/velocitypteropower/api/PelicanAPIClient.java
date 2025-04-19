package de.tubyoub.velocitypteropower.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.*;

public class PelicanAPIClient implements PanelAPIClient {
    public final Logger logger;
    public final ConfigurationManager configurationManager;
    public final ProxyServer proxyServer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VelocityPteroPower plugin;

    private final HttpClient httpClient;
    private final ExecutorService executorService;

    public PelicanAPIClient(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.proxyServer = plugin.getProxyServer();

        this.executorService = Executors.newFixedThreadPool(10); // Limit to 10 threads
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();
    }

    @Override
    public void powerServer(String serverId, String signal) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/power"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"signal\": \"" + signal + "\"}"))
                    .build();

            plugin.updateRateLimitInfo(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            logger.error("Error powering server.", e);
        }
    }

    @Override
    public boolean isServerOnline(String serverName, String serverId) {
        ConfigurationManager.ServerCheckMethod method = configurationManager.getServerCheckMethod();

        if (method == ConfigurationManager.ServerCheckMethod.VELOCITY_PING) {
            // --- Use Velocity Ping Method ---
            Optional<RegisteredServer> serverOptional = proxyServer.getServer(serverName);
            if (serverOptional.isPresent()) {
                RegisteredServer server = serverOptional.get();
                try {
                    CompletableFuture<ServerPing> pingFuture = server.ping();
                    // Use timeout from config
                    ServerPing pingResult = pingFuture.get(configurationManager.getPingTimeout(), TimeUnit.MILLISECONDS);
                    boolean online = pingResult != null;
                    logger.debug("Ping check for {}: {}", serverName, online ? "Success" : "Failed (No result)");
                    return online;
                } catch (TimeoutException e) {
                    logger.debug("Ping check for {} timed out after {}ms.", serverName, configurationManager.getPingTimeout());
                    return false;
                } catch (ExecutionException e) {
                    // Log the underlying cause if possible
                    logger.debug("Ping check for {} failed: {}", serverName, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    return false;
                } catch (InterruptedException e) {
                    logger.warn("Ping check for {} interrupted.", serverName);
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    // Catch unexpected errors during ping
                    logger.warn("Unexpected error pinging server {}: {}", serverName, e.getMessage(), e);
                    return false;
                }
            } else {
                // Server not registered in Velocity, cannot ping
                logger.debug("Cannot perform PING check: Server '{}' not registered in Velocity.", serverName);
                return false;
            }

        } else if (method == ConfigurationManager.ServerCheckMethod.PANEL_API) {
            // --- Use Pterodactyl API Method ---
            if (serverId == null || serverId.isEmpty()) {
                 logger.error("Cannot perform API check: Server ID is missing for server '{}'.", serverName);
                 return false;
            }
            int retryCount = 3;
            while (retryCount > 0) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + "/resources"))
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey())
                            .GET()
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    plugin.updateRateLimitInfo(response);
                    String responseBody = response.body();

                    if (response.statusCode() == 200) {
                        // More robust check: parse JSON or use contains carefully
                        // Using contains for simplicity based on old code
                        boolean running = responseBody != null && responseBody.contains("\"current_state\":\"running\"");
                        logger.debug("API check for {} (ID {}): Status {}, State Running: {}", serverName, serverId, response.statusCode(), running);
                        return running;
                    } else {
                        logger.warn("API check for {} (ID {}) failed with status code: {}", serverName, serverId, response.statusCode());
                        return false; // Non-200 means not running or error
                    }
                } catch (IOException e) {
                    // Check specifically for GOAWAY which might indicate HTTP/2 issues
                    if (e.getMessage() != null && e.getMessage().contains("GOAWAY")) {
                        retryCount--;
                        if (retryCount == 0) {
                            logger.error("API check for {} (ID {}) failed after retries due to GOAWAY: {}", serverName, serverId, e.getMessage());
                            return false;
                        }
                        logger.warn("API check for {} (ID {}) received GOAWAY, retrying... ({} retries left)", serverName, serverId, retryCount);
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logger.warn("API check retry sleep interrupted for {} (ID {}).", serverName, serverId);
                            return false;
                        }
                        // Continue to next iteration of the while loop
                    } else {
                        // Other IOExceptions
                        logger.error("API check for {} (ID {}) failed with IOException: {}", serverName, serverId, e.getMessage());
                        return false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("API check for {} (ID {}) interrupted.", serverName, serverId);
                    return false;
                } catch (Exception e) {
                    // Catch unexpected errors during API call
                    logger.error("Unexpected error during API check for {} (ID {}): {}", serverName, serverId, e.getMessage(), e);
                    return false;
                }
            }
            // If loop finishes due to retries exhausting
            return false;

        } else {
            // Should not happen if config loading is correct, but fallback
            logger.error("Invalid server-status-check-method configured ({})! Please check config. Defaulting to PING check.", method);
            // Optionally, call itself recursively with PING or duplicate the PING logic
            // For simplicity, just return false or try PING again here.
            // Let's try PING as a fallback:
             Optional<RegisteredServer> serverOptional = proxyServer.getServer(serverName);
             if (serverOptional.isPresent()) {
                 try {
                     return serverOptional.get().ping().get(configurationManager.getPingTimeout(), TimeUnit.MILLISECONDS) != null;
                 } catch (Exception e) { return false; }
             }
             return false;
        }
    }

        @Override
        public boolean isServerEmpty (String serverName){
            Optional<RegisteredServer> server = proxyServer.getServer(serverName);
            return server.map(value -> value.getPlayersConnected().isEmpty()).orElse(true);
        }

        public void shutdown () {
            executorService.shutdownNow();
        }
    }

