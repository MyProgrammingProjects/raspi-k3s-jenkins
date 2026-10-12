# Jenkins on Kubernetes with Ephemeral ARM64 Build Agents

## Architecture Overview

This repository documents the setup of Jenkins running on a Kubernetes cluster built with Raspberry Pi nodes, with the goal of exploring cloud-native CI/CD execution patterns, using Kubernetes-native infrastructure, while adapting Jenkins into that environment, aiming for GitOps-oriented delivery workflows in a constrained ARM64 hardware.

The architecture is centered around ephemeral Kubernetes-based Jenkins agents, containerized workloads, where each pipeline execution dynamically creates isolated pods responsible for building and publishing container images.

The current setup includes:

- Custom Helm chart instead of the official Jenkins chart
- Jenkins controller running inside Kubernetes
- Kubernetes ephemeral agents
- Podman for container image builds
- Azure Container Registry (ACR) as image registry
- Shared volumes between containers inside the agent pod
- GitOps-oriented deployment strategy
- Argo CD integration

The goal of the project is not simply "running Jenkins", but understanding the mechanics behind:
- Kubernetes scheduling
- CI agent orchestration
- Helm templating
- GitOps workflows
- ARM64 containerized builds
- Build isolation strategies

---

## Why Jenkins

At the time I'm writing this, I currently work with GitHub Actions. Previously I also worked with Azure DevOps (Classic and YAML pipelines). For this project I intentionally chose Jenkins.

The goal was not selecting the most modern or easiest CI platform. The goal was understanding how CI systems work internally.

Jenkins is a flexible CI platform, particularly when:
- pipelines require full execution control,
- custom infrastructure is involved,
- or Kubernetes-native execution models are required.

This project also became an opportunity to:
- understand Jenkins Kubernetes agents,
- explore pipeline composition,
- learn Groovy-based pipeline execution,
- and evaluate the operational tradeoffs of self-hosted CI.

Another important factor was reducing dependency on opaque third-party pipeline abstractions. Instead of heavily relying on plugins for every operation, the pipelines intentionally favor explicit shell commands and direct tooling usage.

This increases maintenance responsibility, but also:
- improves transparency,
- reduces hidden behavior,
- and simplifies troubleshooting.

---

## Helm Chart Design

Instead of using the official Jenkins Helm chart, I decided to create a custom chart from scratch.

The purpose was educational:
- understanding Helm templating,
- Kubernetes resource relationships,
- PVC/PV binding,
- service exposure,
- RBAC,
- node selectors,
- and deployment composition.

