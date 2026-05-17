def exec(String dockerfilePath, 
            String imageName) {
    sh """

    export DOCKER_CONFIG=/home/user/.docker

    buildctl \
        --addr unix:///tmp/buildkitd.sock \
        build \
        --frontend dockerfile.v0 \
        --local context=. \
        --local dockerfile=. \
        --opt filename=${dockerfilePath} \
        --output type=image,name=${imageName},push=true
    """
}

return this   // 🔴 REQUIRED for load()