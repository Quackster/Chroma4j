package com.quackster.chroma;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a frame in a Chroma animation sequence
 */
public class ChromaFrame {
    private int loop = -1;
    private int framesPerSecond = -1;
    private List<String> frames;
    private final List<Sequence> sequences = new ArrayList<>();

    public ChromaFrame() {
        this.frames = new ArrayList<>();
    }

    public int getLoop() {
        return loop;
    }

    public void setLoop(int loop) {
        this.loop = loop;
    }

    public int getFramesPerSecond() {
        return framesPerSecond;
    }

    public void setFramesPerSecond(int framesPerSecond) {
        this.framesPerSecond = framesPerSecond;
    }

    public List<String> getFrames() {
        return frames;
    }

    public void setFrames(List<String> frames) {
        this.frames = frames;
    }

    public List<Sequence> getSequences() {
        return sequences;
    }

    public int getResolvedFrameCount() {
        int count = getLayerFrameCount();
        int loopCount = loop < 0 ? 1 : loop;
        if (loopCount <= 0) {
            loopCount = 1;
        }
        int repeat = framesPerSecond < 1 ? 1 : framesPerSecond;
        return Math.max(1, count * loopCount * repeat);
    }

    public int getAvailableFrameCount() {
        return Math.max(1, frames.size());
    }

    public Frame resolveFrame(int animationFrameIndex, int direction) {
        int layerFrameCount = getLayerFrameCount();
        if (layerFrameCount < 1) {
            return null;
        }

        int repeat = framesPerSecond < 1 ? 1 : framesPerSecond;
        int sequenceOffset = animationFrameIndex / repeat;
        int loopIndex = sequenceOffset / layerFrameCount;
        int localFrame = sequenceOffset % layerFrameCount;
        int loopCount = loop < 0 ? 1 : loop;

        if ((loopCount > 0 && loopIndex >= loopCount) || (loopCount <= 0 && layerFrameCount == 1)) {
            localFrame = layerFrameCount - 1;
        }

        int cursor = 0;
        for (Sequence sequence : sequences) {
            int sequenceFrameCount = sequence.getFrameCount();
            if (localFrame < cursor + sequenceFrameCount) {
                return sequence.getFrame(localFrame - cursor, direction);
            }
            cursor += sequenceFrameCount;
        }

        return null;
    }

    public Frame resolveAvailableFrame(int animationFrameIndex, int direction) {
        if (frames.isEmpty()) {
            return null;
        }
        int frameIndex = Math.min(Math.max(0, animationFrameIndex), frames.size() - 1);
        int cursor = 0;
        for (Sequence sequence : sequences) {
            int sequenceSize = sequence.getFrames().size();
            if (frameIndex < cursor + sequenceSize) {
                return sequence.getFrames().get(frameIndex - cursor).forDirection(direction);
            }
            cursor += sequenceSize;
        }
        return new Frame(frames.get(frameIndex), 0, 0);
    }

    public Frame firstFrame(int direction) {
        return resolveAvailableFrame(0, direction);
    }

    private int getLayerFrameCount() {
        int count = 0;
        for (Sequence sequence : sequences) {
            count += sequence.getFrameCount();
        }
        return count;
    }

    public static class Sequence {
        private int loopCount = 1;
        private final List<Frame> frames = new ArrayList<>();

        public Sequence(int loopCount) {
            if (loopCount > 0) {
                this.loopCount = loopCount;
            }
        }

        public List<Frame> getFrames() {
            return frames;
        }

        private int getFrameCount() {
            return frames.size() * loopCount;
        }

        private Frame getFrame(int index, int direction) {
            if (frames.isEmpty() || index < 0 || index >= getFrameCount()) {
                return null;
            }
            return frames.get(index % frames.size()).forDirection(direction);
        }
    }

    public static class Frame {
        private final String id;
        private final int x;
        private final int y;
        private final Map<Integer, int[]> offsets = new HashMap<>();

        public Frame(String id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        public String getId() {
            return id;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public void setOffset(int direction, int x, int y) {
            offsets.put(direction, new int[] {x, y});
        }

        private Frame forDirection(int direction) {
            int[] offset = offsets.get(direction);
            if (offset == null) {
                return this;
            }
            return new Frame(id, offset[0], offset[1]);
        }
    }
}
