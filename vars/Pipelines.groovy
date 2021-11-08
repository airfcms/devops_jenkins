//Cleaning is needed(Testing in needed -> Env.variables) and Integration with SonnarQube

def call(body) {
    // evaluate the body block, and collect configuration into the objectdef
    pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    scmUrl = scm.getUserRemoteConfigs()[0].getUrl()

    pipeline {
      /*
          environment{
            REPOSITORY_NAME = pipelineParams['repositoryName']
            BUILD_DIRECTORY = pipelineParams['cmakeBuildDir']
            }
      */
        agent any
          stages {
            stage('build') {
              agent{
                /*
                environment{
                  DOCKER_IMAGE = pipelineParams['dockerImage']
                  DOCKER_REG_ARTIFACTORY = pipelineParams['dockerRegistryUrl']
                  scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
                }
                */
                docker {
                  reuseNode true //Don't see the difference on::off ### From the consoleOutput it seems the image is removed when the building stage is finished. Need to check why!!! <---------------

                  image pipelineParams['dockerImage']
                  registryUrl pipelineParams['dockerRegistryUrl']
                }
              }
              steps {
                //Link can't be literally here #########
                sh 'env | sort' //To check available global variables

                sh"""
                  echo Cloning Repository in Docker Image Workspace
                  git clone ${scmUrl}
                  cmake -S ${pipelineParams['repositoryName']} -B ${pipelineParams['cmakeBuildDir']}
                  make -C ${pipelineParams['cmakeBuildDir']}
                  ./${pipelineParams['cmakeBuildDir']}/${pipelineParams['repositoryName']}
                 """
             }
            } //stage(build) closed bracket
            /*
            stage('unit testing'){

            }
            stage('sw integration testing') {

            }
            stage('hw/sw integration testing') {

            }
            stage('static analysis') {

            }
            */
            stage('deploy') { //It seems that the docker image is remove before this stage !!IMPORTANT!!
            /*
              environment{
                GENERIC_REGISTRY_ID = pipelineParams['artifactoryGenericRegistry_ID']
                REGISTRY_MAIN_DIRECTORY = 'build-repo'
                ARTIFACT_NAME = env.REPOSITORY_NAME
              }
            */
              steps{

                rtUpload(
                      serverId: pipelineParams['artifactoryGenericRegistry_ID'],
                      spec: '''{
                                "files": [
                                           {
                                            "pattern": "*/${pipelineParams['repositoryName']}",
                                            "target": "build-repo/"
                                            }
                                         ]
                                }'''
                )

                rtPublishBuildInfo ( //Send notification and prevents an error.
                    serverId: pipelineParams['artifactoryGenericRegistry_ID']
                )
              }
            } //stage(deploy) closed bracket
          } //stages body closed bracket
          post {
              always{
                sh 'echo In post block -> Clean Workspace'
                clean_workspace_WorkAround(WORKSPACE)
              }
          }//post body closed bracket
        } //pipeline body closed bracket
} //def body closed bracket

def clean_workspace_WorkAround(String workspace){
    sh 'rm -rf ${workspace}/*' //Work around because the declarative sintax bugs with deleteDir() and cleanWS()
}