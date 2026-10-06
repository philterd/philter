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
package ai.philterd.philter.utils;

/**
 * The rule for names a client puts in a request path: a legal hold reference, a custom list name, and a
 * context name. Each of these characters, percent-encoded in a path, is refused by Tomcat or Spring before
 * Philter sees the request, so an item named with one could be created but never read, changed, or
 * removed through its path. Policy names follow a stricter rule of their own.
 */
public final class PathSafeNames {

    /** The rule, worded for an error message and the documentation. */
    public static final String RULE = "cannot contain /, \\, ;, %, or control characters, and cannot be . or ..";

    private PathSafeNames() {
    }

    /** Whether the name can be used in a request path. A null or blank name is for the caller to refuse. */
    public static boolean isPathSafe(final String name) {
        if (name == null) {
            return true;
        }
        if (".".equals(name) || "..".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ';' || c == '%' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

}
