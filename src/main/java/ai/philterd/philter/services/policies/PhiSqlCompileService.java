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
package ai.philterd.philter.services.policies;

import ai.philterd.phisql.Compiler;
import ai.philterd.phisql.CompileResult;
import ai.philterd.phisql.PhiSQL;

/**
 * Compiles PhiSQL policy source into the native Phileas policy JSON that Philter consumes, using the
 * PhiSQL reference compiler. Parse and compile errors are returned as a failed {@link Result} rather
 * than thrown, so callers can surface a clean error to the user.
 */
public class PhiSqlCompileService {

    // The compiler holds an immutable catalog and compiles statelessly per call, so a single instance
    // is reused across requests (loading the catalog once).
    private final Compiler compiler;

    public PhiSqlCompileService() {
        this.compiler = new Compiler();
    }

    public PhiSqlCompileService(final Compiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Compiles PhiSQL source to a native Phileas policy.
     * @param source The PhiSQL source.
     * @return A successful {@link Result} carrying the policy name, description, and compiled JSON, or a
     *         failed {@link Result} carrying the parse/compile error message.
     */
    public Result compile(final String source) {

        if (source == null || source.isBlank()) {
            return Result.error("The PhiSQL source is empty.");
        }

        try {
            final CompileResult compileResult = compiler.compile(source);
            return Result.ok(compileResult.policyName(), compileResult.description(), compileResult.toJsonString());
        } catch (final PhiSQL.ParseException | Compiler.CompileException ex) {
            return Result.error(ex.getMessage());
        }

    }

    /**
     * The outcome of a compile attempt: either a successfully compiled policy or an error message.
     */
    public static final class Result {

        private final boolean success;
        private final String name;
        private final String description;
        private final String policyJson;
        private final String error;

        private Result(final boolean success, final String name, final String description,
                       final String policyJson, final String error) {
            this.success = success;
            this.name = name;
            this.description = description;
            this.policyJson = policyJson;
            this.error = error;
        }

        static Result ok(final String name, final String description, final String policyJson) {
            return new Result(true, name, description, policyJson, null);
        }

        static Result error(final String error) {
            return new Result(false, null, null, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getPolicyJson() {
            return policyJson;
        }

        public String getError() {
            return error;
        }

    }

}
