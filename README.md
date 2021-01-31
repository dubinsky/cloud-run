## Overview ##

Gradle plugin to deploy Google Cloud Run service
- described in a [knative](https://knative.dev/)
  [Service](https://knative.dev/docs/serving/spec/knative-api-specification-1.0/#service-2)
  YAML file
- using Google Cloud [Service Accout](https://cloud.google.com/iam/docs/service-accounts)  


Minimal version of JIB supported: 2.7.0.

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

// equivalent to `gcloud run services describe $serviceName --format export`

TODO !!! image changes, but re-deploy is not happening?!

## Motivation ##

TODO

## With JIB ##

TODO

https://github.com/dubinsky/cloud-run/issues/6

read-only values lazily exported by the extension
Note: lazy values are used instead of project.afterEvaluate() to provide more laziness,
so that JIB and CloudRun plugins do not have to be applied in specific order.

Old:
```groovy
final String gcloudProject = 'alter-rebbe-2'
final String gcloudService = 'collector'
final String serviceImage  = "gcr.io/$gcloudProject/$gcloudService"
final String mainClassName = 'org.opentorah.collector.Service'

final String gcloudServiceAccountProperty = 'gcloudServiceAccountKey_' + gcloudProject.replace('-', '_')
final String gcloudServiceAccountKey      = findProperty(gcloudServiceAccountProperty) ?: System.getenv(gcloudServiceAccountProperty)

jib {
  to {
    auth {
      username = '_json_key'
      password = gcloudServiceAccountKey
    }
    image = serviceImage
  }
  container {
    mainClass = mainClassName
  }
}

deploy.dependsOn('jib')
deploy.doLast {
  // TODO maybe --impersonate-service-account instead?
  exec {
    standardInput new ByteArrayInputStream(gcloudServiceAccountKey.getBytes('UTF-8'))
    commandLine 'gcloud', 'auth', 'activate-service-account', '--key-file', '-'
  }

  exec {
    commandLine 'gcloud', 'beta', 'run', 'services', 'replace', "$projectDir/service.yaml"
  }
}
```

New with autoconfiguration:
```groovy
jib.container.mainClass = 'org.opentorah.collector.Service'
cloudRun.region = 'us-east4'
deploy.dependsOn(cloudRunDeploy)
```

## GitHub Actions ##

Old:
```yaml
- name: "Build and push image to Google Container Registry"
  env:
    gcloudServiceAccountKey: ${{secrets.gcloudServiceAccountKey}}
  run: ./gradlew --no-daemon jib

- name: "Deploy Cloud Run service"
  uses: google-github-actions/deploy-cloudrun@main
  with:
    credentials: ${{secrets.gcloudServiceAccountKey}}
    metadata: "./collector/service.yaml"
    region: "us-east4"
```

New:
```yaml
- name: "Build and push container image to Google Container Registry and deploy Cloud Run service"
  env:
    gcloudServiceAccountKey: ${{secrets.gcloudServiceAccountKey}}
    jib.console: "plain"
  run: ./gradlew --no-daemon deploy
```

  // used by the GitHub Action to determine if the service exists; I do it differently.
  def exists(serviceName: String): Boolean =
    listServices.map(CloudRun.getServiceName).contains(serviceName)


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

// inspired in part by the deploy-cloudrun GitHub Action
//   see https://github.com/google-github-actions/deploy-cloudrun

// authentication - see
//   https://github.com/googleapis/google-auth-library-java#google-auth-library-oauth2-http
// (what is ServiceAccountJwtAccessCredentials.fromStream(keyStream) for?)
// using credentials with Google's HTTP clients - see
//   https://github.com/googleapis/google-auth-library-java#using-credentials-with-google-http-client

// from the replaceService() JavaDoc:
//  Only the spec and metadata labels and annotations are modifiable.
// see https://github.com/google-github-actions/deploy-cloudrun/blob/e6563531efecd65332243ad924e3dcf72681c41a/src/service.ts#L138
// GitHub Action merge 'previous' into 'service':
//   spec.template.metadata.labels;
//   spec.template.metadata.annotations;
//   spec.template.spec.containers[0]:
//     command and args are removed if not present in 'service'
//     in env, variables present in 'previous' but not in 'service' are added;

// Note: when parsing Service YAML, objectMapper.readValue(inputStream, classOf[Service]) throws
//   java.lang.IllegalArgumentException:
//   Can not set com.google.api.services.run.v1.model.ObjectMeta field
//   com.google.api.services.run.v1.model.Service.metadata to java.util.LinkedHashMap
// so I convert YAML into a JSON string and then parse it using Google's parser:

Include annotated service.xml here!

[Google Cloud Java Client Libraries](https://github.com/googleapis/google-cloud-java)

[Google Cloud SDK](https://github.com/twistedpair/google-cloud-sdk)

[gcloud run](https://github.com/twistedpair/google-cloud-sdk/tree/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run)
       [and](https://github.com/twistedpair/google-cloud-sdk/tree/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/serverless)

deploy.doLast {
  exec {
    standardInput new ByteArrayInputStream(gcloudServiceAccountKey.getBytes('UTF-8'))
    commandLine 'gcloud', 'auth', 'activate-service-account', '--key-file', '-'
  }
}

// --min-instances is in beta, so I have to use 'gcloud beta':
exec { commandLine(
'gcloud', 'beta',
'run', 'deploy'  , 'collector',
'--image'        , 'gcr.io/alter-rebbe-2/collector',
'--platform'     , 'managed',
'--region'       , 'us-east4',
'--min-instances', 1,
'--allow-unauthenticated'
)}
}

gcloud beta services ...

    // Note: I have no idea why gcloud run deploy adds the three-letter suffix tothe revision name,
    // but it doe - and so do I...


Another advantage of configuring property name instead of the key itself is: in CI environment,
the key is normally supplied (via an environment variable) only to the steps that need it;
by retrieving the key only when it is needed, we avoid...