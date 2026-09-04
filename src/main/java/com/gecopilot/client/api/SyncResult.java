package com.gecopilot.client.api;

import java.util.ArrayList;
import java.util.List;

public class SyncResult {
    public List<CloudFlip> flips = new ArrayList<>();
    public List<PlanRowDto> plan = new ArrayList<>();
    public List<PositionDto> positions = new ArrayList<>();
    public List<AlchDto> alch = new ArrayList<>();
    public List<CrashDto> crash = new ArrayList<>();
    public List<WatchDto> watch = new ArrayList<>();
    public String tier;
}
