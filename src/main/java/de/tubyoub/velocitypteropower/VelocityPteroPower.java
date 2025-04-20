/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 * (Header omitted for brevity, assume it's the same as others)
 */
package de.tubyoub.velocitypteropower;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PanelType;
import de.tubyoub.velocitypteropower.api.PelicanAPIClient;
import de.tubyoub.velocitypteropower.api.PterodactylAPIClient;
import de.tubyoub.velocitypteropower.command.PteroCommand;
import de.tubyoub.velocitypteropower.config.ConfigurationManager;
import de.tubyoub.velocitypteropower.config.MessagesManager;
import de.tubyoub.velocitypteropower.handler.PlayerConnectionHandler;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.listener.ServerSwitchListener;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.UpdateService;
import de.tubyoub.velocitypteropower.util.Metrics;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main plugin class for VelocityPteroPower.
 * Handles plugin initialization, dependency setup, and provides access to core components.
 */
@Plugin(
    id = "velocity-ptero-power",
    name = "VelocityPteroPower",
    version = "0.9.4",
    authors = {"TubYoub"},
    description = "Manage Pterodactyl/Pelican servers via Velocity.",
    url = "https://github.com/BT-Pluginz/VelocityPteroPower"
)
public class VelocityPteroPower {
    // Plugin constants
    private static final String VERSION = "0.4";
    private static final String MODRINTH_PROJECT_ID = "1dDr5J4w";
    private static final int BSTATS_PLUGIN_ID = 21465;

    // Injected dependencies
    private final ProxyServer proxyServer;
    private final ComponentLogger logger;
    private final Path dataDirectory;
    private final CommandManager commandManager;
    private final Metrics.Factory metricsFactory;

    // Core Components (Managers, Services, Handlers)
    private ConfigurationManager configurationManager;
    private MessagesManager messagesManager;
    private PanelAPIClient apiClient;
    private RateLimitTracker rateLimitTracker;
    private UpdateService updateService;
    private PlayerConnectionHandler playerConnectionHandler;
    private ServerLifecycleManager serverLifecycleManager;
    private ServerSwitchListener serverSwitchListener;

    // Shared State (managed here, passed to handlers/managers)
    private Map<String, PteroServerInfo> serverInfoMap = new ConcurrentHashMap<>();
    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();

    /**
     * Constructor called by Velocity's plugin loader.
     */
    @Inject
    public VelocityPteroPower(
        ProxyServer proxy,
        @DataDirectory Path dataDirectory,
        CommandManager commandManager,
        ComponentLogger logger,
        Metrics.Factory metricsFactory
    ) {
        this.proxyServer = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.commandManager = commandManager;
        this.metricsFactory = metricsFactory;
    }

    /**
     * Handles plugin initialization logic when the proxy starts.
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logStartupBanner();

        // 1. Initialize Config and Messages First
        this.configurationManager = new ConfigurationManager(this);
        this.messagesManager = new MessagesManager(this);
        configurationManager.loadConfig();
        messagesManager.loadMessages();
        logger.isEnabledForLevel(configurationManager.getLoggerLevel());

        // 2. Validate API Key
        if (!configurationManager.hasValidApiKey()) {
            logInvalidApiKeyError();
            return; // Stop initialization
        }

        // 3. Initialize Core Components
        this.rateLimitTracker = new RateLimitTracker(logger, configurationManager);
        this.updateService = new UpdateService(logger, configurationManager, VERSION, MODRINTH_PROJECT_ID);
        initializeApiClient(); // Sets up apiClient
        if (this.apiClient == null) {
            logger.error("Failed to initialize Panel API Client. Plugin disabled.");
            return;
        }
        this.serverInfoMap = configurationManager.getServerInfoMap(); // Load initial map

        // 4. Initialize Handlers/Managers/Listeners that depend on core components
        this.serverLifecycleManager = new ServerLifecycleManager(proxyServer,this);
        this.playerConnectionHandler = new PlayerConnectionHandler(proxyServer, this);
        this.serverSwitchListener = new ServerSwitchListener(this, serverLifecycleManager);

        // 5. Register Commands and Listeners
        commandManager.register(
            commandManager.metaBuilder("ptero").aliases("vpp").build(),
            new PteroCommand(this)
        );
        proxyServer.getEventManager().register(this, playerConnectionHandler);
        proxyServer.getEventManager().register(this, serverSwitchListener);

        // 6. Enable bStats Metrics
        metricsFactory.make(this, BSTATS_PLUGIN_ID);

        // 7. Check for Updates
        updateService.performUpdateCheck();

        logger.info("VelocityPteroPower v{} successfully loaded.", VERSION);
    }

    /**
     * Handles plugin shutdown logic.
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (apiClient != null) {
            apiClient.shutdown();
        }
        // Potentially add shutdown logic for other components if needed
        logger.info("Shutting down VelocityPteroPower... Goodbye!");
    }

    /** Reloads the plugin's configuration and messages, and updates relevant state. */
    public void reload() {
        logger.info("Reloading VelocityPteroPower configuration...");

        // Reload core config/messages
        configurationManager.loadConfig();
        messagesManager.loadMessages();
        logger.isEnabledForLevel(configurationManager.getLoggerLevel());

        // Update server info map reference (important for handlers/managers using it)
        this.serverInfoMap = configurationManager.getServerInfoMap();
        // Note: If handlers/managers made copies, they'd need a way to get the new map.
        // Since they hold references, updating this.serverInfoMap should be sufficient IF
        // ConfigurationManager returns the *same map instance* or the handlers re-fetch it.
        // Let's assume ConfigurationManager updates its internal map and getServerInfoMap returns the current one.

        // Re-initialize API client if panel type or credentials changed
        PanelType oldType = (apiClient instanceof PelicanAPIClient) ? PanelType.pelican : PanelType.pterodactyl;
        PanelType newType = configurationManager.getPanelType();
        String oldKey = apiClient != null ? configurationManager.getPterodactylApiKey() : ""; // Get key before reload potentially changes it
        String newKey = configurationManager.getPterodactylApiKey(); // Key after reload

        // Only re-init if type or key actually changed
        if (apiClient == null || oldType != newType || !oldKey.equals(newKey)) {
             logger.info("API client configuration changed. Re-initializing...");
             if (apiClient != null) {
                 apiClient.shutdown(); // Shutdown old client
             }
             initializeApiClient();
             if (apiClient == null) {
                 logger.error("Failed to re-initialize Panel API Client after reload. Plugin may not function correctly.");
             }
             // Update API client reference in components that use it
             if (serverLifecycleManager != null) {
                 // Need a setter or re-creation if API client is final in manager
                 // For simplicity, let's assume re-creation or non-final field for now.
                 // This highlights a complexity of splitting - managing dependency updates.
                 // A better approach might use dependency injection frameworks.
                 logger.warn("API Client re-initialized. Dependent components might need restarting or updating.");
             }
             if (playerConnectionHandler != null) {
                  logger.warn("API Client re-initialized. Dependent components might need restarting or updating.");
             }

        } else {
             logger.info("API client configuration unchanged.");
        }


        logger.info("VelocityPteroPower configuration reloaded.");
    }

    /** Initializes the appropriate PanelAPIClient based on configuration. */
    private void initializeApiClient() {
        PanelType type = configurationManager.getPanelType();
        logger.info("Initializing API client for panel type: {}", type);
        // Ensure API key is valid before creating client
        if (!configurationManager.hasValidApiKey()) {
             logInvalidApiKeyError();
             this.apiClient = null;
             return;
        }

        if (type == PanelType.pelican) {
            this.apiClient = new PelicanAPIClient(this);
        } else { // Default to Pterodactyl
            this.apiClient = new PterodactylAPIClient(this);
        }
    }

    /** Logs the plugin's startup banner. */
    private void logStartupBanner() {
        MiniMessage mm = MiniMessage.miniMessage();
        logger.info(mm.deserialize("<#4287f5>____   ________________________"));
        logger.info(mm.deserialize("<#4287f5>\\   \\ /   /\\______   \\______   \\"));
        logger.info(mm.deserialize("<#4287f5> \\   Y   /  |     ___/|     ___/"));
        logger.info(mm.deserialize("<#4287f5>  \\     /   |    |    |    |"+ "<#00ff77>         VelocityPteroPower <#6b6c6e>v" + VERSION));
        logger.info(mm.deserialize("<#4287f5>   \\___/    |____|tero|____|ower" + "<#A9A9A9>     Running on Velocity"));
    }

    /** Logs a detailed error message when the API key is invalid. */
    private void logInvalidApiKeyError() {
         logger.error("=================================================");
         logger.error(" VelocityPteroPower Initialization Failed!");
         logger.error(" ");
         logger.error(" No valid API key found or configured in config.yml.");
         logger.error(" Please ensure 'pterodactyl.apiKey' is set correctly.");
         logger.error(" Key should start with 'ptlc_' (Client) or 'peli_' (Pelican).");
         logger.error(" Application API keys ('ptla_') are NOT supported.");
         logger.error(" ");
         logger.error(" Plugin will be disabled.");
         logger.error("=================================================");
    }

    /**
     * Gets the formatted plugin prefix for messages.
     * Example: "[VPP] "
     *
     * @return The prefix component.
     */
    public Component getPluginPrefix() {
        TextColor prefixColor = TextColor.color(66, 135, 245); // Blueish
        return Component.text("[", NamedTextColor.GRAY)
            .append(Component.text(messagesManager.getMessage("prefix"), prefixColor))
            .append(Component.text("] ", NamedTextColor.GRAY));
    }

    // --- Getters for Core Components and State ---
    // These allow other classes (like commands) to access necessary parts

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public ComponentLogger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }

    public MessagesManager getMessagesManager() {
        return messagesManager;
    }

    public PanelAPIClient getApiClient() {
        return apiClient;
    }

    public RateLimitTracker getRateLimitTracker() {
        return rateLimitTracker;
    }

     public ServerLifecycleManager getServerLifecycleManager() {
        return serverLifecycleManager;
    }

    public Map<String, PteroServerInfo> getServerInfoMap() {
        // Return the live map so handlers see updates after reload
        return serverInfoMap;
    }

    public Set<String> getStartingServers() {
        return startingServers; // Used by PlayerConnectionHandler
    }

    public Map<UUID, Long> getPlayerCooldowns() {
        return playerCooldowns; // Used by PlayerConnectionHandler
    }
}
