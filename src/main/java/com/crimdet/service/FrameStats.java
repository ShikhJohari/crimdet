package com.crimdet.service;

/** Live snapshot of {@link FrameProcessor} throughput. */
public record FrameStats(long processedFrames, long droppedFrames, double avgLatencyMs, double fps) {}
