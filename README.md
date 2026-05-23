# Jenkins on Kubernetes with Ephemeral ARM64 Build Agents

## Architecture Overview

This repository documents the setup of Jenkins running on a Kubernetes cluster built with Raspberry Pi nodes, with the goal of exploring cloud-native CI/CD execution patterns, using Kubernetes-native infrastructure, while adapting Jenkins into that environment, aiming for GitOps-oriented delivery workflows in a constrained ARM64 hardware.

The architecture is centered around ephemeral Kubernetes-based Jenkins agents, containerized workloads, where each pipeline execution dynamically creates isolated pods responsible for building and publishing container images.

The current setup includes:

- Custom Helm chart instead of the official Jenkins chart
- Jenkins controller running inside Kubernetes
- Kubernetes ephemeral agents
- BuildKit for container image builds
- Azure Container Registry (ACR) as image registry
- Shared volumes between containers inside the agent pod
- GitOps-oriented deployment strategy
- Planned ArgoCD integration

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
  - name: docker-config
    emptyDir: {}
  containers:
  - name: buildkit
    image: moby/buildkit:v0.29.0@sha256:a678f07cfe6d0c03c721810e28fc628df91a2f8d2bb93dff353647e70920fbee
    securityContext:
      privileged: true
    volumeMounts:
      - name: docker-config
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
    - name: docker-config
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
├── buildkit container
│     └── image build runtime
├── azure-cli container
│     └── Azure operations runtime
└── auxiliary container
      └── helper tooling runtime
```

As will be pointed out soon, when in a Jenkins pipeline stage there is the reference to `container(<container_name>)` the Jenkins pipeline is still orchestrated through the jnlp container. What happens is the switching of the execution context to another container inside the same pod.


### buildkit container

The `buildkit` container runs:
- `buildkitd`
- `buildctl`

This container is responsible for:
- building container images,
- pushing images to ACR,
- and avoiding Docker-in-Docker approaches.

Using BuildKit directly provides:
- better isolation,
- improved cloud-native alignment,
- and reduced dependency on a Docker daemon.

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
* BuildKit tooling,
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
image: moby/buildkit:v0.29.0@sha256:a678f07cfe6d0c03c721810e28fc628df91a2f8d2bb93dff353647e70920fbee
```

Although image tags improve readability, the digest is the actual immutable reference.

This approach was chosen to improve:

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
- Docker config files,
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
2. Login to ACR
3. Build & Push Image
4. Adjust image tags in Helm values
5. Push manifest changes back to GitHub

And here Jenkins _responsibility_ ends, since Jenkins is the CI pipeline. ArgCD (planned for future usage) will then, following GitOps approach, check if the cluster in synced with the applications repositories and, case changes exist on any of them, proceed with the proper cluster reconciliation so that the source of truth are the repositories manifests.



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
                    env.FULL_IMAGE = "${env.IMAGE_NAME}:${env.GIT_COMMIT_HASH}"
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

        //Build image using buildkit container and push it into ACR
        stage('Build & Push Image') {
            steps {
                container('buildkit') {
                   script
                   {
                     def container_build_publish = load 'jenkins/vars/container_build_publish.groovy'
                     container_build_publish.exec(env.DOCKERFILE_PATH, env.FULL_IMAGE)
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
    }

    post {
        success {
            echo '✅ Docker image built and container running!'
        }
        failure {
            echo '❌ Something went wrong with Docker pipeline.'
        }
    }
}

```


### BuildKit usage

My architecture decision was to have not CI pipeline required tools running on the host and all in the Kubernetes cluster. For that reason, instead of having Docker commands executed directly against a host daemon, the pipeline uses BuildKit. BuildKit in run inside its own container on Jenkins agent Pod, for the reasons previously discussed.

The build process uses:
- `buildkitd`
- `buildctl`
- `dockerfile.v0` frontend
- explicit socket communication

This allows image creation without requiring Docker-in-Docker.

### Shared Libraries

As pipelines started becoming repetitive, common logic was extracted into reusable Groovy scripts.

Examples include:
- ACR login logic
- container image build/publish logic

This reduced duplication while keeping the pipeline behavior explicit. These can be found in the [vars](https://github.com/MyProgrammingProjects/raspi-k3s-jenkins/vars/) folder .

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
    ├── Authenticate to Azure 
    ├── Build ARM64 Image with BuildKit 
    ├── Push Image to Azure Container Registry 
    └── Update Helm Values Repository 
                                        ↓ 
                                    ArgoCD 
                                        ↓ 
                    Kubernetes Desired State Reconciliation
```


High level description of such :

* developer pushes code into Github 
* Jenkins pipeline triggered manually
* ephemeral agent is created
* image built with BuildKit
* image pushed to ACR (Azure Container Registry)
* Helm values for the application are updated
* GitOps repository (where the Kubernetes manifests for the application resides) is updated
* __future ArgoCD reconciliation planned._

At this stage, pipelines are intentionally triggered manually instead of automatically through GitHub webhooks.

This provides tighter operational control while:

* validating pipeline behavior,
* evolving the infrastructure,
* and refining deployment workflows.

The long-term direction is progressively moving toward:

* stronger GitOps separation,
* dedicated deployment repositories,
* and automated reconciliation through ArgoCD.

The detailed application pipelines and shared Jenkins libraries will be documented separately as the platform evolves.

One interesting aspect is that pipeline execution result could also be seen on the GitHub repository.


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

The intended final architecture is:

1. Application pipeline builds image
2. Pipeline pushes image to ACR
3. Pipeline updates Helm values repository
4. ArgoCD detects repository changes
5. ArgoCD synchronizes cluster state

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

The long-term direction is evolving toward a more complete GitOps workflow, where:

* Jenkins is responsible for artifact generation and manifest updates,
* while ArgoCD handles continuous reconciliation of the Kubernetes desired state.

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
- BuildKit images

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
- BuildKit-based image builds are working
- ACR integration is functional
- pipeline libraries are partially modularized
- Helm deployment repositories are being separated

The next major step is integrating ArgoCD and completing the GitOps deployment flow.

---

## References

Official documentation and resources consulted during this project include:

- Jenkins Kubernetes Plugin documentation
- Jenkins Docker documentation
- Kubernetes official documentation
- Helm documentation
- BuildKit official repository
- Azure Container Registry documentation
- ArgoCD documentation

Additional community resources were also used for troubleshooting and implementation comparisons.









