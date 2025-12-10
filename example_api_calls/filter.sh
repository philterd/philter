#!/bin/bash -e

# Create an SSN-only policy.
curl -v -s -X POST "http://localhost:8080/api/policies" -H "Content-Type: application/json" -d @./ssn.json

# Apply the policy to text.
curl -s -X POST "http://localhost:8080/api/filter?p=ssn" -H "Content-Type: text/plain" -H "Accept: text/plain" -d'His SSN was 123-45-6789.'
