package com.gecopilot.client;

public final class ServerConfig {
    private ServerConfig() {}
    public static final String BASE_URL = "https://ge-copilot.onrender.com";
    public static final String DISCORD_URL = "https://discord.gg/zeBkPymf4f";
    public static final String VERSION = "0.1.31-beta";
    // Shown once in the panel after a version update (existing users only). Update per release.
    // HTML fragment (wrapped in <html> by the banner); keep it to a few bullet lines.
    public static final String WHATS_NEW =
        "- A price chart now covers the chatbox on the GE Set up offer and Offer status screens, with your offer and Coffer's suggested buy and sell\n"
        + "- Hover it for the price, margin and volume at that time, and switch between 1d, 1w, 1m and 1y with its tabs\n"
        + "- Price history comes from the OSRS Wiki; turn the chart off with \"GE offer price chart\" in the plugin config";
}
