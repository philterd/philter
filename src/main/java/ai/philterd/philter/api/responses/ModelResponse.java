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
package ai.philterd.philter.api.responses;

import ai.philterd.philter.services.models.PhEyeModels;
import io.swagger.v3.oas.annotations.media.Schema;

/** A local PhEye model a policy can name. Its location on disk is not included. */
public class ModelResponse {

    private final String name;
    private final String encoder;
    private final Integer maxLength;
    private final Integer maxWidth;
    private final long sizeBytes;

    public ModelResponse(final PhEyeModels.PhEyeModel model) {
        this.name = model.name();
        this.encoder = model.encoder();
        this.maxLength = model.maxLength();
        this.maxWidth = model.maxWidth();
        this.sizeBytes = model.sizeBytes();
    }

    @Schema(description = "The model's name, which a policy gives as a ph-eye filter's phEyeConfiguration.modelPath.")
    public String getName() { return name; }

    @Schema(description = "The encoder the model was trained from, from its gliner_config.json, for example "
            + "microsoft/deberta-v3-small. Absent when the configuration does not say.", nullable = true)
    public String getEncoder() { return encoder; }

    @Schema(description = "The most words of a text the model reads; later words are not examined. Absent when "
            + "the configuration does not say.", nullable = true)
    public Integer getMaxLength() { return maxLength; }

    @Schema(description = "The most words one detected entity can span. Absent when the configuration does not say.",
            nullable = true)
    public Integer getMaxWidth() { return maxWidth; }

    @Schema(description = "The size of the model's files, in bytes.")
    public long getSizeBytes() { return sizeBytes; }

}
