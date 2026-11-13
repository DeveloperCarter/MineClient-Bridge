package io.github.campione01.mineclientbridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatFeedbackTrackerTest {
    @Test void ordinaryChatIsRecordedByItsEventOnly() {
        var tracker = new ChatFeedbackTracker();
        Object component = new Object();
        tracker.received(component);
        assertFalse(tracker.local(new Object(), component));
    }
    @Test void localRepliesAreOncePerLineIncludingRepeatedIdenticalComponents() {
        var tracker = new ChatFeedbackTracker();
        Object component = new Object();
        Object line = new Object();
        assertTrue(tracker.local(line, component));
        assertFalse(tracker.local(line, component));
        assertTrue(tracker.local(new Object(), component));
        tracker.clear();
        assertTrue(tracker.local(line, component));
    }
    @Test void oldIdentitiesAreBoundedAndCanBeForgotten() {
        var tracker = new ChatFeedbackTracker();
        Object oldest = new Object();
        tracker.local(oldest, new Object());
        for (int index = 0; index < 300; index++) tracker.local(new Object(), new Object());
        assertTrue(tracker.local(oldest, new Object()));
    }
}
