/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
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
package ai.philterd.philter.services;

import ai.philterd.phileas.model.policy.Policy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

public class OpenSearchPolicyServiceTest {

    private final Gson gson = new Gson();

    @Test
    public void deserializePolicy() throws JsonProcessingException {

        // tests policy deserialization

        final String json = """
                {
                          "name": "default",
                          "config": {
                            "splitting": {
                              "enabled": true,
                              "threshold": 384,
                              "method": "width"
                            },
                            "pdf": {
                              "redactionColor": "black",
                              "showReplacement": false,
                              "replacementFont": "helvetica",
                              "replacementMaxFontSize": 12.0,
                              "scale": 0.25,
                              "dpi": 150,
                              "compressionQuality": 1.0,
                              "preserveUnredactedPages": false
                            },
                            "postFilters": {
                              "removeTrailingPeriods": true,
                              "removeTrailingSpaces": true,
                              "removeTrailingNewLines": true
                            },
                            "analysis": {
                              "identification": true,
                              "sentiment": {
                                "model": "classpath:en-sentiment.bin",
                                "enabled": false
                              },
                              "offensiveness": {
                                "model": "classpath:/en-offensiveness.bin",
                                "enabled": false
                              }
                            }
                          },
                          "identifiers": {
                            "all": false,
                            "customDictionaries": [],
                            "age": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "ageFilterStrategies": [
                                {
                                  "id": "ade49fb6-66e0-407b-923b-2f67830280ad",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "AGE"
                                }
                              ]
                            },
                            "creditCard": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "onlyValidCreditCardNumbers": true,
                              "creditCardFilterStrategies": [
                                {
                                  "id": "6fcb6847-95aa-4153-90d7-92801749b7a9",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "CREDIT_CARD"
                                }
                              ],
                              "ignoreWhenInUnixTimestamp": false,
                              "onlyWordBoundaries": true
                            },
                            "date": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "onlyValidDates": false,
                              "dateFilterStrategies": [
                                {
                                  "id": "85881f8e-d5c4-4836-a5a7-e73de9b62707",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "shiftRandom": false,
                                  "shiftDays": 0,
                                  "shiftMonths": 0,
                                  "shiftYears": 0,
                                  "futureDates": false,
                                  "filterType": "DATE"
                                }
                              ]
                            },
                            "emailAddress": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "onlyStrictMatches": true,
                              "onlyValidTLDs": false,
                              "emailAddressFilterStrategies": [
                                {
                                  "id": "0cbfb033-8506-4159-a791-4364421c15be",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "EMAIL_ADDRESS"
                                }
                              ]
                            },
                            "ipAddress": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "ipAddressFilterStrategies": [
                                {
                                  "id": "98f809de-d71e-46f7-8910-203dab128ff8",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "IP_ADDRESS"
                                }
                              ]
                            },
                            "phoneNumber": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "phoneNumberFilterStrategies": [
                                {
                                  "id": "9fff375a-3972-4cec-bd74-f1692cc11417",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "PHONE_NUMBER"
                                }
                              ]
                            },
                            "ssn": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "ssnFilterStrategies": [
                                {
                                  "id": "ff98ab25-0145-4c5e-8e6e-f8238295813d",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "SSN"
                                }
                              ]
                            },
                            "url": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "urlFilterStrategies": [
                                {
                                  "id": "915dd4a1-7a7d-494c-90be-0512f61c0482",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "URL"
                                }
                              ],
                              "requireHttpWwwPrefix": true
                            },
                            "vin": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "vinFilterStrategies": [
                                {
                                  "id": "698aea2a-142b-46ff-8b50-a6723fff23b4",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "VIN"
                                }
                              ]
                            },
                            "zipCode": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "zipCodeFilterStrategies": [
                                {
                                  "id": "c8d3640b-ce3a-4b09-85cd-b0c00704933d",
                                  "strategy": "REDACT",
                                  "redactionFormat": "{{{REDACTED-%t}}}",
                                  "replacementScope": "DOCUMENT",
                                  "staticReplacement": "",
                                  "maskCharacter": "*",
                                  "maskLength": "SAME",
                                  "truncateCharacter": "*",
                                  "truncateDirection": "LEADING",
                                  "condition": "",
                                  "alert": false,
                                  "salt": false,
                                  "filterType": "ZIP_CODE"
                                }
                              ],
                              "requireDelimiter": false,
                              "validate": false
                            },
                            "phEye": {
                              "enabled": true,
                              "ignored": [],
                              "ignoredFiles": [],
                              "ignoredPatterns": [],
                              "windowSize": 0,
                              "priority": 0,
                              "phEyeConfiguration": {
                                "endpoint": "http://philter-ph-eye-1:5000",
                                "timeout": 600,
                                "maxIdleConnections": 30,
                                "keepAliveDurationMs": 30,
                                "labels": [
                                  "Person"
                                ]
                              },
                              "removePunctuation": false,
                              "thresholds": {}
                            }
                          },
                          "ignored": [],
                          "ignoredPatterns": [],
                          "graphical": {
                            "boundingBoxes": []
                          }
                        }
                }""";

        final ObjectMapper objectMapper = new ObjectMapper();
        final Policy policy = objectMapper.readValue(json, Policy.class);

        Assert.assertEquals("default", policy.getName());

    }

}
