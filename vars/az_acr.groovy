def login(String azClientId, 
            String azClientSecret, 
            String azTenantId,
            String acrName) {
    sh """
                    az login --service-principal \
                        --username ${azClientId} \
                        --password ${azClientSecret} \
                        --tenant ${azTenantId}

                    TOKEN=\$(az acr login --name ${acrName} --expose-token --query accessToken -o tsv)


                    mkdir -p /root/.docker
                    # The username must be: The username must be:
                    # This is a special value required by ACR when using tokens (Microsoft Docs)
                    cat > /root/.docker/config.json <<EOF
                    {
                        "auths": {
                            "${acrName}.azurecr.io": {
                            "username": "00000000-0000-0000-0000-000000000000",
                            "password": "\$TOKEN"
                            }
                        }
                    }
EOF
# EOF must start at column 0, it cannot have indentation
"""
}

return this   // 🔴 REQUIRED for load()