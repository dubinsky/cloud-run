## Overview ##

Gradle plugin to deploy Google Cloud Run services.

TODO service account key - why and where to get it
(and where to put it :)

TODO YAML

### Apply to a project ###

```groovy
plugins {
  id 'org.podval.tools.cloudrun' version '0.0.1'
}
```

### Configure ###

```groovy
cloudRun {
  region = 'us-east4'
  serviceAccountKeyProperty = 'gcloudServiceAccountKey'
  serviceYamlFilePath = "$getProjectDir/service.yaml"
}
```

#### Service Account Key Property ####

Parameter `serviceAccountKeyProperty` names the environment variable and Gradle
property that contains the JSON key for the service
account to be used for deployment.

When running locally, the key will be retrieved from a property
defined in `~/.gradle/gradle.properties`; when running in
Continuous Integration environment (e.g., GitHub Actions),
it is retrieved from a secret configured in that environment
and passed to the build in an environment variable with the same name;
it is this name that this parameter configures.
It defaults to `gcloudServiceAccountKey`.

To help configuring the Gradle property, plugin outputs the
(appropriately quoted and encoded) property file snippet
if this parameter is set to an absolute path to the file with the JSON
key. 

#### Region ####

Parameter `region` specifies which region the service is to be deployed in;
one of the Google Cloud Run regions must be supplied.

#### Path to the YAML file ####

Parameter `serviceYamlFilePath` is a path to a YAML file that describes the
service to be deployed; the file is in the
 `apiVersion: "serving.knative.dev/v1" - kind: "Service"` format;
see [the sample](./service.yaml) for the mapping between
`gcloud run deploy` options and the fields in the file.

This parameter defaults to `"$getProjectDir/service.yaml"` and needs to be
explicitly set only if a different path is desired. 

### Use ###

Plugin creates `cloudRunDeploy` task that sends a replace request to
Google Cloud Run. Note: if none of the parameters in the YAML file
changed and the container image was not updated since the latest
revision was created, Google Cloud Run will (correctly) not create a new revision.

It also creates two help tasks that retrieve the YAML for the service and
its latest revision respectively: `cloudRunGetServiceYaml` and `cloudRunGetLatestRevisionYaml`.

## Motivation ##

TODO

## Differences from gcloud run ##

TODO

deploy.doLast {
  exec {
    standardInput new ByteArrayInputStream(gcloudServiceAccountKey.getBytes('UTF-8'))
    commandLine 'gcloud', 'auth', 'activate-service-account', '--key-file', '-'
  }

  exec {
    commandLine 'gcloud', 'beta', 'run', 'services', 'replace', "$projectDir/service.yaml"
  }
}


## Technical notes ##

