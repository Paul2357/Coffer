package com.gecopilot.client;

public final class ServerConfig {
    private ServerConfig() {}
    public static final String BASE_URL = "https://ge-copilot.onrender.com";
    public static final String DISCORD_URL = "https://discord.gg/zeBkPymf4f";
    public static final String VERSION = "0.1.28-beta";
    // Shown once in the panel after a version update (existing users only). Update per release.
    // HTML fragment (wrapped in <html> by the banner); keep it to a few bullet lines.
    public static final String WHATS_NEW =
        "- Coffer checks in less often while its panel is closed and catches up the moment you open it\n"
        + "- Price-move tips can arrive up to 10 min late while the panel is closed; GE offer alerts are unchanged\n"
        + "- The GE price overlay retries quietly if the server is slow to respond";
}
