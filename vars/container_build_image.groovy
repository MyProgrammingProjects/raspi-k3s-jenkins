def exec(String dockerfilePath,
            String imageName,
            String imageArchivePath, 
            String buildConfiguration,
            String buildEnvironment) {
    sh """

    podman build \
        --file=${dockerfilePath} \
        --build-arg BUILD_CONFIGURATION="${buildConfiguration}" \
        --build-arg BUILD_ENVIRONMENT="${buildEnvironment}" \
        --tag ${imageName} \
        .

    podman image ls

    podman save \
    --format docker-archive \
    --output "${imageArchivePath}" \
    "${imageName}"

    ls -la ${imageArchivePath}

    """
}

return this   // 🔴 REQUIRED for load()


