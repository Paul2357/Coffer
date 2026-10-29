package com.gecopilot.client;

public final class ServerConfig {
    private ServerConfig() {}
    public static final String BASE_URL = "https://ge-copilot.onrender.com";
    public static final String DISCORD_URL = "https://discord.gg/zeBkPymf4f";
    public static final String VERSION = "0.1.34-beta";
    // Shown once in the panel after a version update (existing users only). Update per release.
    // HTML fragment (wrapped in <html> by the banner); keep it to a few bullet lines.
    public static final String WHATS_NEW =
        "- The GE offer screen and flip cards now show how many of an item you can still buy and when its 4-hour buy limit resets.\n"
        + "- Each GE slot now shows how long its offer has gone without a fill, turning amber after 15 minutes and red after an hour. You can turn the timers off in settings.\n"
        + "- Price warnings on the GE offer screen now check the price you've typed in.";
}
