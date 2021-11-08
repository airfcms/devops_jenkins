//Cleaning is needed and Integration with SonnarQube

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
          environment{
            REPOSITORY_NAME= pipelineParams['repositoryName']
            BUILD_DIRECTORY= pipelineParams['cmakeBuildDir']
            }

        agent any
          stages {
            stage('build') {
              agent{
                environment{
                  DOCKER_IMAGE = pipelineParams['dockerImage']
                  DOCKER_REG_ARTIFACTORY = pipelineParams['dockerRegistryUrl']
                  SCM_URL = scm.getUserRemoteConfigs()[0].getUrl()
                }
                docker {
                  reuseNode true //Don't see the difference on::off ### From the consoleOutput it seems the image is removed when the building stage is finished. Need to check why!!! <---------------

                  image env.DOCKER_IMAGE
                  registryUrl env.DOCKER_REG_ARTIFACTORY
                }
              }
              steps {
                //Link can't be literally here #########
                sh 'env | sort' //To check available global variables

                sh"""
                  echo Cloning Repository in Docker Image Workspace
                  git clone ${env.SCM_URL}
                  cmake -S ${env.REPOSITORY_NAME} -B ${env.BUILD_DIRECTORY}
                  make -C ${env.BUILD_DIRECTORY}
                  ./${env.BUILD_DIRECTORY}/${env.REPOSITORY_NAME}
                 """
             }
            }
            //Not the stage Várzea asked stage(???)
            //For testing purposes
            //Push the artifact to Azure Artifactory Generic registry
            stage('deploy') { //It seems that the docker image is remove before this stage !!IMPORTANT!!
              environment{
                GENERIC_REGISTRY_ID = pipelineParams['artifactoryGenericRegistry_ID']
                REGISTRY_MAIN_DIRECTORY = 'build-repo'
                ARTIFACT_NAME = env.REPOSITORY_NAME
              }
              steps{

                rtUpload(
                      serverId: env.GENERIC_REGISTRY_ID,
                      spec: '''{
                                "files": [
                                           {
                                            "pattern": "*/${env.ARTIFACT_NAME}",
                                            "target": "${env.REGISTRY_MAIN_DIRECTORY}/"
                                            }
                                         ]
                                }'''
                )

                rtPublishBuildInfo ( //Send notification and prevents an error.
                    serverId: env.GENERIC_REGISTRY_ID
                )
              }
            } //stage(deploy) closed bracket
          } //stages body closed bracket
          post {
              always{
                sh 'echo In post block -> Clean Workspace'
                clean_workspace_WorkAround(env.WORKSPACE)
              }
          }//post body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket

def clean_workspace_WorkAround(String workspace){
    sh 'rm -rf ${workspace}/*' //Work around because the declarative sintax bugs with deleteDir() and cleanWS()
}