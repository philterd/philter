/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.testutil;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects every message logged while it is open, from any logger, to check what reaches the log. */
public final class LogCapture implements AutoCloseable {

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Logger root = (Logger) LogManager.getRootLogger();
    private final AbstractAppender appender;

    public LogCapture() {
        appender = new AbstractAppender("capture-" + System.identityHashCode(this), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(final LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
                entries.add(new Entry(event.getLevel(), event.getMessage().getFormattedMessage(), event.getThrown() != null));
            }
        };
        appender.start();
        root.addAppender(appender);
    }

    /** Whether any message logged so far contains {@code text}. */
    public boolean contains(final String text) {
        return messages.stream().anyMatch(message -> message.contains(text));
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }

    /** Each message logged so far, with its level and whether a stack trace was attached. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public record Entry(Level level, String message, boolean thrown) {
    }

    @Override
    public void close() {
        root.removeAppender(appender);
        appender.stop();
    }

}
