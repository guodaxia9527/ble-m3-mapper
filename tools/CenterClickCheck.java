package com.guodaxia.blem3mapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Host-only timing regression check; never connects to a phone or changes a player. */
public final class CenterClickCheck {
    private static int checks;

    private static final class Event {
        final String button;
        final long arrived;
        Event(String button, long arrived) { this.button = button; this.arrived = arrived; }
    }

    private static final class Replay {
        final CenterClickTracker<String> clicks = new CenterClickTracker<>(320);
        final ArrayDeque<Event> queue = new ArrayDeque<>();
        final List<String> output = new ArrayList<>();
        Object source = new Object();

        void enqueue(String button, long arrived) { queue.add(new Event(button, arrived)); }

        void result(CenterClickTracker.Click<String> click) {
            if (click != null) output.add(click.context + (click.doubleClick ? ":like" : ":toggle"));
        }

        void step(long workerTime) {
            result(clicks.expire(workerTime, !queue.isEmpty()));
            Event event = queue.poll();
            if (event == null) return;
            switch (event.button) {
                case "CENTER": result(clicks.press(source, "YouTube", event.arrived)); break;
                case "WARNING": break;
                case "DISCONNECTED": clicks.cancel(); break;
                default:
                    result(clicks.takeSingle());
                    output.add(event.button);
            }
        }

        void expect(String... expected) {
            if (!output.equals(Arrays.asList(expected))) {
                throw new AssertionError("Expected " + Arrays.toString(expected) + ", got " + output);
            }
            checks++;
        }
    }

    public static void main(String[] args) {
        Replay one = new Replay();
        one.enqueue("CENTER", 1000);
        one.step(1000);
        one.step(1320);
        one.expect(); // No playback toggle while the double-click window is still open.
        one.step(1321);
        one.step(1500);
        one.expect("YouTube:toggle");

        Replay pair = new Replay();
        pair.enqueue("CENTER", 1000);
        pair.step(1000);
        pair.enqueue("CENTER", 1230);
        pair.step(1230);
        pair.step(2000);
        pair.expect("YouTube:like");

        Replay boundary = new Replay();
        boundary.enqueue("CENTER", 1000);
        boundary.step(1000);
        boundary.enqueue("CENTER", 1320);
        boundary.step(1320);
        boundary.step(2000);
        boundary.expect("YouTube:like");

        Replay late = new Replay();
        late.enqueue("CENTER", 1000);
        late.enqueue("CENTER", 1321);
        late.step(4000);
        late.step(4000);
        late.step(4000);
        late.expect("YouTube:toggle", "YouTube:toggle");

        // A slow foreground/UI operation can leave diagnostics in front of the second
        // press. Worker time must not replace input time or split this genuine pair.
        Replay backlog = new Replay();
        backlog.enqueue("CENTER", 1000);
        backlog.enqueue("WARNING", 1100);
        backlog.enqueue("WARNING", 1180);
        backlog.enqueue("CENTER", 1240);
        for (int i = 0; i < 5; i++) backlog.step(4500);
        backlog.expect("YouTube:like");

        Replay partialBacklog = new Replay();
        partialBacklog.enqueue("CENTER", 1000);
        partialBacklog.step(1000);
        partialBacklog.enqueue("WARNING", 1150);
        partialBacklog.enqueue("CENTER", 1270);
        for (int i = 0; i < 3; i++) partialBacklog.step(2800);
        partialBacklog.expect("YouTube:like");

        Replay triple = new Replay();
        triple.enqueue("CENTER", 1000);
        triple.enqueue("CENTER", 1150);
        triple.enqueue("CENTER", 1275);
        for (int i = 0; i < 4; i++) triple.step(3000);
        triple.expect("YouTube:like", "YouTube:toggle");

        Replay interleaved = new Replay();
        interleaved.enqueue("CENTER", 1000);
        interleaved.enqueue("RIGHT", 1100);
        interleaved.enqueue("CENTER", 1200);
        for (int i = 0; i < 4; i++) interleaved.step(3000);
        interleaved.expect("YouTube:toggle", "RIGHT", "YouTube:toggle");

        Replay reconnect = new Replay();
        reconnect.enqueue("CENTER", 1000);
        reconnect.enqueue("DISCONNECTED", 1100);
        reconnect.enqueue("CENTER", 1200);
        for (int i = 0; i < 4; i++) reconnect.step(3000);
        reconnect.expect("YouTube:toggle"); // The abandoned old click never acts or pairs.

        Replay replacedProcess = new Replay();
        replacedProcess.result(replacedProcess.clicks.press(replacedProcess.source, "old", 1000));
        replacedProcess.source = new Object();
        replacedProcess.result(replacedProcess.clicks.press(replacedProcess.source, "new", 1100));
        replacedProcess.result(replacedProcess.clicks.expire(1500, false));
        replacedProcess.expect("old:toggle", "new:toggle"); // No cross-process double click.

        CenterClickTracker<String> timer = new CenterClickTracker<>(320);
        timer.press(new Object(), "YouTube", 1000);
        if (timer.pollMillis(1310, 50) != 11 || timer.pollMillis(5000, 50) != 1)
            throw new AssertionError("Polling must use the current clock and include the boundary");
        timer.cancel();
        if (timer.hasPending() || timer.expire(6000, false) != null)
            throw new AssertionError("Cancelled/changed-app gestures must not act later");
        checks++;
        System.out.println("CENTER_CLICK_OK checks=" + checks);
    }
}
