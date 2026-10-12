def login(String azClientId, 
            String azClientSecret, 
            String azTenantId,
            String acrName) {
    sh """

                    # https://matthiasguentert.net/how-to-log-into-an-azure-container-registry-using-podman-cli/

                    az login --service-principal \
                        --username ${azClientId} \
                        --password ${azClientSecret} \
                        --tenant ${azTenantId}

                    TOKEN=\$(az acr login --name ${acrName} --expose-token --query accessToken -o tsv)


                    mkdir -p /root/acr
                    # The username must be: The username must be:
                    # This is a special value required by ACR when using tokens (Microsoft Docs)
                    
                    echo "${acrName}.azurecr.io" > /root/acr/acr.txt
                    echo "00000000-0000-0000-0000-000000000000" > /root/acr/username.txt
                    echo "\$TOKEN" > /root/acr/token.txt

"""
}

return this   // 🔴 REQUIRED for load()