The chart, which can be found [here](https://github.com/MyProgrammingProjects/raspi-k3s-jenkins/tree/main/custom-chart), currently manages:
- PersistentVolume
- PersistentVolumeClaim
- Service
- Deployment
- Service Account
- RBAC resources

Some important implementation decisions:
- Jenkins data persisted using `local-path` storage class
- Dedicated Kubernetes namespace for Jenkins
- Dedicated `tools` node for CI workloads
- Node affinity through `nodeSelector` in the deployment and `nodeAffinity` in the persistent volume
- Init container for fixing volume permissions
- Explicit resource requests and limits

One important lesson learned during implementation was the relationship between:
- `PersistentVolume`
- `PersistentVolumeClaim`
- `storageClassName`

Using two strategies for node affinity (`nodeSelector` and `nodeAffinity`) was also relevant regarding Kubernetes scheduling behavior.

This project intentionally keeps the chart relatively explicit instead of abstracting every detail into helper templates.

The goal is readability and understanding rather than maximum chart reusability.



---

## Kubernetes Agent Architecture

One of the most important parts of this project is the pipeline execution model.

Instead of static Jenkins agents, pipelines execute inside ephemeral Kubernetes pods created dynamically during pipeline execution. The agent's definition is set at the beginning of the pipeline definition.

Taking into context the cluster nodes distribution and their responsibilities (see [`Ansible` repository](https://github.com/MyProgrammingProjects/raspi-k3s-ansible), the agent's specification takes into consideration, via `nodeSelector`, that all agents must run on a specific node.

My initial version of the pipelines, the flow of stages was as depicted below, where the pipelines did not had vulnerabilities check stages, neither for the compiled application nor the resulting docker images.


```txt
Checkout
  │
  ▼
Login to ACR
  │
  ▼
Build & Push Image
  │
  ▼
Checkout Infrastructure Repository
  │
  ▼
Adjust Image Name
  │
  ▼
Update values.yaml in Infrastructure Repository
  │
  ▼
Post Actions
```

The pipeline was updated to introduce Trivy vulnerability scanning at two stages: after building the .NET application and after building the container image.

To prevent images with high- or critical-severity vulnerabilities from being published, the pipeline separates image building from image publication. Trivy scans the built image before the pipeline proceeds to the push stage.

This change required revisiting the previous BuildKit-based workflow, which combined image building and publication. The new workflow uses Podman to build the image and save it as an Docker archive. The archive is stored in an emptyDir volume shared between containers in the ephemeral Jenkins agent pod.

Trivy scans this archive before Podman publishes the image to Azure Container Registry (ACR). Because the archive is stored in an ephemeral volume, it is removed when the agent pod is terminated.

As such, the new pipeline stages are the following:

```txt
Checkout
  │
  ▼
Build Application
  │
  ▼
Check Application Vulnerabilities
  │
  ▼
Build Image
  │
  ▼
Check Image Vulnerabilities
  │
  ▼
Login to ACR
  │
  ▼
Push Image
  │
  ▼
Checkout Infrastructure Repository
  │
  ▼
Adjust Image Name
  │
  ▼
Update values.yaml in Infrastructure Repository
  │
  ▼
Post Actions
```




```groovy

pipeline {
    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  nodeSelector: 
    kubernetes.io/hostname: tools
  volumes:
  - name: auth-data
    emptyDir: {}
  - name: app-build-volume
    emptyDir: {}
  - name: image-volume
    emptyDir: {}
  containers:
  - name: podman-builder
    image: quay.io/podman/stable
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    volumeMounts:
      - name: auth-data
        mountPath: /home/user/acr
      - name: image-volume
        mountPath: /image-archive
    command:
    - sh
    - -c
    - cat
    tty: true

  - name: azure-cli
    image: mcr.microsoft.com/azure-cli:azurelinux3.0-arm64@sha256:e1ea504fb79e4348935072055898f675367b9bade81608c4b51835e232b913fe
    imagePullPolicy: IfNotPresent
    volumeMounts:
    - name: auth-data
      mountPath: /root/acr
    command:
    - sh
    - -c
    - cat
    tty: true

  - name: app-build-container
    image: mcr.microsoft.com/dotnet/sdk:10.0-azurelinux3.0-arm64@sha256:7db078926447807991fce6040e9f11df8a8a114c6e2d912e7012dade5d283ee1
    imagePullPolicy: IfNotPresent
    volumeMounts:
      - name: app-build-volume
        mountPath: /app
    command:
    - sh
    - -c
    - cat
    tty: true

  - name: trivy
    image: docker.io/aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa
    imagePullPolicy: IfNotPresent
    volumeMounts:
      - name: app-build-volume
        mountPath: /app
      - name: image-volume
        mountPath: /image-archive
    command:
    - sh
    - -c
    - cat
    tty: true

  - name: auxiliary
    image: alpine:3.22.4@sha256:310c62b5e7ca5b08167e4384c68db0fd2905dd9c7493756d356e893909057601
    imagePullPolicy: IfNotPresent
    command:
    - sh
    - -c
    - |
      apk add yq git &&
      cat
    tty: true
'''
        }
    }

(... rest of pipeline: stages and steps ...)

}
```

As comparison, previous agent's pod implementations was like this:

```groovy

pipeline {
    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  nodeSelector: 
    kubernetes.io/hostname: tools
  volumes:
  - name: auth-data
    emptyDir: {}
  containers:
  - name: buildkit
    image: moby/buildkit:v0.29.0@sha256:a678f07cfe6d0c03c721810e28fc628df91a2f8d2bb93dff353647e70920fbee
    securityContext:
      privileged: true
    volumeMounts:
      - name: auth-data
        mountPath: /home/user/.docker
    command:
      - sh
      - -c
      - |
        set -e

        export BUILDKITD_FLAGS="--addr unix:///tmp/buildkitd.sock"

        echo "Starting buildkitd..."
        buildkitd $BUILDKITD_FLAGS > /tmp/buildkitd.log 2>&1 &

        echo "Waiting for socket..."
        for i in $(seq 1 20); do
            if [ -S /tmp/buildkitd.sock ]; then
                echo "BuildKit ready"
                break
            fi
            sleep 1
        done

        if [ ! -S /tmp/buildkitd.sock ]; then
            echo "BuildKit failed to start"
            cat /tmp/buildkitd.log
            exit 1
        fi

        tail -f /dev/null
    tty: true

  - name: azure-cli
    image: mcr.microsoft.com/azure-cli:azurelinux3.0-arm64@sha256:e1ea504fb79e4348935072055898f675367b9bade81608c4b51835e232b913fe
    volumeMounts:
    - name: auth-data
      mountPath: /root/.docker
    command:
    - sh
    - -c
    - cat
    tty: true

  - name: auxiliary
    image: alpine:3.22.4@sha256:310c62b5e7ca5b08167e4384c68db0fd2905dd9c7493756d356e893909057601
    command:
    - sh
    - -c
    - |
      apk add yq git &&
      cat
    tty: true
'''
        }
    }

(... rest of pipeline: stages and steps ...)

}
```

Each pod contains multiple containers with distinct responsibilities, which will be presented next.

### jnlp container

The `jnlp` container is responsible for establishing communication between the Jenkins controller and the Kubernetes agent pod.

This container runs the Jenkins remoting agent and acts as the primary communication channel between Jenkins and the pod executing the pipeline.

Although the entire Kubernetes pod represents the ephemeral Jenkins execution environment, the jnlp container is the component specifically responsible for:

* establishing connection back to Jenkins controller,
* receiving pipeline instructions,
* maintaining connectivity with the Jenkins controller,
* and coordinating execution inside the pod.

Without it Jenkins cannot control the pod. All other containers present in the agent's Pod specification do not talk to Jenkins directly. The remaining containers inside the pod provide specialized execution environments used during different pipeline stages. So the Pod's architecture is something like:


```txt

Jenkins Agent Pod
├── jnlp container
│     └── Jenkins remoting agent
├── trivy
│     └── application and image runtime for vulnerabilities check
├── podman-builder
│     └── image build/push runtime
├── app-build-container
│     └── .NET SDK
├── azure-cli container
│     └── Azure operations runtime
└── auxiliary container
      └── helper tooling runtime
```

As will be pointed out soon, when in a Jenkins pipeline stage there is the reference to `container(<container_name>)` the Jenkins pipeline is still orchestrated through the jnlp container. What happens is the switching of the execution context to another container inside the same pod.


### podman-builder container

The `podman-builder container` is responsible for building container images, exporting them to an Docker archive, and publishing the scanned image to Azure Container Registry.

The image archive is shared with the `trivy` container through an emptyDir volume, allowing vulnerability scanning to occur before publication.

This approach separates image building, security validation and publication into distinct pipeline stages without requiring a Docker daemon.

### trivy container

This container is responsible for scanning application artifacts and container images for vulnerabilities, using `trivy`. To scan application artifacts is used

```bash
trivy fs --exit-code 1 --scanners vuln,secret,misconfig --severity HIGH,CRITICAL ${buildPath}
```

To scan the container images `trivy`instruction is


```bash
trivy image --exit-code 1 --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --input ${imageArchivePath}
```

### app-build-container container

The `app-build-container` container runs the .NET SDK used to build the application targeted by a given pipeline.

### azure-cli container

The `azure-cli` container handles:
- Azure authentication,
- ACR token acquisition,
- and registry login workflows.

This separation keeps concerns isolated between:
- image building,
- cloud authentication,
- and Jenkins orchestration.

### auxiliary container

This container provides additional tooling required during pipeline execution, currently including:

* git
* yq

The decision to use a dedicated auxiliary container was intentional.

Instead of creating a single large custom CI image containing every required tool, the pipeline pod is currently composed of multiple specialized containers with distinct responsibilities.

This approach improves:

* separation of concerns,
* image simplicity,
* and independent tooling management.

An alternative approach would be maintaining a custom internal CI image bundling:

* Azure CLI,
* Git,
* yq,
* podman,
* .NET SDK
* and other utilities.

That would likely reduce pod complexity and startup overhead, but would also introduce:

* additional image maintenance,
* version management,
* image rebuild requirements,
* and tighter coupling between unrelated tooling dependencies.

At the current stage of the project, prioritizing modularity and experimentation was considered more valuable than minimizing container count.

### Container Image Versioning Strategy

All third-party container images used by the pipelines are pinned not only by tag, but also by immutable image digest.

Example:

```txt
image: quay.io/podman/stable
```

Most third-party container images are pinned by digest to improve reproducibility. The Podman builder is an exception: it currently uses the stable tag because maintaining a reliably retrievable historical image reference proved problematic. This means the Podman builder image can change independently of the pipeline configuration.

Choosing to have images pinned by digest approach was chosen to improve:

* pipeline reproducibility,
* execution consistency,
* ARM64 image stability,
* and operational predictability.

Without digest pinning, upstream image rebuilds could silently modify runtime behavior even when tags remain unchanged.

The tradeoff is that security and dependency updates become an explicit operational decision rather than an automatic side effect of image pulls.

This means image updates should be:

* reviewed,
* validated,
* and tested deliberately before pipeline runtime changes are introduced.




### Shared Volumes

Containers inside the pod share credentials through Kubernetes volumes.

This allows:
- ACR authentication artifacts,
- config files,
- container images 
- and temporary execution data

to be exchanged between containers without embedding secrets into images.

---

## Pipeline Design

The pipelines (one per application) evolved progressively during the project.

Initially, the goal was simply:
- repository checkout,
- image build,
- and image push.

The architecture later evolved into:
- reusable Groovy libraries,
- image tagging strategies,
- GitOps updates,
- and manifest repository synchronization.

### Pipeline stages

For the various application being deployed, the common pipeline stages between then include those presented next:

1. Checkout
2. Build Application
3. Check Compiled Application for Vulnerabilities
4. Build Container Image
5. Check Container Image for Vulnerabilities
6. Login to ACR
7. Push Container Image to Container Repository
8. Adjust image tags in Helm values
9. Push manifest changes back to GitHub

And here Jenkins _responsibility_ ends, since Jenkins is the CI pipeline. [ArgCD](https://github.com/MyProgrammingProjects/raspi-k3s-gitops)  will then, following GitOps approach, check if the cluster is in sync with the applications repositories and, if changes are detected in any of them, proceed with the proper cluster reconciliation so that the source of truth are the manifests stored in the repositories.



```groovy

pipeline {
    agent {
        kubernetes {
            yaml '''
            (... agent's pod containers definition ...)
'''
        }
    }

    options {
        skipDefaultCheckout(true)
    }

    environment {
        //Environment variables are grouped into:
        //- Azure authentication credentials
        //- image naming/tagging configuration
        //- GitOps repository targeting
    }

    stages {

        //Setup environment variables for image name based on commit hash
         stage('Checkout') {
            steps {
                echo 'Checking out source'
                script {
                    def scmVars = checkout scm
                    echo "Checked out commit ${scmVars.GIT_COMMIT}"

                    env.GIT_COMMIT_HASH = scmVars.GIT_COMMIT
                    env.IMAGE_TAG = "${env.GIT_COMMIT_HASH}"
                    env.FULL_IMAGE = "${env.ACR_FULLNAME}/${env.IMAGE_NAME}:${env.GIT_COMMIT_HASH}"
                    env.IMAGE_ARCHIVE_NAME = "${env.IMAGE_NAME}_${env.GIT_COMMIT_HASH}.tar"
                    env.IMAGE_ARCHIVE_PATH = "${env.IMAGE_ARCHIVE_VOLUME_PATH}/${env.IMAGE_NAME}_${env.GIT_COMMIT_HASH}.tar"
                }
            }
        }

        //Build the application to check code is compiling and to generate application assemblies
        stage('Build Application') {
            steps {
                container('app-build-container') {
                    script{
                        def build_dotnet_application = load 'jenkins/vars/build_dotnet_application.groovy'
                        build_dotnet_application.exec(env.PROJECT_PATH, env.PROJECT_BUILD_PATH, env.BUILD_CONFIGURATION)
                    }
            }
        }
        }

        //First stage of vulnerabilities check: check compiled application assemblies
        stage('Check Application Vulnerabilities') {
            steps {
                container('trivy') {
                    script{
                        def check_application_vulnerabilities = load 'jenkins/vars/check_application_vulnerabilities.groovy'
                        check_application_vulnerabilities.exec(env.PROJECT_BUILD_PATH)
                    }
            }
        }
        }

        //Build Container Image
        stage('Build Image') {
            steps {
                container('podman-builder') {
                   script
                   {
                     def container_build_image = load 'jenkins/vars/container_build_image.groovy'
                     container_build_image.exec(env.DOCKERFILE_PATH, env.FULL_IMAGE, env.IMAGE_ARCHIVE_PATH, env.BUILD_CONFIGURATION, env.BUILD_ENVIRONMENT)
                   }
                }
            }
        }

        //Second stage of vulnerabilities check: check created container image 
        stage('Check Image Vulnerabilities') {
            steps {
                container('trivy') {
                    script{
                        def check_image_vulnerabilities = load 'jenkins/vars/check_image_vulnerabilities.groovy'
                        check_image_vulnerabilities.exec(env.IMAGE_ARCHIVE_PATH)
                    }
            }
        }
        }

        //Do Azure Container Registry authentication
        stage('Login to ACR') {
            steps {
                container('azure-cli') {
                    script{
                        def az_acr = load 'jenkins/vars/az_acr.groovy'
                    az_acr.login(env.AZ_CLIENT_ID, env.AZ_CLIENT_SECRET, env.AZ_TENANT_ID, env.ACR_NAME)
                    }
            }
        }
        }
        
        //Push container image to Azure Container Registry
        stage('Push Image') {
            steps {
                container('podman-builder') {
                   script
                   {
                     def container_push_image = load 'jenkins/vars/container_push_image.groovy'
                     container_push_image.exec(env.IMAGE_ARCHIVE_PATH, env.FULL_IMAGE)
                   }
                }
            }
        }


        //Checkout GitOps repository, so that downstream stages update Helm chart values
        stage('Checkout infrastructure repository') {
             steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'github-ssh-jenkins',keyFileVariable: 'SSH_KEY')]) {
                dir('infra-repo') {
                   sh """
                   export GIT_SSH_COMMAND="ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no"
                   git clone git@github.com:${env.INFRA_GITHUB_REPOSITORY} --branch ${env.BRANCH_NAME} .
                   """
                    }       
                }
            }
        }

        //Using existing GitOps repository values.yml file, adjust image 
         stage('Adjust Image name') {
            steps {
                dir('infra-repo') {
                    container('auxiliary') {
                    sh """
                    yq e '
                    .app.image.tag = "${env.IMAGE_TAG}" | .app.image.repository = "${env.IMAGE_NAME}"
                    ' -i ${env.HELM_VALUES_PATH}
                    """
                    }
                }
            }
        }
        
        //Commit values.yml, providing the image that Kubernetes should be updated with.
        stage('Update values.yaml in infrastructure repository') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'github-ssh-jenkins',keyFileVariable: 'SSH_KEY')]) {
                dir('infra-repo') {
                   sh """
                   cat ${env.HELM_VALUES_PATH}
                   export GIT_SSH_COMMAND="ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no"

                   # Commit & push
                    git config user.email "${env.GITHUB_USER_USR}"
                    git config user.name "jenkins"

                    git add ${env.HELM_VALUES_PATH}
                    git commit -m "adjusted image name to ${env.FULL_IMAGE}"
                    git remote set-url origin git@github.com:${env.INFRA_GITHUB_REPOSITORY}
                    git push origin HEAD:${env.BRANCH_NAME}

                   """
                    }       
                }
            }
        }

        //Cleanup any file created on the workspace
        stage('Cleanup') {
            steps {
                cleanWs()
            }
        }
    }

    post {
        success {
            echo '✅ Container image published and GitOps repository updated successfully'
        }
        failure {
            echo '❌ Pipeline failed. Check the stage logs for details.'
        }
    }
}

```


### podman, trivy, SDKs usage

My architecture decision was to have no CI pipeline required tools running on the host and all in the Kubernetes cluster. For that reason, instead of having Docker commands executed directly against a host daemon, the pipeline uses such tools on isolated containers. These tools run inside their own containers on Jenkins agent Pod, for the reasons previously discussed.

### Shared Libraries

As pipelines started becoming repetitive, common logic was extracted into reusable Groovy scripts.

Examples include:
- ACR login logic
- container image build/publish logic

This reduced duplication while keeping the pipeline behavior explicit. These can be found in the [vars](https://github.com/MyProgrammingProjects/raspi-k3s-jenkins/tree/main/vars) folder .

### Shared volumes

* auth-data:	Shares registry authentication configuration between the relevant containers
* app-build-volume:	Shares compiled application artifacts between the .NET SDK and trivy containers
* image-volume:	Shares the built OCI archive between Podman and trivy

---

## Application Delivery Workflow

At the current stage, all applications follow a similar CI/CD workflow structure.

The goal is creating a reusable Kubernetes-native delivery pipeline capable of:

* building container images,
* publishing artifacts,
* updating deployment manifests,
* and eventually supporting fully automated GitOps reconciliation.

The current high-level workflow is:

```txt
Developer 
    ↓
GitHub Repository 
    ↓ 
Manual Jenkins Pipeline Trigger 
    ↓ 
Ephemeral Kubernetes Agent Pod 
    ├── Checkout Source Code 
    ├── Build Application 
    ├── Check Application Vulnerabilities 
    ├── Build ARM64 Image with Podman 
    ├── Check Image Vulnerabilities
    ├── Authenticate to Azure 
    ├── Push Image to Azure Container Registry 
    └── Update Helm Values Repository 
                                        ↓ 
                                    Argo CD 
                                        ↓ 
                    Kubernetes Desired State Reconciliation
```

A high-level description of the workflow:

* developer pushes code to Github 
* Jenkins pipeline triggered manually
* ephemeral agent is created
* application is compiled in .NET SDK contained image
* the compiled application artifacts are scanned for vulnerabilities using Trivy
* image built with Podman
* resulting image if check for vulnerabilities with Trivy
* image pushed to ACR (Azure Container Registry)
* Helm values for the application are updated
* GitOps repository (where the Kubernetes manifests for the application reside) is updated
* Argo CD for continuous delivery by ensuring reconciliation being the cluster current and desired states.

At this stage, pipelines are intentionally triggered manually instead of automatically through GitHub webhooks.

This provides tighter operational control while:

* validating pipeline behavior,
* evolving the infrastructure,
* and refining deployment workflows.

One interesting aspect is that pipeline execution result could also be seen on the GitHub repository.

---

## Application-Specific Pipelines

This repository documents the Jenkins platform architecture, shared pipeline strategy, ephemeral agent implementation, and common CI/CD workflow used throughout the Home Lab.

However, the individual pipeline implementations for each application are maintained separately in the [`raspi-k3s-applications`](https://github.com/MyProgrammingProjects/raspi-k3s-applications) repository.

Those pipelines contain application-specific details such as:

* build configuration,
* container image generation,
* deployment manifest updates,
* and service-specific CI/CD requirements.



For the detailed implementation of each application pipeline, refer to the Jenkins directory in the [`raspi-k3s-applications`](https://github.com/MyProgrammingProjects/raspi-k3s-applications) repository.

---

## GitOps Direction

One major architectural realization during the project was the importance of separating:
- application source code
- and Kubernetes manifests

Initially, manifests and application code existed in the same repository.

This created problems:
- concurrent pipeline commits,
- Git conflicts,
- and repository lifecycle coupling.

The project later evolved toward:
- dedicated manifest repositories,
- Helm-based deployment repositories,
- and GitOps-style deployment flows.

The final architecture is:

1. Application pipeline builds image
2. Pipeline pushes image to ACR
3. Pipeline updates Helm values repository
4. Argo CD detects repository changes
5. Argo CD synchronizes cluster state

This approach aligns much more closely with modern GitOps practices.

It also separates:
- application lifecycle
- infrastructure lifecycle
- deployment lifecycle

which simplifies long-term maintenance.

---

## Deployment Strategy

At the current stage, Jenkins pipelines are triggered manually instead of automatically through GitHub webhooks.

This decision was intentional.

Since the platform architecture is still evolving, manual execution provides tighter control over:

* deployment timing,
* infrastructure validation,
* troubleshooting,
* and pipeline experimentation.

The goal at this stage is understanding and stabilizing the CI/CD flow rather than maximizing deployment automation.

The long-term direction is to continue evolving toward a complete GitOps workflow, where:

* Jenkins is responsible for artifact generation and manifest updates,
* while Argo CD handles continuous reconciliation of the Kubernetes desired state.

This also reinforces the separation between:

* Continuous Integration (CI)
* and Continuous Delivery/Deployment (CD)


---

## Problems Encountered

This project involved several implementation and operational challenges.

### Persistent Volume binding issues

PVC binding initially failed due to:
- mismatched `storageClassName`
- incorrect node affinity
- and PVC/PV lifecycle inconsistencies

This highlighted how tightly Kubernetes scheduling interacts with storage configuration.

### Jenkins volume permissions

The Jenkins container failed initially because:
- the mounted `hostPath` volume was owned by `root`
- while the Jenkins container runs as UID 1000

This was solved using an init container responsible for adjusting permissions before Jenkins startup.

### Kubernetes cloud agent connection

Agent pods were created successfully but never connected to Jenkins.

The root cause was:
- missing WebSocket configuration inside the Jenkins Kubernetes Cloud setup

Once WebSockets were enabled, agents connected correctly.

### ARM64 compatibility

Several images and tools required validation for ARM64 support.

This was especially important for:
- Jenkins inbound agents
- Azure CLI images

Digest pinning was also introduced to reduce unexpected image changes.

### Git conflicts during GitOps updates

When multiple pipelines attempted to update the same repository simultaneously, Git conflicts occurred because:
- pipelines were committing independently,
- while sharing a single manifest repository.

This reinforced the architectural decision to:
- separate repositories,
- and isolate deployment manifests from application source code.

---

## Current Status

At the current stage:
- Jenkins controller is running inside Kubernetes
- ephemeral Kubernetes agents are operational
- Podman image builds are working
- Vulnerabilities checks are perform both on application and final image levels.
- ACR integration is functional
- Podman image pushes to ACR are working
- pipeline libraries are partially modularized
- Helm deployment repositories are being separated
- Argo CD is fully operational completing the GitOps deployment flow.

---

## References

Official documentation and resources consulted during this project include:

- Jenkins Kubernetes Plugin documentation
- Jenkins Docker documentation
- Kubernetes official documentation
- Helm documentation
- Podman official repository
- Trivy official repository
- Azure Container Registry documentation
- Argo CD documentation

Additional community resources were also used for troubleshooting and implementation comparisons.

---


## Known Observations

### Jenkins Ephemeral Agents and Image Pulls

During testing, auxiliary container images were frequently pulled when new agent pods were created.

Potential contributing factors:

- Ephemeral Kubernetes agent pods
- Scheduling on different cluster nodes
- Image pull policy configuration
- Container runtime image cache availability

Further investigation is required to determine the precise cause and whether image pre-pulling or local registry caching would improve startup times.








