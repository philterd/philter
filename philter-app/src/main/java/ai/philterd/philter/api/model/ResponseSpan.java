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
package ai.philterd.philter.api.model;

import ai.philterd.phileas.model.filtering.Span;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.util.LinkedList;
import java.util.List;

/**
 * A span returnable to the client.
 */
public final class ResponseSpan {

    private int characterStart;
    private int characterEnd;
    private String filterType;
    private String context;
    private double confidence;
    private String replacement;
    private String salt;

    private ResponseSpan(int characterStart, int characterEnd, String filterType, String context, double confidence, String replacement, String salt) {

        this.characterStart = characterStart;
        this.characterEnd = characterEnd;
        this.filterType = filterType;
        this.context = context;
        this.confidence = confidence;
        this.replacement = replacement;
        this.salt = salt;

    }

    public static ResponseSpan fromSpan(Span span) {

        return new ResponseSpan(
                span.getCharacterStart(),
                span.getCharacterEnd(),
                span.getFilterType().toString(),
                span.getContext(),
                span.getConfidence(),
                span.getReplacement(),
                span.getSalt()
        );

    }

    public static List<ResponseSpan> fromSpans(List<Span> spans) {

        List<ResponseSpan> responseSpans = new LinkedList<>();

        for(Span span : spans) {
            responseSpans.add(ResponseSpan.fromSpan(span));
        }

        return responseSpans;

    }

    @Override
    public int hashCode() {

        return new HashCodeBuilder(17, 37).
                append(characterStart).
                append(characterEnd).
                append(filterType).
                append(confidence).
                append(context).
                append(replacement).
                append(salt).
                toHashCode();

    }

    @Override
    public boolean equals(Object o) {

        return EqualsBuilder.reflectionEquals(this, o);

    }

    @Override
    public String toString() {

        return "characterStart: " + characterStart
                + " characterEnd: " + characterEnd
                + " filterType: " + filterType
                + " context: " + context
                + " confidence: " + confidence
                + " replacement: " + replacement
                + " salt: " + salt;

    }

    public int getCharacterStart() {
        return characterStart;
    }

    public void setCharacterStart(int characterStart) {
        this.characterStart = characterStart;
    }

    public int getCharacterEnd() {
        return characterEnd;
    }

    public void setCharacterEnd(int characterEnd) {
        this.characterEnd = characterEnd;
    }

    public String getFilterType() {
        return filterType;
    }

    public void setFilterType(String filterType) {
        this.filterType = filterType;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }


}