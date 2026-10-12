def exec(String projectPath, String buildOutputPath, String buildConfiguration) {
    sh """
                    dotnet build ${projectPath} --configuration ${buildConfiguration} --output ${buildOutputPath}

                    ls -la ${buildOutputPath}
"""
}

return this   // 🔴 REQUIRED for load()