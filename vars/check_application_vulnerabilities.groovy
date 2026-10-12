def exec(String buildPath) {
    sh """

                    ls -la ${buildPath}

                    trivy fs --exit-code 1 --scanners vuln,secret,misconfig --severity HIGH,CRITICAL ${buildPath}
"""
}

return this   // 🔴 REQUIRED for load()