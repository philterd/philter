# Philter Release Notes

Issues whose identifiers start with `PHI-` were previously tracked in Jira before the project's issues were managed in GitHub.

## Version 2.6.0 - Not yet released

* First open source release of Philter under the Apache License, version 2.0.

## Version 2.5.0 - July 6, 2024

This version uses Phileas 2.6.0.

* [#36](https://github.com/philterd/philter/issues/35) - Updating cloud base images to Ubuntu 22.
* [#35](https://github.com/philterd/philter/issues/35) - Updating dependencies versions.
* Changed build and distribution system to Docker images.

## Version 2.4.0 - October 22, 2023

This version uses Phileas 2.4.0.

This version renames filter profiles to policies and adds a new date filter strategy that allows for shifting dates by random intervals.

* PHI-420 - Fix remaining company name mentions

## Version 2.3.0 - August 30, 2023

* PHI-396 - Change to philterd/phileas

## Version 2.2.1

* No issues found - see [Phileas release notes](https://github.com/philterd/phileas/blob/main/RELEASE_NOTES.md).

## Version 2.2.0 - June 14, 2023

* PHI-395 - Limit python dependencies in requirements.txt to just what's needed
* PHI-394 - Support the changes for Flair NLP PersonsV1 models in PHL-264

## Version 2.1.0 - November 30, 2022

* No issues found

## Version 2.0.0 - February 5, 2022

* PHI-386 - Integrate ONNX model

## Version 1.12.1 - December 26, 2021

* PHI-385 - Upgrade log4j to 2.17.0
* PHI-384 - Update Spring Boot to 2.6.2

## Version 1.12.0 - December 14, 2021

* PHI-383 - Upgrade log4j to 2.16.0
* PHI-382 - Remove philter-app-restricted from build
* PHI-381 - Change from gp3 to gp2 for IC Marketplace compatibility
* PHI-380 - Remove duplicate subnet_id from Packer file
* PHI-378 - Release Philter 1.12.0
* PHI-377 - Upgrade log4j to 2.15.0
* PHI-376 - Remove store get replacements from API
* PHI-373 - Set `elasticsearch.version` to 7.5.0
* PHI-372 - Update copyright date in README.txt
* PHI-231 - Add paragraph to license

## Version 1.11.0 - June 7, 2021

* PHI-371 - Release 1.11.0
* PHI-364 - Filter profile not found should return a 400 error
* PHI-357 - Pin torch version when releasing

## Version 1.10.1

* PHI-363 - Release Philter 1.10.1
* PHI-361 - Enable Prometheus metrics by default
* PHI-360 - Move properties files
* PHI-359 - Philter UI should support two-way SSL for client

## Version 1.10.0 - March 21, 2020

* PHI-358 - Philter UI should be available over HTTPS
* PHI-356 - Create script to configure GPU on p3 instances
* PHI-353 - Include the name of the model in the status API
* PHI-350 - Create debian package
* PHI-349 - Standardize cloud images on Ubuntu 20.04 LTS
* PHI-348 - Separate build from publish
* PHI-63 - Create a UI for Philter

## Version 1.9.0 - January 19, 2021

* PHI-344 - Test mutual TLS authentication
* PHI-340 - Add endpoint for PDFs returned as images
* PHI-339 - Move Azure packer to corporate Azure account
* PHI-338 - Update flair to 0.7.0
* PHI-336 - truncateDigits property of the zip code filter strategy needs documented
* PHI-332 - Enable mutual SSL two-way authentication
* PHI-331 - Update flair to 0.6.1
* PHI-214 - Update Azure image to CentOS 8

## Version 1.8.0 - November 1, 2020

* PHI-334 - Fix application properties in Docker container
* PHI-329 - Release Philter 1.8.0
* PHI-328 - Combine philter and philter-ner docker images
* PHI-325 - Add python library license checks to build

## Version 1.7.0

* PHI-324 - Update philter-ner container requirements
* PHI-323 - Reduce AMI EBS and GCP disk size to 8GB from 20GB
* PHI-317 - Update model path on S3 for packer scripts
* PHI-315 - Update base image to ubi 8.2
* PHI-314 - Update AWS AMI to ami-08f3d892de259504d
* PHI-313 - Train healthcare-covid19-2.1 model
* PHI-312 - Train healthcare-lite-2.1 model
* PHI-311 - Train healthcare-discharge-2.1 model
* PHI-310 - Train healthcare-2.1 model
* PHI-309 - Train general lite use-case general-2.1-lite model
* PHI-308 - Train general use-case general-2.1 model
* PHI-307 - Update flair to 0.5.1 and enable allow_long_sentences
* PHI-301 - Create packer builder for AWS SnowCone AMI

## Version 1.6.1.3 - August 30, 2020

* PHI-321 - Update GCP base image

## Version 1.6.1.2 - August 23, 2020

* PHI-320 - Update AWS base image

## Version 1.6.1.1 - July 28, 2020

* PHI-318 - Refresh GCP image as 1.6.1.1

## Version 1.6.0

* PHI-291 - Change philter-ner base container to UBI python 3.6
* PHI-290 - Use philter-base for containers
* PHI-289 - Create docker container tests
* PHI-286 - Create philter-app that requires a license key environment variable
* PHI-282 - Create alert framework
* PHI-281 - Revert * PHI-271 and go back to custom security
* PHI-280 - Use configuration framework
* PHI-278 - Add span disambiguation settings
* PHI-274 - AWS RHEL image has authorized keys
* PHI-270 - Move service initialization to Phileas
* PHI-260 - Integrate gold data set into philter-test

## Version 1.5.2 - May 19, 2020

* PHI-286 - Create philter-app that requires a license key environment variable
* PHI-283 - Validate Philter docker-compose for public distribution

## Version 1.5.1 - May 7, 2020

* PHI-274 - AWS RHEL image has authorized keys
* PHI-272 - Allow users to train their own models

## Version 1.5.0 - April 30, 2020

* PHI-269 - Rename the pre-compiled python file to not have a version number
* PHI-268 - Release Philter 1.5.0
* PHI-263 - Create guide to show how to use a real SSL certificate
* PHI-262 - Remove security.require-ssl property from the properties file
* PHI-258 - Update cache property names
* PHI-257 - Enable S3FilterProfileService based on the application properties
* PHI-256 - Create load-balanced Terraform template
* PHI-251 - Move philter-ner into philter
* PHI-250 - Parameterize the philter-ner model file name
* PHI-248 - Set application.properties permissions to just read/write for philter user
* PHI-247 - Throw error if auth is enabled but token is blank
* PHI-200 - Create gold data set for performance evaluation

## Version 1.4.0 - April 9, 2020

* PHI-249 - Submit Philter 1.4.0 to marketplaces
* PHI-245 - Add optional API authentication
* PHI-242 - Rename FilterApiController to PhilterApiController
* PHI-240 - Set 600 permissions on the .p12 generated file
* PHI-239 - Create Kubernetes deployment scripts
* PHI-238 - Install python dependencies using requirements.txt
* PHI-233 - Switch to Java 11
* PHI-227 - Add Azure RHEL build

## Version 1.3.1 - January 19, 2020

* PHI-234 - Allow client to set document ID
* PHI-233 - Switch to Java 11
* PHI-232 - Add REST endpoint for FHIR
* PHI-229 - Increase Azure CentOS disk to 30 GB
* PHI-228 - Add GCP RHEL build
* PHI-225 - Change to Red Hat Universal Base Image (UBI) for containers
* PHI-224 - Solidify Philter docker-compose scripts
* PHI-223 - Remove docker container from deployment
* PHI-222 - Update GCP image to CentOS 8
* PHI-220 - RHEL certification changes
* PHI-212 - Make packer builder for AWS RHEL image
* PHI-160 - Test on RHEL 8

## Version 1.3.0 - January 27, 2020

* PHI-220 - RHEL certification changes
* PHI-211 - Update philter-ner version to 1.2.0.74.a1c91a2
* PHI-209 - Set phileas version to 1.3.0
* PHI-208 - Improve performance by removing status checks for each request

## Version 1.2.0 - January 16, 2020

* PHI-206 - Show version in status API response
* PHI-205 - Release 1.2.0
* PHI-195 - Update to Phileas 1.2.0

## Version 1.1.0 - December 14, 2019

* PHI-188 - Only include third-party-notices in GCP image build
* PHI-187 - Don't use spring boot for dependency management
* PHI-185 - Test Elasticsearch over https
* PHI-181 - Apply filter profile registry API to Philter
* PHI-179 - Reduce philter-ner containers to 2
* PHI-177 - Set latest AMI in AWS SSM
* PHI-175 - Set AWS parameter to store latest image tag version
* PHI-174 - Update packer script and docker-compose to use specific philter-ner version
* PHI-173 - Make the philter-ner hostname an environment variable
* PHI-172 - Don't tag "latest" docker images
* PHI-164 - Create docker-compose file for philter and philter-ner
* PHI-158 - Add an explain feature to describe how the spans were found/removed
* PHI-155 - /api/filter should not return 500 when not initialized

## Version 1.0.1 - October 17, 2019

* PHI-170 - Create process to refresh GCP Marketplace images
* PHI-156 - Prepare GCP deployment
* PHI-154 - Change error message while Philter is initializing
* PHI-153 - Add license information to poms
* PHI-152 - Change name of philter base container
* PHI-151 - Include submit.sh script in bin/ to send text to Philter.
* PHI-150 - Remove unneeded dependencies
* PHI-149 - Package source of licenses Google requires
* PHI-148 - Including contents of licenses in distribution
* PHI-147 - /api/replacements should not return 500 when not enabled
* PHI-44 - Add Google Compute packer build

## Version 1.0.0 - October 6, 2019

Initial release.

* PHI-146 - Parameterize the philter-ner endpoint
* PHI-145 - Update license content when received from lawyer
* PHI-144 - philter-ner docker build needs to get model from repo
* PHI-143 - Make a default filter profile to ship with
* PHI-142 - Use a mock philter-ner webservice during JUnit tests
* PHI-131 - Allow Philter to be configured for the Filter Profile Registry
* PHI-97 - Train custom model
* PHI-29 - Locate suitable training corpus

## Pre-release Versions

* Version 0.9.3 - September 17, 2019
* Version 0.9.2 - May 6, 2019
* Version 0.9.1 - April 11, 2019
* Version 0.9.0 - April 3, 2019
