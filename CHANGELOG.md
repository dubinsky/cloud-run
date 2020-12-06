# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.2] - 2020-12-05
- deploy logic even for a new service;
- track status of Configuration too (got nothing from it though);
- track status of all polled resources in parallel;
- updated google-auth-library-oauth2-http;
- updated google-api-services-run;
- updated JIB;
- simplified JIB configuration: username and password are now settable from a Provider;
- cleanup;

## [0.1.1] - 2020-12-04
- put version and description into the JAR manifest during build;
- retrieve the version from the JAR manifest at runtime;
- status tracker displays only the messages;
- listing and getting of Routes;
- multi-stage status tracking;

## [0.1.0] - 2020-12-03
- added application version to the artifact;
- add application name/version annotations at deploy;
- add three-letter suffix to the revision name;
- added StatusTracker;
- added CloudRun.logger;
- moved deploy() into CloudRunService;

## [0.0.9] - 2020-12-03
- force redeployment by specifying revision name;
- cleanup;

## [0.0.8] - 2020-12-01
- removed the use of BeanProperty annotation;
- cleanup;

## [0.0.7] - 2020-11-30
- set jib.to.auth.password only when jib task is about to execute:
  in CI environment key property may not be defined during a non-deploy build;
  
## [0.0.6] - 2020-11-30
- JIB auto-configuration (jib.to.image, jib.to.auth.username, jib.to.auth.password);
- cloudRunDeploy.dependsOn('jib');

## [0.0.5] - 2020-11-30
- made properties exposed by the 'cloudRun' extension lazy to avoid plugin application order issues;
- working on configuring 'jib' extension;

## [0.0.4] - 2020-11-30
- enabled Gradle Plugin Portal deployment;
- working on the JIB integration for the key;

## [0.0.3] - 2020-11-29
- name of the key property is configured instead of the key itself;

## [0.0.2] - 2020-11-29
- make compatible with JIB;
- configure publishing to the Gradle Plugin Portal;
- convert key file into property in the plugin;
- beginning of the documentation
- cleanup;

## [0.0.1] - 2020-11-29
- initial working version
