[![Maven Central](https://img.shields.io/maven-central/v/org.podval.tools/org.podval.tools.cloudrun.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22org.podval.tools%22%20AND%20a:%22org.podval.tools.cloudrun%22)

## Introduction ##

Gradle plugin to deploy a service described in a [knative](https://knative.dev/)
  [Service](https://knative.dev/docs/serving/spec/knative-api-specification-1.0/#service-2)
  [YAML](https://knative.dev/docs/reference/api/serving-api/) file
to [Google Cloud Run](https://cloud.google.com/run)
from the local machine or from CI like [GitHub Actions](https://docs.github.com/en/actions) -
or to run it in the local [Docker](https://docs.docker.com/).

Plugin is opinionated:
- the *only* supported method of configuring the service is the YAML file;
- the *only* supported method of authentication with the Google Cloud Platform
  is the [Service Account](https://cloud.google.com/iam/docs/service-accounts);
- plugin integrates nicely with the
  [JIB Gradle plugin](https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin),
  so using JIB is recommended (but not required).
  
### Apply to a project ###

```groovy
plugins {
  id 'com.google.cloud.tools.jib' version '2.8.0' // optional; see `JIB Plugin` section below
  id 'org.podval.tools.cloudrun' version '0.2.0'
}
```

### Service YAML file ###

Put the following into the `service.yaml` in your project:
```yaml
apiVersion: "serving.knative.dev/v1"
kind: "Service"
metadata:
  name: "<service-name>"
spec:
  template:
    spec:
      containers:
      - image: "gcr.io/<project-id>/<service-name>"
        resources:
          limits:
            cpu: "1000m"
            memory: "512Mi"
```

Configurable parameters are listed in the `service.yaml` section below.

### Deploy from local machine ###

You can use the task `cloudRunDeploy` created by the plugin to deploy new revision of the
service to Google Cloud Run from your local machine:
```shell
$ ./gradlew cloudRunDeploy
```

### Deploy from CI ###

Put your service account key into a secret `gcloudServiceAccountKeySecret`
in the GitHub repository or organization, and you can use the following GitHub Actions workflow
step to deploy from the CI environment:
```yaml
- name: "Build and push the image and deploy Cloud Run service"
  env:
    gcloudServiceAccountKey: ${{secrets.gcloudServiceAccountKeySecret}}
  run: ./gradlew cloudRunDeploy
```

### Run locally ###

You can use the task `cloudRunLocal` to run the service with the same parameters,
resource limits etc. in the local Docker:
```shell
$ ./gradlew cloudRunLocal
```

Additional Docker options can be configured using `additionalOptions` property on
that task:
```groovy
cloudRunLocal.additionalOptions = [
  '--mount', 'type=bind,source=...,target=/mnt/store/'
]
```

For multiple local configurations with different `additionalOptions`,
additional tasks of type `org.podval.tools.cloudrun.CloudRunPlugin.RunLocalTask`
can be added to the Gradle project.

### JIB plugin ###

If your Gradle project uses JIB to build and push the image (and why wouldn't it?),
CloudRun plugin configures some values on the`jib` extension (if you did not  configure them):
- `jib.to.image` is set to the image name from the service YAML file;
- `jib.to.auth.password` is set to the service account key;
- `jib.to.auth.username` is set to `_json_key`, telling JIB to use service account key.

In addition:
- `cloudRunDeploy` task is configured to depend on the `jib` task;
- `cloudRunLocal` task is configured to depend on the `jibDockerBuild` task.

Note: any additional task of type `org.podval.tools.cloudrun.CloudRunPlugin.RunLocalTask`
is configured with the parameters from the Service YAML file,
and automatically depends on the`jibDockerBuild` task. 

Using JIB plugin is recommended but not required; if you build and push your
images by other means, it is your responsibility to arrange the Gradle build file
in such a way that:
- the image is built and pushed to the Google Container Registry
before the `cloudRunDeploy` task runs;
- the image is built and pushed to the local Docker before `cloudRunLocal` task runs.

### YAML retrieval ###

Plugin creates two help tasks that retrieve the YAML for the service and
its latest revision respectively:
- `cloudRunGetServiceYaml` is similar to
  `gcloud run services describe $serviceName --format export`
- `cloudRunGetLatestRevisionYaml` is similar to
  `gcloud run services describe $serviceName --format export` (if you know the name of the
  latest revision of your service :))

## Configuration ##

Plugin creates `cloudRun` extension that can be used to configure it via `build.gradle` file:
```groovy
cloudRun {
  region = 'us-east4'                                     // required
  serviceYamlFilePath = "$getProjectDir/service.yaml"     // optional
  serviceAccountKeyProperty = 'gcloudServiceAccountKey'   // optional
}
```

If you use default property name for the key (`gcloudServiceAccountKey`), and the YAML
file for your service is in the file `service.yaml` in your Gradle project,
the only thing you need to configure in your Gradle build file is the region to deploy in,
for example:
```groovy
cloudRun.region = 'us-east4'
```

### Region ###

Parameter `region` specifies which region the service is to be deployed in;
one of the Google Cloud Run regions *must* be supplied.

### Path to the YAML file ###

Parameter `serviceYamlFilePath` is a path to a YAML file that describes the
service; see `service.yaml` section below for the mapping between
`gcloud run deploy` options and the fields in the file.

This parameter defaults to `"$getProjectDir/service.yaml"` and needs to be
explicitly set only if a different path is desired.

### Service Account Key Property ###

Parameter `serviceAccountKeyProperty` names the environment variable and Gradle
property that contains the JSON key for the service
account to be used for deployment.

When deploying to Google Cloud Run from the local machine,
the key is retrieved from a property
defined in `~/.gradle/gradle.properties`.

When running in Continuous Integration environment (e.g., GitHub Actions),
it is retrieved from a secret configured in that environment
and passed to the workflow steps that need it in an environment variable
with the same name. Since the key itself is retrieved only when needed,
steps that do not have the key available will work - unless they involve
tasks that require it.

Itt is this *name* that this parameter configures.
It defaults to `gcloudServiceAccountKey` and needs to be explicitly set
only if a different name is desired.

To help configure the Gradle property,
if this parameter is set to an absolute path to the file with the JSON key,
plugin outputs the (appropriately quoted and encoded) property file snippet
that can be added to `~/.gradle/gradle.properties`.

## Motivation ##

### Deploy from local machine ###

Let's say I am developing a Google Cloud Run service that I build and push to
Google Container Registry using JIB's Gradle plugin. To deploy the service,
I use `gcloud` CLI (which, since I work with Google Cloud, I of course have
installed on my machine) and not the Google Cloud Platform UI, because I want my
deployments to be reproducible. (One-time things like setting the service to be
available without authentication is done using the UI,
since using `gcloud --allow-unauthenticated` 
for this is [not](https://github.com/google-github-actions/deploy-cloudrun#Allow-unauthenticated-requests)
[recommended](https://github.com/google-github-actions/deploy-cloudrun/blob/main/src/cloudRun.ts#L275).)
Since I want my deployments to be reproducible, I want the `gcloud` commands to be
recorded in my sources.

This leads to something like this in the Gradle build file:
```groovy
final String gcloudService = '...'
final String serviceImage  = "gcr.io/<gcloud-project>/$gcloudService"

final String gcloudServiceAccountProperty = 'gcloudServiceAccountKey'
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
    mainClass = '...'
  }
}

cloudRunDeploy.dependsOn('jib')
cloudRunDeploy.doLast {
  exec {
    standardInput new ByteArrayInputStream(gcloudServiceAccountKey.getBytes('UTF-8'))
    commandLine 'gcloud', 'auth', 'activate-service-account', '--key-file', '-'
  }

  exec {
    commandLine 'gcloud', 'beta', 'run', 'deploy', gcloudService,
       '--image', serviceImage,
       '--platform', 'managed',
       '--region', 'us-east4',
       '--min-instances', '1',
       '--max-instances', '2'
  }
}
```

Now I can deploy with:
```shell
$ ./gradlew cloudRunDeploy
```
Note: `serviceImage` and `gcloudServiceAccountKey` are shared between `jib` and `gcloud`.

Note: `gcloud beta` must be used while `--min-instances` is in beta.

Note: I didn't find a way to run `gcloud` using service account without it becoming
the current default, so to run other `gcoloud` commands as my main account, I need
to remember to switch to it first. This is inconvenient and annoying, but workable.


### Deploy from CI ###

Of course, I also want to be able to build and push the image and deploy the service
from the CI run, in my case - GitHub Actions workflow.
Thankfully, there is an action
[google-github-actions/setup-gcloud](https://github.com/google-github-actions/setup-gcloud)
that can be used to install `gcloud` on the CI machine!
This leads to something like this in the `.github/workflows/CI.yaml` file:
```yaml
- name: "Build and push image to Google Container Registry"
  env:
    gcloudServiceAccountKey: ${{secrets.gcloudServiceAccountKeySecret}}
  run: ./gradlew jib

- name: "Set up gcloud CLI"
  uses: GoogleCloudPlatform/github-actions/setup-gcloud@master
  with:
    project_id: "<project-id>"
    service_account_key: ${{secrets.gcloudServiceAccountKeySecret}}

- name: "Deploy Cloud Run service"
  run: |
    gcloud beta run deploy <service-name> \
    --quiet \
    --image "gcr.io/<project-id>/<service-name>" \
    --platform managed \
    --region us-east4 \
    --min-instances 1 \
    --max-instances 2
```

And here is the *real* problem: service configuration is now duplicated;
one copy - in the `build.gradle` file - is used for deployment from the
local machine, while another is used from the CI.

Note: I didn't try to simplify the above to:
```yaml
- name: "Set up gcloud CLI"
  uses: GoogleCloudPlatform/github-actions/setup-gcloud@master
  with:
    project_id: "<project-id>"
    service_account_key: ${{secrets.gcloudServiceAccountKeySecret}}

- name: "Build and push image and deploy Cloud Run service"
  env:
    gcloudServiceAccountKey: ${{secrets.gcloudServiceAccountKeySecret}}
  run: ./gradlew cloudRunDeploy
```
If that actually worked, I might have stopped right there, since
there is no more configuration duplication - and CloudRun plugin
would not have been written ;)

### Using YAML ###

It would be nice if a format existed that:
- describes configuration of the service;
- is understood by `gcloud` and
- is understood by a GitHub Action.

Then, configuration duplication could be avoided.

Turns out, such a format *does* exist: it is [knative](https://knative.dev/)
[Service](https://knative.dev/docs/serving/spec/knative-api-specification-1.0/#service-2)
[YAML](https://knative.dev/docs/reference/api/serving-api/) format!

This is how it looks like:
```yaml
apiVersion: "serving.knative.dev/v1"
kind: "Service"
metadata:
  name: "<service-name>"
spec:
  template:
    metadata:
      annotations:
        autoscaling.knative.dev/minScale: "1"
        autoscaling.knative.dev/maxScale: "2"
    spec:
      containers:
      - image: "gcr.io/<project-id>/<service-name>"
        resources:
          limits:
            cpu: "1000m"
            memory: "512Mi"
```

To use it in `gcloud`, instead of `run deploy` command one uses `run services replace`;
to use it in GitHub workflow, one switches to a sibling
action: [google-github-actions/deploy-cloudrun](https://github.com/google-github-actions/deploy-cloudrun).

Assuming the YAML describing the service is in the `service.yaml` file,
configuration in `build.gradle` looks like this:
```groovy
final String gcloudService = '...'
final String serviceImage  = "gcr.io/<gcloud-project>/$gcloudService"

final String gcloudServiceAccountProperty = 'gcloudServiceAccountKey'
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
    mainClass = '...'
  }
}

cloudRunDeploy.dependsOn('jib')
cloudRunDeploy.doLast {
  exec {
    standardInput new ByteArrayInputStream(gcloudServiceAccountKey.getBytes('UTF-8'))
    commandLine 'gcloud', 'auth', 'activate-service-account', '--key-file', '-'
  }

  exec {
    commandLine 'gcloud', 'beta', 'run', 'services', 'replace', "$projectDir/service.yaml"
  }
}
```

And GitHub workflow simplifies to:
```yaml
- name: "Build and push image to Google Container Registry"
  env:
    gcloudServiceAccountKey: ${{secrets.gcloudServiceAccountKeySecret}}
  run: ./gradlew --no-daemon jib

- name: "Deploy Cloud Run service"
  uses: google-github-actions/deploy-cloudrun@main
  with:
    credentials: ${{secrets.gcloudServiceAccountKeySecret}}
    metadata: "./service.yaml"
    region: "us-east4"
```

But there is a problem: if none of the parameters in the YAML file
changed, Google Cloud Run will (correctly) not create a new revision -
even though a new image was pushed by the build. This is because
both `gcloud run services replace` and the `deploy-cloudrun` action use
declarative knative API that makes sure that all the parameters of the service
(including the image name) are as described in the YAML file.

The irony is that this API is the *only* way to interact with Cloud Run,
and that is what `gcloud`, all the actions, and Google Cloud Platform UI use.
How is it then that they manage to force creation of a new revision?
Turns out, the tools that do that add explicit revision name to the YAML they
submit, thus creating a situation where the state of affairs described in the
submitted YAML *is* different from the existing one, and that triggers creation
of a new revision.

Note: I did not find any options to force creation of a new revision while
using YAML file; if I did, both for `gcloud` and the `deploy-cloudrun` action,
I might have stopped right there - and this plugin
would not have been written ;)

This is where I decided to write a little something that uses the same API,
reads parameters from a YAML file, and adds whatever needs to be added to force
creation of a new revision. As a result, there is now a way to run the service
locally without yet another configuration duplicaton:

### Run locally ###

To debug and tune memory and CPU constraints, the service needs to be run locally.

To run locally in the environment equivalent to Google Cloud Run, I need to install:
- [minikube](https://minikube.sigs.k8s.io/docs/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl/)
- [knative](https://knative.dev/docs/install/any-kubernetes-cluster/)
- [istioctl](https://istio.io/latest/docs/setup/install/istioctl/)
- [Istio](https://knative.dev/docs/install/installing-istio/)
- [skaffold](https://skaffold.dev/) (probably)

Google CloudCode plugin for IntelliJ Idea promises to do all that, but does not recognize
my project as built with JIB (does not make JIB builder available for my project in
`Cloud Code | Cloud Run | Run Locally` run configuration).

It is much simpler to run the image locally in plain Docker.
To avoid yet another configuration duplication, CloudRun plugin creates a task -
`cloudRunLocal` -
that uses JIB to build the image to the local Docker and then runs it with the parameters
from the YAML file.

To allow for additional configuration of the local run (e.g., mounting local volumes),
`cloudRunLocal` task exposes `additionalOptions` property, which can be used like this:
```groovy
cloudRunLocal.additionalOptions = [
  '--mount', 'type=bind,source=...,target=/mnt/store/',
  '--env'  , 'STORE=file:///mnt/store/'
]
```

With the CloudRun plugin thus enhanced, I can now:
- deploy the service from the local machine;
- deploy the service from CI;
- run the service locally
without configuration duplication; see `Introduction` section above.

## service.yaml ##

Structure of the [Service YAML file](https://knative.dev/docs/reference/api/serving-api/)
and equivalents for `gcloud` and `docker` commands:

```yaml
apiVersion: "serving.knative.dev/v1"                 # gcloud run deploy         docker run
kind: "Service"
metadata:
  name: ...                                          # SERVICE parameter         --name
  labels:
    LABEL: 'VALUE'                                   # --labels                  --label
  annotations:
    run.googleapis.com/launch-stage: "BETA"          # gcloud beta run deploy
spec:
  template:
    metadata:
      annotations:
        autoscaling.knative.dev/minScale: "1"        # --min-instances
        autoscaling.knative.dev/maxScale: "91"       # --max-instances
        run.googleapis.com/vpc-access-connector: ... # --vpc-connector
    spec:
      serviceAccountName: ....                       # --service-account
      containerConcurrency: 80                       # --concurrency
      timeoutSeconds: 300                            # --timeout
        containers:
        - image: ...                                 # --image                   IMAGE parameter
          command:                                   # --command                 COMMAND parameter
            - "command"
          args:                                      # --args                    ARGS parameter
            - "arg1"
            - "arg2"
          env:                                       # --set-env-vars            --env
            - name: "environmentVariable1Name"
              value: "environmentVariable1Value"
            - name: "environmentVariable2Name"
              value: "environmentVariable2Value"
          ports:
            - containerPort: 8080                    # --port                    PORT env var
          resources:
            limits:
              cpu: "1000m"                           # --cpu                     --cpus
              memory: "256Mi"                        # --memory                  --memory
```

Documentation on what can be configured and how: https://cloud.google.com/run/docs/how-to#configure

`gcloud` options without YAML file analogues or not supported on fully managed Cloud Run:
- --cluster
- --cluster-location
- --context
- --kubeconfig
- --namespace
- --platform
- --no-traffic
- --set-cloudsql-instances
- --set-config-maps
- --set-secrets
- --connectivity
- --[no-]use-http2

## Notes ##

### Configuring JIB ###

JIB Gradle plugin has to be configured "lazily" by the CloudRun plugin.
I thought that for this to be possible, JIB extension's `username` and `password`
need to be Gradle Properties, but at the time they were plain Strings.
My pull request to fix that was
[graciously accepted](https://github.com/GoogleContainerTools/jib/pull/2906)
for JIB 2.7.0. Thanks!

In the end, CloudRun plugin uses `project.afterEvaluate()` block for this,
but if it turns out that this approach works only if `jib` and `cloudrun`
plugins are applied in specific order, I'll switch to using Properties.

### Sources of inspiration ###

During writing of the plugin, I consulted the (Python) sources of the
[Google Cloud SDK](https://github.com/twistedpair/google-cloud-sdk),
for example [command_lib/run](https://github.com/twistedpair/google-cloud-sdk/tree/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run)
and [command_lib/serverless](https://github.com/twistedpair/google-cloud-sdk/tree/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/serverless),
and TypeScript sources of the GitHub Action [deploy-cloudrun](https://github.com/google-github-actions/deploy-cloudrun),
for example [service.ts](https://github.com/google-github-actions/deploy-cloudrun/blob/main/src/service.ts).


### YAML mangling ###

GitHub Action `deploy-cloudrun`
[merges](https://github.com/google-github-actions/deploy-cloudrun/blob/main/src/service.ts#L138)
parameters from the YAML file with those of the current revision of the service.
From the `gcloud` code it seems that it performs similar (although not quite the same)
merge.

I think this is done to support workflow where the service is sometimes deployed
by other means (`gloud`, GCP UI) and some parameters (e.g., environment variables) are set
during such deployment. CloudRun plugin treats the YAML file as the source of truth,
and resets all the parameters accordingly; existing environment variables etc. are *not* retained.

When generating revision name, the plugin follows the `gcloud` and GCP UI pattern:
```
<service name>-<generation (5 digits)>-<random suffix (consonat-vowel-consonant)>
```
(which the GitHub Action does not).

Plugin adds annotations to the YAML it submits that identify it as the
deploying client and specify the version used.

### Possible Enhancements ###

- document (and, if required, support) additional Google Cloud Run-specific features
  that are expressable in the YAML file using Google-specific annotations and such;
- support platforms other than just CloudRun Fully managed;
- generate `gcloud` command line equivalent from `service.yaml`;
