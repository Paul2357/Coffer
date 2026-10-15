package com.gecopilot.client;

public final class ServerConfig {
    private ServerConfig() {}
    public static final String BASE_URL = "https://ge-copilot.onrender.com";
    public static final String DISCORD_URL = "https://discord.gg/zeBkPymf4f";
    public static final String VERSION = "0.1.26-beta";
    // Shown once in the panel after a version update (existing users only). Update per release.
    // HTML fragment (wrapped in <html> by the banner); keep it to a few bullet lines.
    public static final String WHATS_NEW =
        "- Stay signed in while you use Coffer (sessions renew automatically)\n"
        + "- Clear 'Signed out' and 'Can't reach Coffer' notices instead of empty tabs";
}
