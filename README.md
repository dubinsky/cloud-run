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
  serviceAccountKey =
    findProperty('gcloudServiceAccountKey') ?:
    System.getenv('gcloudServiceAccountKey')
  region = 'us-east4'
  serviceYamlFilePath = "$getProjectDir/service.yaml"
}
```

#### Service Account Key ####

Parameter `serviceAccountKey` contains the JSON key for the service
account to be used for deployment. It must be configured.

When running locally, this normally is a value of a property
defined in `~/.gradle/gradle.properties`; when running in
Continuous Integration environment (e.g., GitHub Actions),
it is retrieved from a secret configured in that environment
and passed to the build in an environment variable.
Example above shows how this parameter can be configured
for both local and CI environments (with `gcloudServiceAccountKey`
assumed to be the name of both the Gradle property and the environment
variable supplied by the CI environment).

To help setting the Gradle property, plugin outputs the
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
## Instructions ##
## Differences from gcloud run ##
## Technical notes ##

## TODO ##
- instead of the key, configure the name of the property; default it;
  expose the key on the extension;
- get Bintray approval
- add Gradle Plugin Portal setup
- get Gradle Plugin Portal approval
- get the log of the replace request
- file an issue with JIB to make image name a property
