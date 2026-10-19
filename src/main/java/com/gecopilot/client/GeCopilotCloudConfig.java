package com.gecopilot.client;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gecopilotcloud")
public interface GeCopilotCloudConfig extends Config {
    // Edited from the Coffer panel's Settings cog, not here. Hidden from RuneLite's config UI so
    // there's a single place to change them (the values are still stored under this config group).
    @ConfigItem(keyName = "bankroll", name = "Bankroll (gp)", description = "Coins available to flip with", hidden = true)
    default int bankroll() { return 10_000_000; }

    @ConfigItem(keyName = "slots", name = "GE slots", description = "GE slots to use for flipping", hidden = true)
    default int slots() { return 4; }

    @ConfigItem(keyName = "pace", name = "Pace (active/steady/patient)", description = "Trading pace", hidden = true)
    default String pace() { return "steady"; }

    @ConfigItem(keyName = "access", name = "Access (all/members/f2p)", description = "Item access filter", hidden = true)
    default String access() { return "all"; }

    @ConfigItem(keyName = "nudgesEnabled", name = "In-client nudges", description = "In-game position nudges", hidden = true)
    default boolean nudgesEnabled() { return true; }

    @ConfigItem(keyName = "nudgeCooldownMin", name = "Nudge cooldown (min)", description = "Minutes between nudges per item", hidden = true)
    default int nudgeCooldownMin() { return 5; }

    // Risk / filter preferences. riskTolerance is a quick-fill preset over the four raw values; the
    // raw values are what actually get sent to the server. Balanced is the default for fresh installs.
    @ConfigItem(keyName = "riskTolerance", name = "Risk tolerance", description = "conservative/balanced/aggressive/custom", hidden = true)
    default String riskTolerance() { return "balanced"; }

    @ConfigItem(keyName = "minVolume", name = "Min volume/hr", description = "Lowest hourly volume to consider", hidden = true)
    default int minVolume() { return 200; }

    @ConfigItem(keyName = "minMargin", name = "Min margin (gp)", description = "Lowest per-item margin to consider", hidden = true)
    default int minMargin() { return 10; }

    @ConfigItem(keyName = "minRoi", name = "Min ROI (%)", description = "Lowest return-on-investment to consider", hidden = true)
    default double minRoi() { return 0.3; }

    @ConfigItem(keyName = "fillTarget", name = "Fill target (%)", description = "Desired fill probability", hidden = true)
    default int fillTarget() { return 75; }

    @ConfigItem(keyName = "onboardingSeen", name = "Onboarding seen", description = "First-run guide dismissed", hidden = true)
    default boolean onboardingSeen() { return false; }

    // Visible in the RuneLite config panel (the other keys are edited from the Coffer settings cog).
    @ConfigItem(keyName = "priceWarnings", name = "Offer price warnings", position = 1,
        description = "On the GE offer screen, warn when a sell is below what buyers are paying now, a buy is above the market high, or a buy's after-tax margin is too thin to flip")
    default boolean priceWarnings() { return true; }

    @ConfigItem(keyName = "statusChatNotice", name = "Service notices in chat", position = 2,
        description = "When Coffer posts a service notice (maintenance, an outage), also say it once in the chatbox. The panel banner shows either way")
    default boolean statusChatNotice() { return true; }
}
