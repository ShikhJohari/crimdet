package com.crimdet.service;

import com.crimdet.model.RecognizedFace;

import java.awt.image.BufferedImage;
import java.util.List;

/** Outcome of a single {@link FrameProcessor} cycle. */
public record FrameResult(BufferedImage frame, List<RecognizedFace> faces, long latencyMs) {}
