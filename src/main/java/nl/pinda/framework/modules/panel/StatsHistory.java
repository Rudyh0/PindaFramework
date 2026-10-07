package nl.pinda.framework.modules.panel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Houdt elke minuut het aantal spelers, de TPS en het geheugen bij (laatste 24 uur, alleen in het geheugen). */
final class StatsHistory {

    private static final int MAX_SAMPLES = 24 * 60;

    record Sample(long time, int online, double tps, long memory) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();

    synchronized void add(Sample sample) {
        samples.addLast(sample);
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    synchronized List<Sample> all() {
        return new ArrayList<>(samples);
    }

    synchronized void clear() {
        samples.clear();
    }
}
