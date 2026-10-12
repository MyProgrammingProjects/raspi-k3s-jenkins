def exec(String imageArchivePath, String imageName) {
    sh """

    ls -la ${imageArchivePath}

    podman load -i ${imageArchivePath}

    ls -la  /home/user/acr/

    USERNAME=\$(cat /home/user/acr/username.txt)
    ACR_NAME=\$(cat /home/user/acr/acr.txt)
    TOKEN=\$(cat /home/user/acr/token.txt)

    podman login -u \$USERNAME -p \$TOKEN \$ACR_NAME

    podman push  ${imageName}
    
    """
}

return this   // 🔴 REQUIRED for load()