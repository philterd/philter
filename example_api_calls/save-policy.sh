#!/bin/bash -e

curl -v -s -X POST "http://localhost:8080/api/policies" -H "Content-Type: application/json"  -d @../distribution/policies/default.json
