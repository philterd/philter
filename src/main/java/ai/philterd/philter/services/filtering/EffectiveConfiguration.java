package ai.philterd.philter.services.filtering;

/** Immutable execution inputs. Context mappings and vector contents intentionally remain live. */
public record EffectiveConfiguration(String policyJson, String contextJson) { }
