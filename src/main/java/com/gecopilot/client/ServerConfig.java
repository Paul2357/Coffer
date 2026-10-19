package com.gecopilot.client;

public final class ServerConfig {
    private ServerConfig() {}
    public static final String BASE_URL = "https://ge-copilot.onrender.com";
    public static final String DISCORD_URL = "https://discord.gg/zeBkPymf4f";
    public static final String VERSION = "0.1.30-beta";
    // Shown once in the panel after a version update (existing users only). Update per release.
    // HTML fragment (wrapped in <html> by the banner); keep it to a few bullet lines.
    public static final String WHATS_NEW =
        "- A banner at the top of the panel tells you when Coffer has a service notice or is down for maintenance\n"
        + "- While Coffer is unavailable the plugin checks back less often, and the banner clears once it is back\n"
        + "- Notices also appear once in chat; turn that off with \"Service notices in chat\" in the plugin config";
}
