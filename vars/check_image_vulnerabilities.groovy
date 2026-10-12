def exec(String imageArchivePath) {
    sh """

    ls -la ${imageArchivePath}

    trivy image --exit-code 1 --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --input ${imageArchivePath}
"""
}

return this   // 🔴 REQUIRED for load()