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
package ai.philterd.philter.model;

import org.bson.types.ObjectId;

public class ServiceResponse {

    private final String message;
    private final boolean successful;
    private final ObjectId objectId;
    private final int statusCode;
    private final String details;

    public static ServiceResponse success(final String message) {
        return new ServiceResponse(message, true);
    }

    public static ServiceResponse success() {
        return new ServiceResponse(true);
    }

    public static ServiceResponse success(final String message, final String details) {
        return new ServiceResponse(message, true, details);
    }

    public static ServiceResponse failure(final String message) {
        return new ServiceResponse(message, false);
    }

    public static ServiceResponse failure(final String message, final String details) {
        return new ServiceResponse(message, false, details);
    }

    public ServiceResponse(final String message, final boolean successful, final String details) {
        this.message = message;
        this.successful = successful;
        this.objectId = null;
        this.statusCode = 0;
        this.details = details;
    }


    public ServiceResponse(final String message, final boolean successful) {
        this.message = message;
        this.successful = successful;
        this.objectId = null;
        this.statusCode = 0;
        this.details = null;
    }

    public ServiceResponse(final String message, final boolean successful, final int statusCode) {
        this.message = message;
        this.successful = successful;
        this.objectId = null;
        this.statusCode = statusCode;
        this.details = null;
    }

    public ServiceResponse(final String message, final boolean successful, final ObjectId objectId, final int statusCode) {
        this.message = message;
        this.successful = successful;
        this.objectId = objectId;
        this.statusCode = statusCode;
        this.details = null;
    }

    public ServiceResponse(final boolean successful) {
        this.message = null;
        this.successful = successful;
        this.objectId = null;
        this.statusCode = 0;
        this.details = null;
    }

    public String getMessage() {
        return message;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public ObjectId getObjectId() {
        return objectId;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getDetails() {
        return details;
    }

}
